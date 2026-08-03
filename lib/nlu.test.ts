/**
 * NLU accuracy harness. Run with:  npm run test:nlu
 *
 * The suite is deliberately adversarial: every case is a paraphrase, a typo,
 * a fragment, or slang — never a verbatim training utterance. A case passes
 * when the engine answers with the expected intent, or (for the ambiguous
 * block) asks a clarifying question that includes the expected intent.
 */

import { understand, HIGH_CONFIDENCE } from './nlu';
import { DEFAULT_FAQS } from './chatbot-data';

interface Case {
  q: string;
  expect: string;
  /** Clarifying is an acceptable outcome for genuinely ambiguous phrasing. */
  clarifyOk?: boolean;
}

const CASES: Case[] = [
  // --- paraphrasing -------------------------------------------------------
  { q: 'how many vacation days are left for me', expect: 'leave_balance' },
  { q: 'do I still have any sick days', expect: 'leave_balance' },
  { q: 'show my remaining pto', expect: 'leave_balance' },
  { q: 'what is my leave quota', expect: 'leave_balance' },
  { q: 'i need to take a few days off next month what do i do', expect: 'leave_apply' },
  { q: 'procedure to request time off', expect: 'leave_apply' },
  { q: 'where can i see last month earnings', expect: 'payslip_summary' },
  { q: 'i want to see my salary slip', expect: 'payslip_summary' },
  { q: 'my pay has not come yet', expect: 'salary_date' },
  { q: 'which day do we get paid every month', expect: 'salary_date' },
  { q: 'am i covered for dental treatment', expect: 'insurance_benefits' },
  { q: 'can i add my wife to the mediclaim', expect: 'insurance_benefits' },
  { q: 'how many days must i serve before leaving', expect: 'resignation_exit' },
  { q: 'i am planning to put in my papers', expect: 'resignation_exit' },
  { q: 'when do increments happen', expect: 'appraisal_cycle' },
  { q: 'can i work remotely three days a week', expect: 'wfh_policy' },
  { q: 'is my travel money approved yet', expect: 'expense_status' },
  { q: 'i submitted bills last week where is the money', expect: 'expense_status' },
  { q: 'did the boss sign off on my holiday', expect: 'leave_status', clarifyOk: true },
  { q: 'where is form sixteen', expect: 'tax_form16' },
  { q: 'epf contribution query', expect: 'pf_provident' },
  { q: 'i want to recommend a friend for a job', expect: 'referral_bonus' },
  { q: 'list of festival days off this year', expect: 'holiday_calendar', clarifyOk: true },
  { q: 'my new joiner paperwork', expect: 'onboarding_docs' },
  { q: 'how long is leave for new fathers', expect: 'maternity_paternity' },

  // --- typos -------------------------------------------------------------
  { q: 'wat is my leav balnce', expect: 'leave_balance' },
  { q: 'payslp for last montth', expect: 'payslip_summary' },
  { q: 'salry not credted', expect: 'salary_date' },
  { q: 'insurnce coverge detials', expect: 'insurance_benefits' },
  { q: 'maternty leav policy', expect: 'maternity_paternity' },
  { q: 'reimbursment staus', expect: 'expense_status' },
  { q: 'notice peroid lenght', expect: 'resignation_exit' },
  { q: 'wrk from hom policy', expect: 'wfh_policy' },
  { q: 'forgt my paswrd', expect: 'password_access' },
  { q: 'aprisal cycl', expect: 'appraisal_cycle' },
  { q: 'laptp requst statuss', expect: 'asset_status' },

  // --- incomplete sentences / fragments ----------------------------------
  { q: 'leave balance', expect: 'leave_balance' },
  { q: 'payslip', expect: 'payslip_summary' },
  { q: 'notice period?', expect: 'resignation_exit' },
  { q: 'wfh', expect: 'wfh_policy' },
  { q: 'form 16', expect: 'tax_form16' },
  { q: 'insurance', expect: 'insurance_benefits' },
  { q: 'holidays', expect: 'holiday_calendar', clarifyOk: true },
  { q: 'expense claim status', expect: 'expense_status' },
  { q: 'referral bonus', expect: 'referral_bonus' },
  { q: 'pf', expect: 'pf_provident' },

  // --- slang / shorthand -------------------------------------------------
  { q: 'hw mny leaves left', expect: 'leave_balance' },
  { q: 'need my payslp asap pls', expect: 'payslip_summary' },
  { q: 'cn i wfh tmrw', expect: 'wfh_policy' },
];

/** Sensitive cases must route immediately, never merely answer. */
const SENSITIVE_CASES = [
  'my manager keeps making inappropriate comments about me',
  'i am being bullied by a colleague',
  'i want to report harassment',
  'i faced discrimination during the review',
  'i need to file a grievance against my lead',
  'someone is retaliating against me for complaining',
];

/** Genuinely out-of-scope: must escalate, not hallucinate an answer. */
const OUT_OF_SCOPE = [
  'what is the weather in bangalore tomorrow',
  'can you write me a python script',
  'who won the cricket match last night',
];

let pass = 0;
const failures: string[] = [];

for (const c of CASES) {
  const r = understand(c.q, DEFAULT_FAQS);
  const answeredRight = r.decision === 'answer' && r.intent?.id === c.expect;
  const clarifiedRight =
    c.clarifyOk &&
    r.decision === 'clarify' &&
    r.clarify?.options.some((o) => o.intentId === c.expect);

  if (answeredRight || clarifiedRight) {
    pass++;
  } else {
    failures.push(
      `  "${c.q}"\n     expected ${c.expect}, got ${r.decision}:${r.intent?.id ?? '-'} ` +
        `(conf ${r.confidence.toFixed(2)})`
    );
  }
}

let sensitivePass = 0;
for (const q of SENSITIVE_CASES) {
  const r = understand(q, DEFAULT_FAQS);
  if (r.decision === 'sensitive' && (r.route === 'hrbp' || r.route === 'hr_head')) sensitivePass++;
  else failures.push(`  [sensitive] "${q}" -> ${r.decision} / route=${r.route}`);
}

let oosPass = 0;
for (const q of OUT_OF_SCOPE) {
  const r = understand(q, DEFAULT_FAQS);
  if (r.decision === 'escalate' || r.decision === 'clarify') oosPass++;
  else failures.push(`  [out-of-scope] "${q}" -> ${r.decision}:${r.intent?.id} (conf ${r.confidence.toFixed(2)})`);
}

const total = CASES.length + SENSITIVE_CASES.length + OUT_OF_SCOPE.length;
const totalPass = pass + sensitivePass + oosPass;
const pct = (totalPass / total) * 100;

console.log('NLU accuracy report');
console.log('===================');
console.log(`Understanding cases : ${pass}/${CASES.length}`);
console.log(`Sensitive routing   : ${sensitivePass}/${SENSITIVE_CASES.length}`);
console.log(`Out-of-scope        : ${oosPass}/${OUT_OF_SCOPE.length}`);
console.log(`Overall             : ${totalPass}/${total} = ${pct.toFixed(1)}%`);
console.log(`(high-confidence threshold = ${HIGH_CONFIDENCE})`);

if (failures.length) {
  console.log('\nFailures:');
  console.log(failures.join('\n'));
}

if (pct < 90) {
  console.error(`\nFAIL: accuracy ${pct.toFixed(1)}% is below the 90% target`);
  process.exit(1);
}
console.log('\nPASS: accuracy target met');
