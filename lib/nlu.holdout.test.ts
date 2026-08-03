/**
 * Held-out NLU set. These cases were written after the engine was tuned on
 * nlu.test.ts and are deliberately NOT used for tuning — this is the honest
 * generalisation number. Run with: npm run test:nlu:holdout
 */

import { understand } from './nlu';
import { DEFAULT_FAQS } from './chatbot-data';

const CASES: { q: string; expect: string }[] = [
  { q: 'how do i check the leaves i have accumulated', expect: 'leave_balance' },
  { q: 'is there any earned leave in my account', expect: 'leave_balance' },
  { q: 'need to book two days next friday', expect: 'leave_apply' },
  { q: 'whom do i inform before going on leave', expect: 'leave_apply' },
  { q: 'my net pay figure for june', expect: 'payslip_summary' },
  { q: 'i cannot open my payslp document', expect: 'payslip_summary' },
  { q: 'bank account did not receive the wages', expect: 'salary_date' },
  { q: 'tds seems higher this month', expect: 'tax_form16' },
  { q: 'do i submit investment proofs in january', expect: 'tax_form16' },
  { q: 'sum insured under the group cover', expect: 'insurance_benefits' },
  { q: 'add my newborn to the mediclaim', expect: 'insurance_benefits' },
  { q: 'travel bill still awaiting approval', expect: 'expense_status' },
  { q: 'what limits apply for meal claims', expect: 'expense_submit' },
  { q: 'my machine allocation request', expect: 'asset_status' },
  { q: 'am i allowed to be remote full time', expect: 'wfh_policy' },
  { q: 'how much notice if i quit during probation', expect: 'resignation_exit' },
  { q: 'when are ratings released', expect: 'appraisal_cycle' },
  { q: 'optional holidays this year', expect: 'holiday_calendar' },
  { q: 'account locked out of darwinbox', expect: 'password_access' },
  { q: 'payout for referring a candidate', expect: 'referral_bonus' },
  { q: 'transfer epf from old employer', expect: 'pf_provident' },
  { q: 'documents required in first week of joining', expect: 'onboarding_docs' },
  { q: 'leave for adopting a child', expect: 'maternity_paternity' },
  { q: 'was my leave request rejected', expect: 'leave_status' },
  { q: 'clarify my casual leav quota pls', expect: 'leave_balance' },
];

const SENSITIVE = [
  'a senior keeps passing sexist remarks in meetings',
  'i was singled out because of my religion',
  'i want to complain formally about favouritism',
];

let strict = 0; // answered with exactly the right intent
let lenient = 0; // right intent, or clarified and offered it
const misses: string[] = [];

for (const c of CASES) {
  const r = understand(c.q, DEFAULT_FAQS);
  const exact = r.decision === 'answer' && r.intent?.id === c.expect;
  const offered =
    r.decision === 'clarify' && r.clarify?.options.some((o) => o.intentId === c.expect);
  if (exact) strict++;
  if (exact || offered) lenient++;
  else misses.push(`  "${c.q}" -> ${r.decision}:${r.intent?.id ?? '-'} (${r.confidence.toFixed(2)}), want ${c.expect}`);
}

let sens = 0;
for (const q of SENSITIVE) {
  const r = understand(q, DEFAULT_FAQS);
  if (r.decision === 'sensitive') sens++;
  else misses.push(`  [sensitive] "${q}" -> ${r.decision}`);
}

const n = CASES.length;
console.log('Held-out NLU report (not tuned against)');
console.log('=======================================');
console.log(`Answered correctly outright : ${strict}/${n} = ${((strict / n) * 100).toFixed(1)}%`);
console.log(`Correct or clarified into it: ${lenient}/${n} = ${((lenient / n) * 100).toFixed(1)}%`);
console.log(`Sensitive routed            : ${sens}/${SENSITIVE.length}`);
if (misses.length) console.log('\nMisses:\n' + misses.join('\n'));
