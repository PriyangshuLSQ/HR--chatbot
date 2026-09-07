/**
 * Conversation orchestration: turns an employee utterance into a bot turn.
 *
 * Sits between the NLU (`lib/nlu.ts`), the HRMS adapter (`lib/darwinbox.ts`)
 * and the ticket store (`lib/hr-store.ts`). Holds the dialogue state that makes
 * the assistant feel continuous rather than stateless:
 *
 *   - a pending clarification, so the answer to "which did you mean?" narrows
 *     the search instead of being re-parsed as a fresh question
 *   - a pending escalation offer, so a bare "yes" creates the ticket
 *   - the last resolved topic, so follow-ups ("what about sick leave?") inherit
 *     context instead of falling to low confidence
 *
 * Answer precedence, in strict order:
 *
 *   1. Sensitive matters — harassment, grievances. Deterministic: pattern match,
 *      scripted response, routed to the HR Head. Never reaches a model, because
 *      a paraphrased or missed harassment report is not a recoverable error.
 *   2. The employee's own records — leave balance, payslip, request status, live
 *      from Darwinbox. No document contains these.
 *   3. HR's indexed documents, retrieved and written up by the model, cited.
 *      This is the only source of policy answers.
 *   4. One clarifying question where the subject is genuinely ambiguous.
 *   5. Otherwise: say it is not in the documents, and offer a human.
 *
 * There are no hand-written answers behind step 3. The matcher used to hold ~20
 * of them and they took precedence over the corpus, which meant uploading a
 * policy could not change what the bot said about a topic it already "knew" —
 * and what it knew was whatever someone typed into a source file, with no
 * citation and no way for HR to correct it. Retrieval is now the whole answer.
 */

import {
  understand,
  intentById,
  labelForIntent,
  type Intent,
  type IntentAction,
  type NluResult,
  type RouteTarget,
} from './nlu';
import type { FAQ } from './chatbot-data';
// Types only. The mock adapter's fetch* functions are no longer called from anywhere in the
// answer path — see runAction below. `lib/darwinbox.ts` still exists because DataCards is
// typed against these shapes; nothing serves data from it.
import type { LeaveBalance, PayslipSummary, TrackedRequest } from './darwinbox';
import { createTicket, type Ticket } from './hr-store';
import { RELEVANCE_THRESHOLD } from './knowledge/types';
import type { AnswerMode, Citation, KnowledgeAnswer } from './knowledge/types';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type CardPayload =
  | { kind: 'leave_balance'; balances: LeaveBalance[] }
  | { kind: 'payslip'; payslip: PayslipSummary }
  | { kind: 'requests'; requests: TrackedRequest[]; heading: string }
  | { kind: 'ticket'; ticket: Ticket };

export interface BotTurn {
  content: string;
  decision: NluResult['decision'] | 'action' | 'ack';
  confidence: number;
  intentId?: string;
  intentLabel?: string;
  clarify?: NluResult['clarify'];
  card?: CardPayload;
  /** Renders the "escalate to HR" affordance under the message. */
  offerEscalation?: boolean;
  sensitive?: boolean;
  /** Feedback is collected on substantive answers, not acknowledgements. */
  collectFeedback: boolean;
  corrections: { from: string; to: string }[];
  /** Source passages, when the answer came from HR's uploaded documents. */
  citations?: Citation[];
  /** How a document-backed answer was produced — written, or quoted verbatim. */
  aiMode?: AnswerMode;
  /** The local model that wrote the answer, shown under the citations. */
  aiModel?: string;
}

export interface ConversationContext {
  pendingClarify?: NluResult['clarify'];
  /** The query that triggered the clarification, so we can escalate it later. */
  pendingQuery?: string;
  pendingRoute?: RouteTarget;
  pendingTags?: string[];
  pendingConfidence?: number;
  /**
   * The bot asked "shall I raise a ticket?" and is waiting for the answer.
   *
   * Strictly that: a question was put to the employee and their next message is the reply to
   * it. It is NOT "escalation is available here" — every answer offers that, via
   * `BotTurn.offerEscalation` and a button the employee has to press on purpose.
   *
   * The distinction has teeth because a bare "okay" while this is set files a ticket. Set after
   * a successful answer, it made "okay" mean consent when it meant acknowledgement: one real
   * conversation went leave question → answer → "okay" → HR-5YC8F1 raised against HR Ops. So
   * only `noCoverageTurn` and the clarify-rejected path — the two turns that actually ask — may
   * set this, and any turn that answers the question must clear it.
   */
  offeredEscalation?: boolean;
  lastIntentId?: string;
  lastQuery?: string;
}

