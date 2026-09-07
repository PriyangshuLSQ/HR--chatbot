/**
 * Proves a spell-correction cannot file a confidential incident.
 *
 * The failure this guards against was live: "how much unpaid leave can I take" was routed to the
 * HRBP as a critical, confidential grievance. Nothing in the question was sensitive. Two guesses
 * composed to produce it — "unpaid" is absent from the HR vocabulary and sat two edits from
 * "unfair", and SYNONYMS maps "unfair" to "grievance", which `detectSensitive` read as the
 * employee having said the word.
 *
 * That is the worst available failure mode for this classifier, and it is worse than a wrong
 * answer: it puts a fabricated grievance under a real person's name on the HR Head's queue, tells
 * them an HRBP will call within a business day, and there is no undo. So the cases below are
 * asserted in both directions — ordinary questions must stay ordinary, and genuine reports must
 * still route.
 *
 * Run: pnpm test:sensitive
 */

import { understand } from './nlu';

/**
 * Three outcomes, not two — conflating the middle one with the last is a mistake worth naming.
 *
 * - `filed`    a hard sensitive decision: routed immediately and filed confidentially, no answer.
 * - `offered`  answered from the documents like any other question, with the confidential desk
 *              named beside the answer. This is the designed behaviour for naming a sensitive
 *              SUBJECT without reporting anything ("what is the POSH policy") and is not a
 *              filing — nothing lands on anyone's queue.
 * - `ordinary` neither. No sensitive handling at all.
 *
 * The bug this file exists for produced `filed`, which is the only irreversible one.
 */
type Outcome = 'filed' | 'offered' | 'ordinary';

interface Case {
  q: string;
  expect: Outcome;
  why: string;
}

const CASES: Case[] = [
  // --- must NOT be sensitive: ordinary HR questions -------------------------
  {
    q: 'how much unpaid leave can I take',
    expect: 'ordinary',
    why: 'the original bug — "unpaid" corrected to "unfair", synonym-mapped to "grievance"',
  },
  {
    q: 'Hypothetically, if I take 30 days of unpaid leave, switch to the new tax regime, and also receive my variable pay in the same month I return — what would my in-hand salary look like in that specific month?',
    expect: 'ordinary',
    why: 'the reported case: a salary question filed as a confidential grievance',
  },
  {
    q: 'can I apply for unpaid leave after my maternity leave ends',
    expect: 'ordinary',
    why: 'unpaid leave beside a second leave type',
  },
  {
    q: 'is unpaid leave approved by my manager or HR',
    expect: 'ordinary',
    why: 'an authority word next to the corrected word — the second half of the rule',
  },
  {
    q: 'what would my salary look like after the increment',
    expect: 'ordinary',
    why: '"like" was corrected to "hike"',
  },
  {
    q: 'how does leave accrual work during a sabbatical',
    expect: 'ordinary',
    why: '"accrual" was corrected to "accrued"',
  },
  {
    q: 'what is the car lease entitlement for my grade',
    expect: 'ordinary',
    why: 'the older "lease" -> "leave" correction this list exists for',
  },
  {
    q: 'what is the POSH policy',
    expect: 'offered',
    why: 'naming the subject without reporting anything: answered, confidential route offered',
  },

  // --- must remain sensitive: real reports ---------------------------------
  {
    q: 'I want to file a grievance',
    expect: 'filed',
    why: 'names it outright',
  },
  {
    q: 'I would like to raise a complaint about my reporting manager',
    expect: 'filed',
    why: 'names a complaint outright',
  },
  {
    q: 'my manager shouts at me in front of the team',
    expect: 'filed',
    why: 'authority plus abuse, in the employee’s own words',
  },
  {
    q: 'my lead has been singling me out and passing me over unfairly',
    expect: 'filed',
    why: 'authority plus mistreatment, in the employee’s own words',
  },
  {
    q: 'I want to report inappropriate behaviour by a senior colleague',
    expect: 'filed',
    why: 'a reported incident',
  },
];

let failed = 0;

console.log('Sensitive-routing / spell-correction report');
console.log('===========================================\n');

for (const c of CASES) {
  const r = understand(c.q, []);
  const actual: Outcome =
    r.decision === 'sensitive' ? 'filed' : r.sensitiveTopic ? 'offered' : 'ordinary';
  const ok = actual === c.expect;
  if (!ok) failed += 1;

  const corrections = r.corrections?.length
    ? r.corrections.map((x) => `${x.from}->${x.to}`).join(', ')
    : '-';

  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${c.expect.padEnd(8)} ${c.q.slice(0, 62)}${c.q.length > 62 ? '…' : ''}`);
  console.log(`          ${c.why}`);
  console.log(`          got=${actual} decision=${r.decision} route=${r.route} corrections=${corrections}`);
  if (!ok) {
    console.log(`          EXPECTED ${c.expect}, GOT ${actual}`);
  }
}

const passed = CASES.length - failed;
console.log(`\n${passed}/${CASES.length} checks passed`);

if (failed > 0) {
  console.log('\nFAIL: a correction can still change how a message is routed.');
  process.exit(1);
}
console.log('PASS: no spell-correction reaches a confidential filing.');
