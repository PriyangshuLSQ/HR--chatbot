/**
 * Answer-precedence harness. Run with:  npm run test:precedence
 *
 * Asserts the one property the curated-answer removal has to guarantee: no reply
 * to a policy question ever comes from this repo. Retrieval is stubbed so each
 * case can force a hit or a miss, and a miss must escalate — if any case answers
 * with prose while `askKnowledge` returned nothing, a hand-written answer has
 * crept back into the path.
 */

import { respond } from './conversation';
import { DEFAULT_FAQS } from './chatbot-data';
import type { KnowledgeAnswer } from './knowledge/types';

// The chat window's ticket writes go over HTTP; this harness has no server.
const originalFetch = globalThis.fetch;
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input);
  if (url.includes('/api/tickets')) {
    const body = JSON.parse(String(init?.body ?? '{}'));
    return new Response(
      JSON.stringify({
        ...body,
        id: body.id ?? 'HR-STUB',
        status: 'open',
        assignee: body.sensitive ? 'Priya Nair (HR Head)' : 'HR Ops Queue',
        priority: body.sensitive ? 'critical' : 'normal',
        route: body.sensitive ? 'hr_head' : 'hr_ops',
        confidential: !!body.sensitive,
        createdAt: new Date().toISOString(),
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
  answer: 'STUBBED_DOCUMENT_ANSWER',
  citations: [
    { docId: 'd1', title: 'Leave policy', filename: 'leave-policy.md', headings: [], snippet: 's', score: 0.9 },
  ],
  confidence: 0.9,
  model: 'llama3.2',
};

const MISS: KnowledgeAnswer = {
  mode: 'none',
  answer: '',
  citations: [],
  confidence: 0.1,
};

let asked: string[] = [];

function opts(knowledge: KnowledgeAnswer | null) {
  asked = [];
  return {
    faqs: DEFAULT_FAQS,
    email: 'employee@company.com',
    userName: 'Ananya Sharma',
    channel: 'HR Portal',
    transcript: [] as { role: 'user' | 'bot'; text: string }[],
    askKnowledge: knowledge
      ? async (q: string) => {
          asked.push(q);
          return knowledge;
        }
      : undefined,
  };
}

interface Check {
  name: string;
  run: () => Promise<{ ok: boolean; detail: string }>;
}

const CHECKS: Check[] = [
  {
    name: 'policy question with a document hit is answered from the document, with citations',
    run: async () => {
      const { turn } = await respond('what is the notice period', {}, opts(HIT));
      const ok =
        turn.content === 'STUBBED_DOCUMENT_ANSWER' &&
        turn.decision === 'answer' &&
        (turn.citations?.length ?? 0) > 0;
      return { ok, detail: `decision=${turn.decision} citations=${turn.citations?.length ?? 0}` };
    },
  },
  {
    name: 'formerly-curated topic with NO document falls through to a human, never to stored prose',
    run: async () => {
      // Every one of these had a hand-written answer before. If any returns prose
      // here, that answer is still being served from source.
      const topics = [
        'what is the notice period',
        'how long is paternity leave',
        'where do i download form 16',
        'what is the leave policy',
        'when do appraisals happen',
        'how much is the referral bonus',
        'how do i submit an expense claim',
        'can i work from home',
      ];
      const leaked: string[] = [];
      for (const q of topics) {
        const { turn } = await respond(q, {}, opts(MISS));
        if (turn.decision !== 'escalate' && turn.decision !== 'clarify') {
          leaked.push(`${q} -> ${turn.decision}: ${turn.content.slice(0, 60)}`);
        }
      }
      return {
        ok: leaked.length === 0,
        detail: leaked.length ? `LEAKED: ${leaked.join(' | ')}` : `${topics.length}/${topics.length} escalated or clarified`,
      };
    },
  },
  {
    name: 'retrieval is actually consulted for a policy question',
    run: async () => {
      await respond('what is the leave carry forward rule', {}, opts(HIT));
      return { ok: asked.length === 1, detail: `askKnowledge called ${asked.length}x` };
    },
  },
  {
    name: 'sensitive matter is answered deterministically and never sent to retrieval',
    run: async () => {
      const { turn } = await respond('i am being harassed by my manager', {}, opts(HIT));
      const ok = turn.decision === 'sensitive' && asked.length === 0 && turn.sensitive === true;
      return { ok, detail: `decision=${turn.decision} askKnowledge=${asked.length}x card=${turn.card?.kind}` };
    },
  },
  {
    name: "employee's own records come from the HRMS, not from documents",
    run: async () => {
      const { turn } = await respond('what is my leave balance', {}, opts(HIT));
      const ok = turn.decision === 'action' && turn.card?.kind === 'leave_balance' && asked.length === 0;
      return { ok, detail: `decision=${turn.decision} card=${turn.card?.kind} askKnowledge=${asked.length}x` };
    },
  },
  {
    name: 'with no retrieval configured at all, a policy question still escalates rather than answering',
    run: async () => {
      const { turn } = await respond('what is the maternity leave policy', {}, opts(null));
      return { ok: turn.decision === 'escalate' || turn.decision === 'clarify', detail: `decision=${turn.decision}` };
    },
  },
  {
    name: 'a bare "yes" after an escalation offer files the ticket',
    run: async () => {
      const { turn } = await respond('yes', { offeredEscalation: true, pendingQuery: 'something obscure' }, opts(MISS));
      return { ok: turn.card?.kind === 'ticket', detail: `card=${turn.card?.kind} decision=${turn.decision}` };
    },
  },
];

async function main() {
  console.log('Answer-precedence report');
  console.log('========================');
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
