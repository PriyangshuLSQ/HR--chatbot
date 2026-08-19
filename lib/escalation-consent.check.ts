/**
 * Escalation-consent harness. Run with:  pnpm test:escalation
 *
 * Asserts the property a ticket write depends on: a ticket is only ever filed in reply to a
 * question the bot actually asked. `ctx.offeredEscalation` is what licenses a bare "okay" to
 * create one, and it used to be set after *successful* answers too — so a real conversation went
 *
 *     "How to apply for leave"  -> a good, cited answer
 *     "okay"                    -> HR-5YC8F1 raised against HR Ops
 *
 * The employee acknowledged an answer and got a ticket. These checks pin both halves: an answer
 * must leave nothing pending, and a genuine offer must still be acceptable.
 */

import { respond, type ConversationContext } from './conversation';
import { DEFAULT_FAQS } from './chatbot-data';
import type { KnowledgeAnswer } from './knowledge/types';

// The chat window's ticket writes go over HTTP; this harness has no server. Stubbed rather than
// left to fail, so "a ticket was filed" is observable instead of collapsing into the same
// failure turn as "the ticket store is down".
const originalFetch = globalThis.fetch;
let ticketsFiled: string[] = [];
/** Parallel to `ticketsFiled`: where each one was routed, which is half of what matters. */
let ticketRoutes: string[] = [];

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input);
  if (url.includes('/api/tickets')) {
    const body = JSON.parse(String(init?.body ?? '{}'));
    ticketsFiled.push(String(body.query ?? ''));
    ticketRoutes.push(String(body.route ?? '?'));
    return new Response(
      JSON.stringify({
        ...body,
        id: body.id ?? 'HR-STUB',
        status: 'open',
        assignee: 'HR Ops Queue',
        priority: 'normal',
        route: 'hr_ops',
        confidential: false,
        createdAt: '2026-08-12T00:00:00.000Z',
        tags: body.tags ?? [],
        transcript: body.transcript ?? [],
      }),
      { status: 201, headers: { 'Content-Type': 'application/json' } }
    );
  }
  return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
}) as typeof fetch;

const HIT: KnowledgeAnswer = {
  mode: 'generated',
  answer: 'Use Darwinbox under Leave -> Apply Leave, at least three working days ahead.',
  citations: [
    { docId: 'd1', title: 'Leave policy', filename: 'leave-policy.md', headings: [], snippet: 's', score: 0.9 },
  ],
  confidence: 0.9,
  model: 'claude-haiku-4-5',
};

const MISS: KnowledgeAnswer = { mode: 'none', answer: '', citations: [], confidence: 0.1 };

function opts(knowledge: KnowledgeAnswer | null) {
  ticketsFiled = [];
  ticketRoutes = [];
  return {
    faqs: DEFAULT_FAQS,
    email: 'employee@company.com',
    userName: 'Ananya Sharma',
    transcript: [] as { role: 'user' | 'bot'; text: string }[],
    askKnowledge: knowledge ? async () => knowledge : undefined,
  };
}

interface Check {
  name: string;
  run: () => Promise<{ ok: boolean; detail: string }>;
}

