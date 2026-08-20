/**
 * What gets searched after a clarifying question.
 *
 * A clarification is answered two different ways and they must not be treated alike. Picking an
 * option ("the second one", "leave policy") is a selection: it names a topic but carries none of
 * the detail, so the search combines the topic's canonical phrasing with what was originally
 * asked. Typing a fresh question is not a selection, however much it happens to overlap an option
 * label — and `matchClarifyOption` accepts any reply overlapping 40% of one.
 *
 * The failure this guards: a car-lease question was misclassified as Leave and produced a leave
 * menu; "tell me about leave policy?" matched "Leave policy & entitlement" on two words of three,
 * and the query sent to retrieval was "what is the leave policy what is the car lease entitlement
 * value for the employee at grade x7" — two unrelated questions stapled together. Retrieval
 * served the muddle and the employee was told there was no reliable answer to a question the
 * corpus covers.
 *
 * Run with `npm run test:clarify`.
 */

import { respond, type ConversationContext } from './conversation';
import { DEFAULT_FAQS } from './chatbot-data';
import type { KnowledgeAnswer } from './knowledge/types';

const HIT: KnowledgeAnswer = {
  mode: 'generated',
  answer: 'STUBBED_DOCUMENT_ANSWER',
  citations: [
    { docId: 'd1', title: 'Some Policy', filename: 'some-policy.pdf', headings: [], snippet: '...', score: 0.9 },
  ],
  confidence: 0.9,
};

let asked: string[] = [];

function opts() {
  asked = [];
  return {
    faqs: DEFAULT_FAQS,
    email: 'employee@company.com',
    userName: 'Ananya Sharma',
    channel: 'web',
    transcript: [] as { role: 'user' | 'bot'; text: string }[],
    askKnowledge: async (q: string) => {
      asked.push(q);
      return HIT;
    },
  };
}

/** The state the bot is in after asking "which of these did you mean?" about a car-lease query. */
function pendingLeaveMenu(): ConversationContext {
  return {
    pendingQuery: 'what is the car lease entitlement value for the employee at grade x7?',
    pendingClarify: {
      question: 'I want to make sure I answer the right thing — which of these did you mean?',
      options: [
        { intentId: 'leave_policy', label: 'Leave policy & entitlement' },
        { intentId: 'maternity_paternity', label: 'Maternity / paternity leave' },
        { intentId: 'leave_balance', label: 'My leave balance' },
      ],
    },
  };
}

interface Check {
  name: string;
  run: () => Promise<{ ok: boolean; detail: string }>;
}

const CHECKS: Check[] = [
  {
    name: 'a typed-out question after a clarify is searched as asked, not stapled to the old query',
    run: async () => {
      await respond('tell me about leave policy?', pendingLeaveMenu(), opts());
      const q = asked[0] ?? '';
      const ok = q === 'tell me about leave policy?' && !q.includes('car lease');
      return { ok, detail: `searched=${JSON.stringify(q)}` };
    },
  },
  {
    name: 'a short selection still carries the original question forward',
    run: async () => {
      await respond('leave policy', pendingLeaveMenu(), opts());
      const q = asked[0] ?? '';
      const ok = q.includes('car lease') && q.includes('leave policy');
      return { ok, detail: `searched=${JSON.stringify(q)}` };
    },
  },
  {
    name: 'an ordinal selection carries the original question forward',
    run: async () => {
      await respond('the first one', pendingLeaveMenu(), opts());
      const q = asked[0] ?? '';
      const ok = q.includes('car lease');
      return { ok, detail: `searched=${JSON.stringify(q)}` };
    },
  },
  {
    name: 'a numeric selection carries the original question forward',
    run: async () => {
      await respond('1', pendingLeaveMenu(), opts());
      const q = asked[0] ?? '';
      const ok = q.includes('car lease');
      return { ok, detail: `searched=${JSON.stringify(q)}` };
    },
  },
];

async function main() {
  console.log('Clarify-refinement report');
  console.log('=========================\n');

  let passed = 0;
  for (const check of CHECKS) {
    const { ok, detail } = await check.run();
    if (ok) passed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${check.name}`);
    console.log(`          ${detail}`);
  }

  console.log(`\n${passed}/${CHECKS.length} checks passed`);
  if (passed < CHECKS.length) process.exit(1);
}

main();
