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
import {
  fetchLeaveBalances,
  fetchLatestPayslip,
  fetchRequests,
  formatCurrency,
  totalLeaveAvailable,
  type LeaveBalance,
  type PayslipSummary,
  type TrackedRequest,
} from './darwinbox';
import { createTicket, type Ticket } from './hr-store';
import { RELEVANCE_THRESHOLD, STRONG_RELEVANCE } from './knowledge/types';
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
  offeredEscalation?: boolean;
  lastIntentId?: string;
  lastQuery?: string;
}

export interface RespondOptions {
  faqs: FAQ[];
  email?: string;
  userName?: string;
  channel: string;
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
const GREETING = /^(hi|hii|hello|hey|good morning|good afternoon|good evening|namaste)\b/i;

/** Words that signal a query is a continuation rather than a new topic. */
const FOLLOWUP = /\b(what about|how about|and|also|mine|that|it|instead|too)\b/i;

// ---------------------------------------------------------------------------
// Darwinbox-backed answers
// ---------------------------------------------------------------------------

async function runAction(
  action: IntentAction,
  email: string | undefined
): Promise<{ content: string; card?: CardPayload } | null> {
  // Live data is per-employee, so it needs an authenticated session.
  if (!email) {
    return {
      content:
        "I can pull that straight from Darwinbox, but I need you to be signed in first so I only ever show you your own records.\n\nPlease sign in and ask me again.",
    };
  }

  switch (action) {
    case 'leave_balance': {
      const balances = await fetchLeaveBalances(email);
      const total = totalLeaveAvailable(balances);
      const pending = balances.reduce((s, b) => s + b.pending, 0);
      const lines = balances
        .map((b) => `• **${b.type}** — ${b.available} available (of ${b.entitled} entitled)`)
        .join('\n');
      return {
        content:
          `Here's your live leave balance from Darwinbox — **${total} days available** in total.\n\n${lines}` +
          (pending
            ? `\n\n${pending} day(s) are attached to requests still awaiting approval, so they're not yet deducted.`
            : ''),
        card: { kind: 'leave_balance', balances },
      };
    }

    case 'payslip_summary': {
      const payslip = await fetchLatestPayslip(email);
      return {
        content:
          `Your most recent payslip is for **${payslip.month}**, credited on ${new Date(
            payslip.paidOn
          ).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}.\n\n` +
          `• **Gross:** ${formatCurrency(payslip.gross)}\n` +
          `• **Net paid:** ${formatCurrency(payslip.net)}\n` +
          `• **Tax deducted (TDS):** ${formatCurrency(
            payslip.deductions.find((d) => d.label.includes('Tax'))?.amount ?? 0
          )}\n\n` +
          `Financial year to date: ${formatCurrency(payslip.ytdGross)} gross, ${formatCurrency(
            payslip.ytdTax
          )} tax. The full breakdown is below, and the PDF is in **Darwinbox → Payroll → Payslips**.`,
        card: { kind: 'payslip', payslip },
      };
    }

    case 'leave_status':
    case 'expense_status':
    case 'asset_status': {
      const kind =
        action === 'leave_status' ? 'leave' : action === 'expense_status' ? 'expense' : 'asset';
      const requests = await fetchRequests(email, kind);
      const heading =
        kind === 'leave' ? 'Your leave requests' : kind === 'expense' ? 'Your expense claims' : 'Your asset requests';

      if (!requests.length) {
        return { content: `You don't have any ${kind} requests on record right now.` };
      }

      const open = requests.filter((r) => r.status === 'pending' || r.status === 'in-progress');
      const summary = open.length
        ? `You have **${open.length} ${kind} request${open.length > 1 ? 's' : ''} still in progress**:\n\n` +
          open.map((r) => `• **${r.id}** — ${r.title}. ${r.lastUpdate}`).join('\n')
        : `All your ${kind} requests are closed out. The most recent was **${requests[0].id}** — ${requests[0].lastUpdate}.`;

      return { content: summary, card: { kind: 'requests', requests, heading } };
    }

    default:
      return null;
  }
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
      channel: opts.channel,
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
      ? "I couldn't file this for you — the ticket system isn't responding.\n\nPlease contact **Priya Nair (HR Head)** or **Meera Iyer (HRBP)** directly. This matters too much to leave sitting in a form that failed."
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
 * Says which is which — "not in the documents" is a different problem for HR
 * than "the assistant is broken", and it tells them the corpus has a gap worth
 * filling.
 */
function noCoverageTurn(confidence: number, corrections: BotTurn['corrections']): BotTurn {
  return {
    content:
      "I couldn't find that in the HR documents I've been given, and I won't guess at policy — a wrong answer about leave or pay costs you real money.\n\nShall I raise a ticket with **HR Operations**? They typically respond within one business day. Reply **yes** and I'll create it with this conversation attached.",
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
        const refined = `${intent.utterances[0] ?? ''} ${ctx.pendingQuery ?? ''}`.trim();

        const action = await actionTurn(intent, 0.95, [], opts);
        if (action) return { turn: action, ctx: next };

        const grounded = await knowledgeTurn(refined, opts, topicOf(intent), []);
        if (grounded) return { turn: grounded, ctx: { ...next, offeredEscalation: true } };

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
    const ticket = await raise(
      ctx.pendingQuery ?? ctx.lastQuery ?? raw,
      'Employee asked for a human after an automated answer',
      'hr_ops',
      ['employee-requested', ...(ctx.pendingTags ?? [])],
      ctx.pendingConfidence ?? 0,
      false,
      opts
    );
    if (!ticket) return { turn: escalationFailedTurn(false), ctx: next };
    return { turn: ticketTurn(ticket, false), ctx: next };
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
  next.pendingRoute = result.route;
  next.pendingTags = result.tags;
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
            : "\n\n---\n\n**I could not file a ticket for this** — the ticket system isn't responding. Please contact **Priya Nair (HR Head)** or **Meera Iyer (HRBP)** directly so this is on record."),
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

  // --- 5b. The employee's own records ------------------------------------
  //
  // Before retrieval, because no document can hold someone's leave balance. The
  // matcher's job here is narrow: recognise that this is a records question and
  // which record. If the HRMS has nothing, the question carries on to the corpus.
  if (result.intent) {
    const action = await actionTurn(result.intent, result.confidence, result.corrections, opts);
    if (action) {
      next.lastIntentId = result.intent.id;
      next.offeredEscalation = true;
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
  // On an ambiguous query a clarifying question beats a marginal passage, so the
  // floor is raised there; documents still pre-empt the question on a strong hit.
  const needed = result.decision === 'clarify' ? STRONG_RELEVANCE : RELEVANCE_THRESHOLD;
  const grounded = await knowledgeTurn(raw, opts, topicOf(result.intent), result.corrections, needed);

  if (grounded) {
    if (result.intent) next.lastIntentId = result.intent.id;
    return { turn: grounded, ctx: { ...next, offeredEscalation: true } };
  }

  // --- 7. Ambiguous and unsupported: exactly one clarifying question ------
  if (result.decision === 'clarify' && result.clarify) {
    return {
      turn: {
        content: result.clarify.question,
        decision: 'clarify',
        confidence: result.confidence,
        clarify: result.clarify,
        collectFeedback: false,
        corrections: result.corrections,
      },
      ctx: { ...next, pendingClarify: result.clarify },
    };
  }

  // --- 8. Not in the corpus: hand over to a human ------------------------
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