const CHECKS: Check[] = [
  {
    name: 'the reported bug: answer then "okay" files no ticket',
    run: async () => {
      const first = await respond('how to apply for leave', {}, opts(HIT));
      const second = await respond('okay', first.ctx, opts(HIT));
      const ok = ticketsFiled.length === 0 && second.turn.decision === 'ack';
      return {
        ok,
        detail: `after-answer offeredEscalation=${first.ctx.offeredEscalation} `
          + `then decision=${second.turn.decision} tickets=${ticketsFiled.length}`,
      };
    },
  },
  {
    name: 'a successful answer leaves no escalation pending',
    run: async () => {
      const { ctx } = await respond('how to apply for leave', {}, opts(HIT));
      return {
        ok: ctx.offeredEscalation === false,
        detail: `offeredEscalation=${ctx.offeredEscalation}`,
      };
    },
  },
  {
    name: 'the clarify-then-answer path leaves no escalation pending',
    run: async () => {
      // The path from the transcript: a menu was offered, an option was tapped, the answer
      // landed. This is where the flag was set, one turn before "okay".
      const menu = await respond('what if i take more than 10 days', {}, opts(MISS));
      if (!menu.turn.clarify) {
        return { ok: true, detail: 'no clarification offered for this query; nothing to assert' };
      }
      const picked = await respond(menu.turn.clarify.options[0].label, menu.ctx, opts(HIT));
      const after = await respond('okay', picked.ctx, opts(HIT));
      return {
        ok: ticketsFiled.length === 0 && after.turn.decision === 'ack',
        detail: `picked=${picked.turn.decision} offeredEscalation=${picked.ctx.offeredEscalation} `
          + `then=${after.turn.decision} tickets=${ticketsFiled.length}`,
      };
    },
  },
  {
    name: 'a real offer is still accepted by "yes"',
    run: async () => {
      const offer = await respond('what is the pet insurance allowance', {}, opts(MISS));
      if (offer.ctx.offeredEscalation !== true) {
        return { ok: false, detail: `expected an offer, got offeredEscalation=${offer.ctx.offeredEscalation}` };
      }
      const accepted = await respond('yes', offer.ctx, opts(MISS));
      return {
        ok: ticketsFiled.length === 1 && !!accepted.turn.card,
        detail: `tickets=${ticketsFiled.length} card=${accepted.turn.card?.kind ?? 'none'}`,
      };
    },
  },
  {
    name: 'a real offer is still accepted by "okay"',
    run: async () => {
      // "okay" must keep working where it genuinely means yes — the offer says "reply yes",
      // but nobody types only what they are told to.
      const offer = await respond('what is the pet insurance allowance', {}, opts(MISS));
      const accepted = await respond('okay', offer.ctx, opts(MISS));
      return {
        ok: ticketsFiled.length === 1,
        detail: `tickets=${ticketsFiled.length} decision=${accepted.turn.decision}`,
      };
    },
  },
  {
    name: 'declining an offer files nothing',
    run: async () => {
      const offer = await respond('what is the pet insurance allowance', {}, opts(MISS));
      const declined = await respond('no thanks', offer.ctx, opts(MISS));
      return {
        ok: ticketsFiled.length === 0 && declined.turn.decision === 'ack',
        detail: `tickets=${ticketsFiled.length} decision=${declined.turn.decision}`,
      };
    },
  },
  {
    name: 'an offer goes stale once an intervening question is answered',
    run: async () => {
      // Offered, then the employee asks something the corpus does cover. The offer is spent:
      // "ok" afterwards is about the answer they just read, not the ticket from two turns ago.
      const offer = await respond('what is the pet insurance allowance', {}, opts(MISS));
      const answered = await respond('how to apply for leave', offer.ctx, opts(HIT));
      const acked = await respond('ok', answered.ctx, opts(HIT));
      return {
        ok: ticketsFiled.length === 0 && acked.turn.decision === 'ack',
        detail: `offeredEscalation=${answered.ctx.offeredEscalation} then=${acked.turn.decision} `
          + `tickets=${ticketsFiled.length}`,
      };
    },
  },
  {
    name: 'a misconduct report is routed to a human, never answered from documents',
    run: async () => {
      // Reported as answered policy: "some one is cheating in office" came back as a 45% match on
      // the work-from-home policy, citing meeting rooms and gratuity forfeiture, and filed
      // nothing. A report that disappears is the worst outcome available here.
      const reports = [
        'some one is cheating in office',
        'someone is stealing from the company',
        'my colleague is faking his timesheet',
        'there is fraud happening in my team',
        'someone is leaking client data',
        'a teammate is falsifying expense claims',
      ];
      const missed: string[] = [];
      for (const r of reports) {
        const o = opts(HIT);
        const { turn } = await respond(r, {}, o);
        if (turn.decision !== 'sensitive' || ticketsFiled.length !== 1) {
          missed.push(`${r} -> ${turn.decision}/${ticketsFiled.length}`);
        }
      }
      return {
        ok: missed.length === 0,
        detail: missed.length ? `NOT ROUTED: ${missed.join(' | ')}` : `${reports.length}/${reports.length} routed and filed`,
      };
    },
  },
  {
    name: 'a policy question about misconduct is still answered from the corpus',
    run: async () => {
      // The vocabulary is present but nobody is being accused, so these must stay policy
      // questions. Phrasings that trip the older harassment/grievance detectors ("how do i file
      // a complaint", "...terminated for misconduct") are deliberately not asserted here: those
      // over-trigger by design, and tightening them would route a real report to a document
      // search. This check covers the misconduct rule added alongside it.
      const policy = [
        'what does the policy say about fraud',
        'is there a whistleblower policy',
        'what is the conflict of interest declaration process',
      ];
      const wrong: string[] = [];
      for (const q of policy) {
        const o = opts(HIT);
        const { turn } = await respond(q, {}, o);
        if (turn.decision === 'sensitive' || ticketsFiled.length > 0) {
          wrong.push(`${q} -> ${turn.decision}/${ticketsFiled.length}`);
        }
      }
      return {
        ok: wrong.length === 0,
        detail: wrong.length ? `OVER-TRIGGERED: ${wrong.join(' | ')}` : `${policy.length}/${policy.length} answered`,
      };
    },
  },
  {
    name: 'reading the POSH policy is a policy question, not an incident report',
    run: async () => {
      // Reported: "tell me about posh policy?" replied "I'm sorry you're dealing with this",
      // routed to the HR Head as a confidential priority matter, and never showed the policy.
      // POSH is the document's name; asking for it is required reading, not a disclosure.
      // Wide on purpose. The first fix here passed "tell me about posh policy?" and still failed
      // "just tell me about posh policy" — one throwaway word ahead of the anchored cue. A short
      // list of tidy phrasings is what let that through, so this covers fillers, politeness
      // wrappers and the first-person informational openers.
      const lookups = [
        'just tell me about posh policy',
        'tell me about posh policy?',
        'just tell me about the posh policy please',
        'so what is the posh policy',
        'hey what is the posh policy',
        'can you tell me about the posh policy',
        'could you please explain the posh policy',
        'what is the posh policy',
        'what is posh policy',
        'explain the posh policy',
        'describe the harassment policy',
        'where can i find the harassment policy',
        'where is the posh policy document',
        'what is the definition of sexual harassment under posh',
        'is there posh training for new joiners',
        'is there a posh training module',
        'who is on the posh committee',
        'explain the anti-discrimination policy',
        'i want to know about the posh policy',
        'i would like to know the posh policy',
        'i am looking for the posh policy',
        'give me the posh policy',
        'share the posh guidelines',
        'details of the posh policy',
        'summarise the posh policy',
        'what does the posh policy say',
        'okay tell me about the harassment policy',
        'what are the rules on discrimination',
        // No document noun at all. This is what defeated the previous rule, which required one.
        'what is posh?',
        'what is posh',
        'posh',
        'what does posh mean',
        'what is sexual harassment',
        'tell me about discrimination',
      ];
      const wrong: string[] = [];
      for (const q of lookups) {
        const o = opts(HIT);
        const { turn } = await respond(q, {}, o);
        // Answered, nothing filed, and the confidential route named rather than taken.
        const offered = /HR Head/.test(turn.content) && turn.offerEscalation === true;
        if (turn.decision === 'sensitive' || ticketsFiled.length > 0 || !offered) {
          wrong.push(`${q} -> ${turn.decision}/${ticketsFiled.length}/offered=${offered}`);
        }
      }
      return {
        ok: wrong.length === 0,
        detail: wrong.length ? `WRONG: ${wrong.join(' | ')}` : `${lookups.length}/${lookups.length} answered with the route offered`,
      };
    },
  },
  {
    name: 'a real disclosure still routes, however the policy is named in it',
    run: async () => {
      // The other side of the exemption, and the side that must never break: if any of these
      // start being answered from documents, the exemption is too wide.
      const reports = [
        'i want to report harassment',
        'i am being harassed at work',
        'my manager is harassing me',
        'someone touched me inappropriately',
        'i feel unsafe at the office',
        'i faced discrimination during the review',
        'i want to file a complaint under the posh policy',
        'my lead keeps making inappropriate comments about me',
        // The dangerous shape: a disclosure that also asks for the policy. The anchor on the
        // asking cue is what keeps these on the routing side, so they are pinned here.
        'he harassed me, what does the policy say',
        'what is the policy, my manager harassed me',
        'i was harassed last week, tell me the policy',
        'a colleague is bullying me',
        'i am facing discrimination because of my gender',
        'i want to report sexual harassment under posh',
        'someone is retaliating against me for complaining',
        'i need to report my manager for harassment',
      ];
      const missed: string[] = [];
      for (const q of reports) {
        const o = opts(HIT);
        const { turn } = await respond(q, {}, o);
        if (turn.decision !== 'sensitive' || ticketsFiled.length !== 1) {
          missed.push(`${q} -> ${turn.decision}/${ticketsFiled.length}`);
        }
      }
      return {
        ok: missed.length === 0,
        detail: missed.length ? `NOT ROUTED: ${missed.join(' | ')}` : `${reports.length}/${reports.length} routed and filed`,
      };
    },
  },
  {
    name: 'accepting the offer after a sensitive-topic answer files confidentially to the HR Head',
    run: async () => {
      // The offer is only worth making if saying yes reaches the right desk. The accept branch
      // used to hardcode hr_ops, which would have put a POSH matter in the general queue.
      const answered = await respond('what is posh?', {}, opts(HIT));
      const o = opts(HIT);
      const accepted = await respond('yes', answered.ctx, o);
      return {
        ok: ticketsFiled.length === 1 && ticketRoutes[0] === 'hr_head' && !!accepted.turn.card,
        detail: `offered=${answered.ctx.offeredEscalation} route=${ticketRoutes.join(',')} `
          + `card=${accepted.turn.card?.kind ?? 'none'}`,
      };
    },
  },
  {
    name: 'declining the offer after a sensitive-topic answer files nothing',
    run: async () => {
      const answered = await respond('what is posh?', {}, opts(HIT));
      const o = opts(HIT);
      const declined = await respond('no thanks', answered.ctx, o);
      return {
        ok: ticketsFiled.length === 0 && declined.turn.decision === 'ack',
        detail: `tickets=${ticketsFiled.length} decision=${declined.turn.decision}`,
      };
    },
  },
  {
    name: 'a sensitive topic with no policy in the corpus offers the route but files nothing',
    run: async () => {
      const o = opts(MISS);
      const { turn, ctx } = await respond('what is posh?', {}, o);
      return {
        ok: ticketsFiled.length === 0 && /HR Head/.test(turn.content) && ctx.offeredEscalation === true,
        detail: `tickets=${ticketsFiled.length} decision=${turn.decision} offered=${ctx.offeredEscalation}`,
      };
    },
  },
  {
    name: 'an explicit request for a ticket files one, with no offer pending',
    run: async () => {
      // The reported case: this arrived after an answer, matched no intent strongly, and was met
      // with a menu of leave and expense topics.
      const asks = [
        'can u raise a ticket about this very serious',
        'please raise a ticket',
        'raise a ticket for this',
        'i want to speak to someone in HR',
        'escalate this to HR',
      ];
      const ignored: string[] = [];
      for (const a of asks) {
        const first = await respond('how to apply for leave', {}, opts(HIT));
        const o = opts(HIT);
        const { turn } = await respond(a, first.ctx, o);
        if (ticketsFiled.length !== 1 || turn.clarify) {
          ignored.push(`${a} -> ${turn.decision}/${ticketsFiled.length}`);
        }
      }
      return {
        ok: ignored.length === 0,
        detail: ignored.length ? `IGNORED: ${ignored.join(' | ')}` : `${asks.length}/${asks.length} filed`,
      };
    },
  },
  {
    name: 'asking how the ticket process works does not file one',
    run: async () => {
      const asks = ['how do i raise a ticket', 'how to escalate an expense claim', 'where do i log an IT issue'];
      const wrong: string[] = [];
      for (const a of asks) {
        const o = opts(HIT);
        await respond(a, {}, o);
        if (ticketsFiled.length > 0) wrong.push(a);
      }
      return {
        ok: wrong.length === 0,
        detail: wrong.length ? `FILED FOR: ${wrong.join(' | ')}` : `${asks.length}/${asks.length} answered, nothing filed`,
      };
    },
  },
  {
    name: 'asking for a ticket after a misconduct report keeps the confidential route',
    run: async () => {
      const report = await respond('some one is cheating in office', {}, opts(HIT));
      const o = opts(HIT);
      const follow = await respond('can u raise a ticket about this very serious', report.ctx, o);
      // Downgrading this to the general HR queue would put a misconduct report in front of
      // whoever picks up the next routine ticket.
      return {
        ok: ticketsFiled.length === 1 && ticketRoutes[0] === 'hr_head' && !!follow.turn.card,
        detail: `route=${ticketRoutes.join(',')} subject=${JSON.stringify(ticketsFiled[0] ?? '')} `
          + `decision=${follow.turn.decision}`,
      };
    },
  },
  {
    name: 'a question that merely opens with "ok" is still answered',
    run: async () => {
      const { turn } = await respond('ok but what about sick leave?', {}, opts(HIT));
      return {
        ok: turn.decision !== 'ack',
        detail: `decision=${turn.decision}`,
      };
    },
  },
  {
    name: 'a bare acknowledgement is never sent to retrieval as a question',
    run: async () => {
      // Left to fall through, "okay" retrieves nothing and comes back as "I don't have a
      // reliable answer to that — shall I raise a ticket?", which invites the very reply that
      // files one. It must not reach the corpus at all.
      const words = ['ok', 'okay', 'sure', 'fine', 'alright', 'noted'];
      const wrong: string[] = [];
      for (const w of words) {
        const ctx: ConversationContext = {};
        const { turn } = await respond(w, ctx, opts(MISS));
        if (turn.decision !== 'ack') wrong.push(`${w} -> ${turn.decision}`);
      }
      return {
        ok: wrong.length === 0,
        detail: wrong.length ? `NOT acknowledged: ${wrong.join(' | ')}` : `${words.length}/${words.length} acknowledged`,
      };
    },
  },
];

async function main() {
  console.log('Escalation-consent report');
  console.log('=========================');
  let pass = 0;

  for (const check of CHECKS) {
    let ok = false;
    let detail = '';
    try {
      const r = await check.run();
      ok = r.ok;
      detail = r.detail;
    } catch (e) {
      detail = `threw: ${e instanceof Error ? e.message : String(e)}`;
    }
    if (ok) pass += 1;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${check.name}\n          ${detail}`);
  }

  console.log(`\n${pass}/${CHECKS.length} checks passed`);
  globalThis.fetch = originalFetch;
  if (pass !== CHECKS.length) process.exit(1);
}

void main();