export interface RespondOptions {
  faqs: FAQ[];
  email?: string;
  userName?: string;
  transcript: { role: 'user' | 'bot'; text: string }[];
  /**
   * Queries the RAG layer over HR's uploaded documents.
   *
   * Injected rather than imported so this module stays isomorphic and unit
   * testable — the retrieval stack is server-only (node:fs, node:zlib), and the
   * NLU holdout tests run it in bare Node with no HTTP available.
   */
  /**
   * Retrieval over HR's documents.
   *
   * @param onToken called with each fragment as the model writes it, when the
   *     caller wants to render progressively. The promise still resolves with the
   *     complete answer either way — the streamed text is a preview of it, never
   *     the source of truth — so the precedence logic below is unchanged whether
   *     the caller streams or not.
   */
  askKnowledge?: (
    question: string,
    history: { role: 'user' | 'bot'; text: string }[],
    onToken?: (text: string) => void
  ) => Promise<KnowledgeAnswer | null>;
  /** Passed through to `askKnowledge`. Set by the chat window; absent in tests. */
  onToken?: (text: string) => void;
}

// ---------------------------------------------------------------------------
// Small dialogue-act detectors
// ---------------------------------------------------------------------------

const AFFIRMATIVE = /^(y|ye|yes|yeah|yep|yup|ok|okay|sure|please|pls|do it|go ahead|escalate|raise it)\b/i;
const NEGATIVE = /^(n|no|nope|nah|not now|its ok|it's ok|no thanks|cancel)\b/i;
const THANKS = /^(thanks|thank you|thanku|ty|great|perfect|awesome|got it|cool)\b/i;

/**
 * A bare acknowledgement: the employee is closing the exchange, not asking anything.
 *
 * Deliberately checked *after* the escalation-offer branch, because the same word means
 * different things in the two positions — "okay" is consent when the bot has just asked whether
 * to raise a ticket, and is small talk when it has not. Reaching here means nothing was asked.
 *
 * Needed as its own case because otherwise an acknowledgement is sent to retrieval, matches
 * nothing, and returns "I don't have a reliable answer to that — shall I raise a ticket?", which
 * is both a non-sequitur and an invitation to say "okay" a second time.
 */
const ACKNOWLEDGEMENT =
  /^(ok|okay|okey|oke|k|kk|alright|all right|sure|fine|noted|understood|makes sense)\b/i;

/**
 * An outright request for a ticket or a person, whether or not one was offered.
 *
 * This has to stand on its own rather than lean on `offeredEscalation`. "Can u raise a ticket
 * about this very serious" was answered with a menu of leave and expense topics: it is too long
 * for the affirmative branch, matches no intent strongly, and nothing else in the pipeline reads
 * it as a request. Two other phrasings — "please raise a ticket", "escalate this to HR" — did
 * work, but only by accident, because AFFIRMATIVE begins with `please` and `escalate` and the
 * flag happened to still be set from an earlier answer. An explicit ask is now handled
 * explicitly, which is both narrower and reliable.
 */
const ESCALATION_REQUEST =
  /\b(raise|create|open|file|log|submit)\s+(a\s+|an\s+|the\s+)?(ticket|case|complaint|grievance|issue)\b/i;

const WANTS_A_HUMAN =
  /\b(escalate\s+(this|it)|speak\s+(to|with)|talk\s+(to|with)|connect\s+me|put\s+me\s+through|need\s+a\s+human|real\s+person)\b/i;

/**
 * Asking how the process works, not asking us to start it.
 *
 * "How do I raise a ticket" is a question with an answer; filing one on their behalf would
 * answer a question they did not ask and leave a ticket someone has to close.
 */
const PROCESS_QUESTION = /\b(how\s+(do|can|would|should)\s+i|how\s+to|what.s?\s+the\s+process|where\s+do\s+i|can\s+i\s+see)\b/i;
const GREETING = /^(hi|hii|hello|hey|good morning|good afternoon|good evening|namaste)\b/i;

/** Words that signal a query is a continuation rather than a new topic. */
const FOLLOWUP = /\b(what about|how about|and|also|mine|that|it|instead|too)\b/i;

// ---------------------------------------------------------------------------
// Employee data answers
// ---------------------------------------------------------------------------

/**
 * Data-intent handler. Deliberately answers nothing.
 *
 * This used to serve leave balances, payslips and request statuses from
 * `lib/darwinbox.ts` — a mock adapter returning realistic fixtures. That was fine while
 * there was no data layer behind it, and actively dangerous once there was: an employee
 * asking "how many casual leaves do I have left?" was told "5 available (of 12 entitled)"
 * and "your live leave balance from Darwinbox", when the HR extract contains no casual
 * leave and no comp-off at all. Confident, specific, and invented — the exact failure the
 * grounding rules exist to prevent, and worse than any refusal because nothing signals it.
 *
 * Returning null lets every data question fall through to the backend, which resolves it
 * against the authenticated employee's real record (and the policy documents) or says it
 * cannot. Questions with no source in the extract — payslips, expense claims, asset
 * requests — now route to HR Ops instead of being fabricated.
 *
 * The signature is kept so the dispatch above is unchanged, and so this comment sits where
 * the next person looks for the mock.
 */
async function runAction(
  _action: IntentAction,
  _email: string | undefined
): Promise<{ content: string; card?: CardPayload } | null> {
  return null;
}

// ---------------------------------------------------------------------------
// Escalation
// ---------------------------------------------------------------------------

/**
 * Files the escalation, or returns null if the ticket store rejected it.
 *
 * Null is handled at every call site rather than thrown: the bot has to say
 * something, and "I could not raise the ticket, here is who to contact" is a
 * usable answer where a claimed ticket number that does not exist is not.
 */
/**
 * A route that actually reaches a person.
 *
 * `pendingRoute` is stamped from every turn's NLU result, and an ordinary answered question
 * carries `none` — not a queue, just the absence of one. Passing that through to a ticket would
 * file it nowhere, so anything that is not a real desk falls back to HR Ops.
 */
function humanRoute(route: RouteTarget | undefined): RouteTarget {
  return route === 'hrbp' || route === 'hr_head' || route === 'hr_ops' ? route : 'hr_ops';
}

async function raise(
  query: string,
  reason: string,
  route: RouteTarget,
  tags: string[],
  confidence: number,
  sensitive: boolean,
  opts: RespondOptions
): Promise<Ticket | null> {
  try {
    return await createTicket({
      query,
      reason,
      raisedBy: opts.email ?? 'unknown@company.com',
      raisedByName: sensitive ? 'Withheld (confidential)' : opts.userName ?? 'Employee',
      route,
      sensitive,
      tags,
      confidence,
      transcript: opts.transcript,
    });
  } catch {
    return null;
  }
}

/**
 * What the bot says when the escalation could not be filed. It names a human
 * rather than asking the employee to try again — if the ticket store is down,
 * retrying is not going to help them.
 */
function escalationFailedTurn(sensitive: boolean): BotTurn {
  return {
    content: sensitive
      ? "I couldn't file this for you — the ticket system isn't responding.\n\nPlease contact the **HR Ops Team** or your **HRBP** directly. This matters too much to leave sitting in a form that failed."
      : "I couldn't raise the ticket — the ticket system isn't responding right now.\n\nPlease email **hrops@company.com** with your question, or try again in a few minutes. Sorry about that.",
    decision: 'escalate',
    confidence: 0,
    collectFeedback: false,
    corrections: [],
  };
}

// ---------------------------------------------------------------------------
// Answer assembly
// ---------------------------------------------------------------------------

/**
 * Live records from Darwinbox — a leave balance, a payslip, a request status.
 *
 * These are not answers written by anyone; they are the employee's own data, and
 * no amount of retrieval over policy documents can produce them. Returns null if
 * the intent carries no action or the HRMS call came back empty, in which case
 * the question falls through to the documents like any other.
 */
async function actionTurn(
  intent: Intent,
  confidence: number,
  corrections: BotTurn['corrections'],
  opts: RespondOptions
): Promise<BotTurn | null> {
  const action = intent.action ?? 'none';
  if (action === 'none') return null;

  const result = await runAction(action, opts.email);
  if (!result) return null;

  return {
    content: result.content,
    card: result.card,
    decision: 'action',
    confidence,
    intentId: intent.id,
    intentLabel: labelForIntent(intent),
    collectFeedback: true,
    corrections,
    offerEscalation: true,
  };
}

/**
 * The answer path: retrieval over HR's indexed documents, written up by the
 * local model and cited.
 *
 * This is the only source of policy answers. There is no hand-written fallback
 * behind it — if the corpus does not cover the question, this returns null and
 * the employee is offered a human rather than a guess. That is the whole point:
 * an answer about notice periods or reimbursement caps that nobody at this
 * company wrote is worse than no answer at all.
 *
 * @param topic the classified subject, when the matcher recognised one. Used for
 *     the weekly digest's grouping — "which topic are we failing at" is the
 *     actionable unit, and it survives the corpus being re-indexed, which a
 *     document id does not.
 * @param needed relevance floor; callers raise it where a clarifying question
 *     would serve the employee better than a marginal passage
 */
async function knowledgeTurn(
  query: string,
  opts: RespondOptions,
  topic: { intentId?: string; intentLabel?: string },
  corrections: BotTurn['corrections'],
  needed: number = RELEVANCE_THRESHOLD
): Promise<BotTurn | null> {
  if (!opts.askKnowledge) return null;

  // A retrieval failure must not break the chat — it degrades to the same
  // "I could not find this, shall I get you a human" path as a miss.
  let knowledge: KnowledgeAnswer | null = null;
  try {
    knowledge = await opts.askKnowledge(query, opts.transcript, opts.onToken);
  } catch {
    return null;
  }

  if (
    !knowledge ||
    knowledge.mode === 'none' ||
    !knowledge.answer.trim() ||
    knowledge.confidence < needed
  ) {
    return null;
  }

  return {
    content: knowledge.answer,
    decision: 'answer',
    confidence: knowledge.confidence,
    intentId: topic.intentId ?? `kb_${knowledge.citations[0]?.docId ?? 'unknown'}`,
    intentLabel: topic.intentLabel ?? knowledge.citations[0]?.title ?? 'HR knowledge base',
    citations: knowledge.citations,
    aiMode: knowledge.mode,
    aiModel: knowledge.model,
    collectFeedback: true,
    corrections,
    offerEscalation: true,
  };
}

/**
 * Nothing in the corpus cleared the floor.
 *
 * Distinguished from a breakage for HR's own reporting — a coverage gap is worth filling and a
 * broken assistant is worth fixing — but the employee is told neither. They get "I can't answer
 * this reliably, here is a human", which is all that is actionable from their side.
 */
function noCoverageTurn(confidence: number, corrections: BotTurn['corrections']): BotTurn {
  return {
    content:
      // Says it cannot answer without explaining where answers come from. The previous wording
      // ("I couldn't find that in the HR documents I've been given") described the machine's
      // internals to someone who only wants to know when they get paid — and it framed a gap in
      // the corpus as a limitation the employee has to understand. What they need is that this
      // needs a person and how to reach one.
      "I don't have a reliable answer to that, and I'd rather not guess — getting leave or pay wrong costs you real money.\n\nShall I raise a ticket with **HR Operations**? They typically respond within one business day. Reply **yes** and I'll create it with this conversation attached.",
    decision: 'escalate',
    confidence,
    offerEscalation: true,
    collectFeedback: false,
    corrections,
  };
}

/** The topic label attached to an answer, for digest grouping. */
function topicOf(intent: Intent | undefined): { intentId?: string; intentLabel?: string } {
  if (!intent || intent.sensitive) return {};
  return { intentId: intent.id, intentLabel: labelForIntent(intent) };
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

export async function respond(
  text: string,
  ctx: ConversationContext,
  opts: RespondOptions
): Promise<{ turn: BotTurn; ctx: ConversationContext }> {
  const raw = text.trim();
  const next: ConversationContext = { ...ctx, lastQuery: raw };

  // --- 1. Resolving a clarification we just asked -------------------------
  if (ctx.pendingClarify) {
    const chosen = matchClarifyOption(raw, ctx.pendingClarify);
    delete next.pendingClarify;

    if (chosen) {
      const intent = intentById(chosen, opts.faqs);
      if (intent) {
        next.lastIntentId = intent.id;

        // A sensitive choice routes, exactly as if it had been said outright.
        // `buildClarification` no longer offers these, so this should be
        // unreachable — but the cost of being wrong is a grievance answered by a
        // document search instead of reaching the HR Head, so it is checked here
        // too rather than trusted to stay unreachable.
        if (intent.sensitive) {
          const ticket = await raise(
            ctx.pendingQuery ?? raw,
            'Sensitive matter selected from a clarifying question',
            intent.route ?? 'hr_head',
            intent.tags ?? ['sensitive'],
            1,
            true,
            opts
          );
          if (!ticket) return { turn: escalationFailedTurn(true), ctx: next };
          return {
            turn: {
              content: intent.answer ?? '',
              decision: 'sensitive',
              confidence: 1,
              intentId: intent.id,
              intentLabel: labelForIntent(intent),
              card: { kind: 'ticket', ticket },
              sensitive: true,
              collectFeedback: false,
              corrections: [],
            },
            ctx: { ...next, lastIntentId: intent.id, offeredEscalation: false },
          };
        }

        // Picking an option narrows the search, it does not select a stored
        // answer. The topic's canonical phrasing is a cleaner retrieval query
        // than the employee's "the second one", and it is combined with what
        // they originally asked so specifics in that wording are not lost.
        //
        // Only when the reply really is a selection, though. `matchClarifyOption` accepts any
        // reply overlapping 40% of an option's label, so a whole new question lands here too:
        // after a car-lease question was misclassified as Leave and produced this menu, "tell me
        // about leave policy?" matched "Leave policy & entitlement" at 2 words of 3 — and the
        // query sent was "what is the leave policy what is the car lease entitlement value for
        // the employee at grade x7", one question stapled to an unrelated one. Retrieval served
        // that muddle and the employee got "I don't have a reliable answer" to a question the
        // corpus covers.
        //
        // A selection is short and is not itself a question; anything else is a new question and
        // is searched as asked. The old query is dropped rather than merged, because the
        // employee moving on is exactly what "they typed something else" means.
        const isSelection = !raw.includes('?') && raw.split(/\s+/).length <= 4;
        const refined = isSelection
          ? `${intent.utterances[0] ?? ''} ${ctx.pendingQuery ?? ''}`.trim()
          : raw;

        const action = await actionTurn(intent, 0.95, [], opts);
        if (action) return { turn: action, ctx: { ...next, offeredEscalation: false } };

        // Answered, so nothing is pending. This is the exact path that filed a ticket for
        // "okay": the employee picked an option from the menu, got a good answer, and the next
        // acknowledgement was read as consent to an offer that was never made.
        const grounded = await knowledgeTurn(refined, opts, topicOf(intent), []);
        if (grounded) return { turn: grounded, ctx: { ...next, offeredEscalation: false } };

        return {
          turn: noCoverageTurn(0.95, []),
          ctx: { ...next, offeredEscalation: true },
        };
      }
    }

    if (NEGATIVE.test(raw) || /none|neither|other/i.test(raw)) {
      const ticket = await raise(
        ctx.pendingQuery ?? raw,
        'Clarification offered but none of the options matched',
        ctx.pendingRoute ?? 'hr_ops',
        [...(ctx.pendingTags ?? []), 'clarify-rejected'],
        ctx.pendingConfidence ?? 0,
        false,
        opts
      );
      if (!ticket) return { turn: escalationFailedTurn(false), ctx: next };
      return { turn: ticketTurn(ticket, false), ctx: next };
    }
    // Anything else: fall through and treat it as a brand-new question, but
    // without asking a second clarifying question for the same thread.
  }

  // --- 2. Accepting or declining an escalation offer ----------------------
  if (ctx.offeredEscalation && AFFIRMATIVE.test(raw) && raw.length <= 24) {
    delete next.offeredEscalation;
    // The route and confidentiality come from whatever was offered. Hardcoding `hr_ops` here
    // would take a "yes" to the confidential offer made after a sensitive-topic answer and file
    // it in the general queue, in front of whoever picks up the next routine ticket.
    const wasSensitive = (ctx.pendingTags ?? []).includes('sensitive');
    const ticket = await raise(
      ctx.pendingQuery ?? ctx.lastQuery ?? raw,
      'Employee asked for a human after an automated answer',
      humanRoute(ctx.pendingRoute),
      ['employee-requested', ...(ctx.pendingTags ?? [])],
      ctx.pendingConfidence ?? 0,
      wasSensitive,
      opts
    );
    if (!ticket) return { turn: escalationFailedTurn(wasSensitive), ctx: next };
    return { turn: ticketTurn(ticket, wasSensitive), ctx: next };
  }

  if (ctx.offeredEscalation && NEGATIVE.test(raw)) {
    delete next.offeredEscalation;
    return {
      turn: {
        content: "No problem — I won't raise a ticket. Ask me anything else whenever you need to.",
        decision: 'ack',
        confidence: 1,
        collectFeedback: false,
        corrections: [],
      },
      ctx: next,
    };
  }

  // --- 3. Social niceties ------------------------------------------------
  if (GREETING.test(raw) && raw.split(/\s+/).length <= 3) {
    return {
      turn: {
        content: `Hello${opts.userName ? ` ${opts.userName.split(' ')[0]}` : ''}! I can help with leave, payroll, benefits, expenses, assets and policies — and I can pull your live Darwinbox records.\n\nWhat would you like to know?`,
        decision: 'ack',
        confidence: 1,
        collectFeedback: false,
        corrections: [],
      },
      ctx: next,
    };
  }

  if (THANKS.test(raw) && raw.split(/\s+/).length <= 4) {
    return {
      turn: {
        content: "Happy to help. If anything else comes up, just ask.",
        decision: 'ack',
        confidence: 1,
        collectFeedback: false,
        corrections: [],
      },
      ctx: next,
    };
  }

  // Two words at most, and not a question: "ok" is an acknowledgement, "ok but what about sick
  // leave?" is a question that happens to start with one.
  if (ACKNOWLEDGEMENT.test(raw) && raw.split(/\s+/).length <= 2 && !raw.endsWith('?')) {
    return {
      turn: {
        content: "Anything else I can help with?",
        decision: 'ack',
        confidence: 1,
        collectFeedback: false,
        corrections: [],
      },
      ctx: next,
    };
  }

  // --- 4. Understand ----------------------------------------------------
  let result = understand(raw, opts.faqs, { alreadyClarified: !!ctx.pendingClarify });

  // A short continuation inherits the previous topic rather than being treated
  // as a weak new question: "what about sick leave?" after a balance answer.
  if (
    result.decision === 'escalate' &&
    ctx.lastIntentId &&
    FOLLOWUP.test(raw) &&
    raw.split(/\s+/).length <= 6
  ) {
    const merged = understand(`${ctx.lastQuery ?? ''} ${raw}`, opts.faqs, {
      alreadyClarified: true,
    });
    if (merged.decision === 'answer') result = merged;
  }

  next.pendingQuery = raw;
  // A soft sensitive topic overrides the ordinary route, so that if the employee takes up the
  // offer below, the ticket goes to the HR Head confidentially and not the general queue.
  next.pendingRoute = result.sensitiveTopic?.route ?? result.route;
  next.pendingTags = result.sensitiveTopic?.tags ?? result.tags;
  next.pendingConfidence = result.confidence;

  // --- 5. Sensitive: route immediately, never queue ----------------------
  if (result.decision === 'sensitive' && result.intent) {
    const ticket = await raise(
      raw,
      'Sensitive matter detected — routed directly to HRBP / HR Head',
      result.route,
      result.tags,
      result.confidence,
      true,
      opts
    );
    // The intent's own answer — who to contact, what the process is — is the part
    // that actually helps someone reporting harassment, so it is shown whether or
    // not the ticket could be filed. Only the ticket card is conditional.
    return {
      turn: {
        content:
          (result.intent.answer ?? '') +
          (ticket
            ? ''
            : "\n\n---\n\n**I could not file a ticket for this** — the ticket system isn't responding. Please contact the **HR Ops Team** or your **HRBP** directly so this is on record."),
        decision: 'sensitive',
        confidence: 1,
        intentId: result.intent.id,
        intentLabel: labelForIntent(result.intent),
        card: ticket ? { kind: 'ticket', ticket } : undefined,
        sensitive: true,
        collectFeedback: false,
        corrections: result.corrections,
      },
      ctx: { ...next, lastIntentId: result.intent.id, offeredEscalation: false },
    };
  }

  // --- 5a-bis. An outright request for a ticket or a person ---------------
  //
  // Placed AFTER the sensitive branch, not before it. Sitting earlier, "i want to file a
  // complaint under the posh policy" matched "file a complaint" and was filed as an ordinary
  // hr_ops ticket — the harassment routing never ran, so a POSH complaint went to the general
  // queue instead of the HR Head. Sensitive classification wins; this catches what is left.
  //
  // Still before retrieval, so the request is never scored as a policy question. `pendingRoute`
  // carries a sensitive route forward: asked for a ticket one turn after reporting misconduct,
  // this must not downgrade it.
  if ((ESCALATION_REQUEST.test(raw) || WANTS_A_HUMAN.test(raw)) && !PROCESS_QUESTION.test(raw)) {
    delete next.offeredEscalation;
    const wasSensitive = (ctx.pendingTags ?? []).includes('sensitive');
    const ticket = await raise(
      // What they want a ticket about is what they said before, not the words "raise a ticket".
      ctx.pendingQuery ?? ctx.lastQuery ?? raw,
      'Employee explicitly asked for a ticket or a human',
      humanRoute(ctx.pendingRoute),
      ['employee-requested', ...(ctx.pendingTags ?? [])],
      ctx.pendingConfidence ?? 0,
      wasSensitive,
      opts
    );
    if (!ticket) return { turn: escalationFailedTurn(wasSensitive), ctx: next };
    return { turn: ticketTurn(ticket, wasSensitive), ctx: next };
  }

  // --- 5b. The employee's own records ------------------------------------
  //
  // Before retrieval, because no document can hold someone's leave balance. The
  // matcher's job here is narrow: recognise that this is a records question and
  // which record. If the HRMS has nothing, the question carries on to the corpus.
  if (result.intent) {
    const action = await actionTurn(result.intent, result.confidence, result.corrections, opts);
    if (action) {
      next.lastIntentId = result.intent.id;
      // A records answer asks nothing. The employee can still escalate from the button.
      next.offeredEscalation = false;
      return { turn: action, ctx: next };
    }
  }

  // --- 6. Everything else is answered from HR's indexed documents --------
  //
  // Every informational question, whatever the matcher scored it. The matcher no
  // longer answers anything — it classifies the topic (for the digest), fixes
  // spelling, and spots the two cases above. There are no stored answers behind
  // this: what the corpus supports is what the employee gets.
  //
  // One floor for every question, ambiguous or not.
  //
  // This used to raise the bar to STRONG_RELEVANCE whenever the matcher was unsure, on the
  // reasoning that "a clarifying question beats a marginal passage". That reasoning inverted
  // itself on exactly the questions worth answering well. A question spanning several topics —
  // "how does a 6-month career break affect my gratuity, leave accrual, PF and insurance" —
  // is ambiguous *because* it spans them: the matcher splits its score four ways and lands at
  // 0.47. Raising the floor then made retrieval fail, and the employee got a menu reading
  // "My leave balance / Leave policy / Health insurance" — a request to throw away three
  // quarters of their question.
  //
  // The model handles this case directly and is instructed to: rule 7 of the grounding prompt
  // says use every extract that bears on the question and give each subject its own line, and
  // rule 9 says name plainly what is not covered. It never got the chance.
  const needed = RELEVANCE_THRESHOLD;
  const grounded = await knowledgeTurn(raw, opts, topicOf(result.intent), result.corrections, needed);

  if (grounded) {
    if (result.intent) next.lastIntentId = result.intent.id;

    // A sensitive subject answered as a question: give them the policy, then name the
    // confidential route without taking it for them. This is the whole point of the soft tier —
    // guessing wrong here costs one sentence, where guessing wrong the other way sent an
    // employee "I'm sorry you're dealing with this" and put a false incident on the HR Head's
    // queue. `offeredEscalation` is set because this turn genuinely asks a question.
    if (result.sensitiveTopic) {
      return {
        turn: {
          ...grounded,
          content:
            grounded.content
            + "\n\n---\n\nIf this is about something that happened to you or someone else, I can"
            + " put you in touch with the **HR Head** confidentially instead — it stays out of the"
            + " general HR queue and away from your manager. Just say **yes** and I'll arrange it.",
          offerEscalation: true,
        },
        ctx: { ...next, offeredEscalation: true },
      };
    }

    // Cleared, not merely left alone: an offer made two turns ago is stale once the question has
    // been answered, so a later "ok" cannot reach back and accept it.
    return { turn: grounded, ctx: { ...next, offeredEscalation: false } };
  }

  // --- 7. (removed) the intent-menu clarifying question -------------------
  //
  // A turn used to be emitted here offering the matcher's top few intent labels as chips —
  // "which of these did you mean? My leave balance / Leave policy & entitlement / Health
  // insurance & benefits". It is gone on purpose.
  //
  // It asked the wrong question. Those labels are the matcher's internal topic names, not
  // anything the employee said, and the menu appeared precisely when their question was rich
  // enough to touch several of them — so the one thing it reliably did was invite someone to
  // discard most of what they had asked. A canned list of guesses is also not what "I don't
  // understand" should sound like coming from an assistant.
  //
  // What happens instead: an unsupported question falls through to the handover below, which
  // says plainly that the documents do not cover it. Where the documents *do* support part of
  // it, step 6 above already answered — the model is told to cover each part it can and name
  // what it cannot.
  //
  // `understand()` still reports decision 'clarify' and still tags the turn, because ambiguity
  // is real signal worth having in the weekly digest. It just no longer decides the reply. The
  // renderer in app/chat/page.tsx and the pendingClarify branch above are now unreachable;
  // they are the machinery to restore if this is ever wanted back.

  // --- 8. Not in the corpus: hand over to a human ------------------------
  //
  // A sensitive subject we cannot answer gets the confidential desk named rather than HR Ops —
  // but still only offered. Filing unasked is what produced SEN tickets for people who wanted
  // to read the POSH policy.
  if (result.sensitiveTopic) {
    return {
      turn: {
        content:
          "I don't have that policy in the documents I've been given, and I'd rather not"
          + " paraphrase this one.\n\nIf this is about something that happened to you or someone"
          + " else, I can put you in touch with the **HR Head** confidentially — it stays out of"
          + " the general HR queue and away from your manager. Say **yes** and I'll arrange it."
          + " Otherwise, **posh@leadsquared.com** will have the document itself.",
        decision: 'escalate',
        confidence: result.confidence,
        offerEscalation: true,
        collectFeedback: false,
        corrections: result.corrections,
      },
      ctx: { ...next, offeredEscalation: true },
    };
  }

  return {
    turn: noCoverageTurn(result.confidence, result.corrections),
    ctx: { ...next, offeredEscalation: true },
  };
}

function ticketTurn(ticket: Ticket, sensitive: boolean): BotTurn {
  return {
    content:
      `Done — I've raised **${ticket.id}** and assigned it to **${ticket.assignee}**.\n\n` +
      `Your original question and the last few messages are attached, so you won't need to repeat yourself. ` +
      (sensitive
        ? 'This is flagged confidential and is not visible to your reporting manager.'
        : "You'll get an email as soon as someone picks it up — typically within one business day."),
    decision: 'escalate',
    confidence: 1,
    card: { kind: 'ticket', ticket },
    collectFeedback: false,
    corrections: [],
  };
}

/** Maps a free-text reply onto one of the clarification options. */
function matchClarifyOption(text: string, clarify: NonNullable<NluResult['clarify']>): string | null {
  const t = text.toLowerCase().trim();

  // "1", "2", "option 2"
  const num = t.match(/(?:option\s*)?([1-9])\b/);
  if (num) {
    const idx = parseInt(num[1], 10) - 1;
    if (clarify.options[idx]) return clarify.options[idx].intentId;
  }

  const ordinals = ['first', 'second', 'third'];
  for (let i = 0; i < ordinals.length; i++) {
    if (t.includes(ordinals[i]) && clarify.options[i]) return clarify.options[i].intentId;
  }

  // Best word overlap with an option label.
  let best: { id: string; score: number } | null = null;
  for (const opt of clarify.options) {
    const words = opt.label.toLowerCase().split(/[^a-z]+/).filter((w) => w.length > 2);
    if (!words.length) continue;
    const hits = words.filter((w) => t.includes(w)).length;
    const score = hits / words.length;
    if (score > 0 && (!best || score > best.score)) best = { id: opt.intentId, score };
  }
  return best && best.score >= 0.4 ? best.id : null;
}
