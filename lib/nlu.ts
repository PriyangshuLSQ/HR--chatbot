/**
 * Natural Language Understanding engine for the HR assistant.
 *
 * Runs fully offline — no model call — so responses are instant and deterministic.
 * The pipeline is:
 *
 *   raw text
 *     -> normalise      (case, punctuation, contractions, leetish digits)
 *     -> tokenise
 *     -> spell-correct  (Damerau-Levenshtein against the domain vocabulary)
 *     -> canonicalise   (synonym lexicon: "pto" / "time off" -> "leave")
 *     -> stem           (light suffix stripping)
 *     -> score          (IDF-weighted coverage + bigram / phrase bonuses)
 *     -> decide         (answer | clarify once | escalate)
 *
 * To swap in an LLM later, keep `understand()`'s signature and replace the
 * scoring section — everything downstream consumes `NluResult` only.
 */

import type { FAQ } from './chatbot-data';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type IntentAction =
  | 'leave_balance'
  | 'payslip_summary'
  | 'leave_status'
  | 'expense_status'
  | 'asset_status'
  | 'none';

/** Where an escalation is routed. Sensitive matters never go to the general queue. */
export type RouteTarget = 'hr_ops' | 'hrbp' | 'hr_head' | 'none';

/**
 * A recognised topic.
 *
 * Intents classify, they do not answer. Policy answers are generated from HR's
 * indexed documents (see `knowledgeTurn` in `lib/conversation.ts`); what an
 * intent contributes is the topic label the weekly digest groups by, the
 * vocabulary that makes spell correction work, and the two special cases that
 * must not go to a model — a records lookup, and a sensitive matter.
 */
export interface Intent {
  id: string;
  domain: string;
  /** Example phrasings. These train the matcher — add more to improve recall. */
  utterances: string[];
  keywords: string[];
  /**
   * Scripted response. Reserved for sensitive matters, which are answered
   * without a model by design — who to contact, and what happens next, must not
   * vary by what a retrieval happened to surface. Nothing else carries one.
   */
  answer?: string;
  action?: IntentAction;
  requiresAuth?: boolean;
  /** Sensitive intents bypass scoring thresholds and route immediately. */
  sensitive?: boolean;
  route?: RouteTarget;
  tags?: string[];
}

export type NluDecision = 'answer' | 'clarify' | 'escalate' | 'sensitive';

export interface NluCandidate {
  intent: Intent;
  confidence: number;
}

export interface NluResult {
  decision: NluDecision;
  confidence: number;
  intent?: Intent;
  /** Populated when decision === 'clarify' — always exactly one question. */
  clarify?: { question: string; options: { label: string; intentId: string }[] };
  candidates: NluCandidate[];
  /** Debug/analytics view of what the engine actually understood. */
  normalizedQuery: string;
  corrections: { from: string; to: string }[];
  action: IntentAction;
  route: RouteTarget;
  tags: string[];
}

// ---------------------------------------------------------------------------
// Tuning knobs
// ---------------------------------------------------------------------------

/** At or above this we answer outright. */
export const HIGH_CONFIDENCE = 0.6;
/** Below this we treat the query as out of scope and escalate. */
export const LOW_CONFIDENCE = 0.3;
/** If the top two candidates are within this margin, we ask which one they meant. */
export const AMBIGUITY_MARGIN = 0.1;

// ---------------------------------------------------------------------------
// Lexicons
// ---------------------------------------------------------------------------

/** Words that carry no intent signal and would otherwise dilute scores. */
const STOPWORDS = new Set([
  'a', 'an', 'the', 'is', 'am', 'are', 'was', 'were', 'be', 'been', 'being',
  'do', 'does', 'did', 'doing', 'have', 'has', 'had', 'i', 'me', 'my', 'mine',
  'we', 'our', 'you', 'your', 'it', 'its', 'to', 'of', 'in', 'on', 'at', 'for',
  'with', 'about', 'and', 'or', 'but', 'if', 'then', 'than', 'so', 'that',
  'this', 'these', 'those', 'there', 'here', 'can', 'could', 'will', 'would',
  'shall', 'should', 'may', 'might', 'must', 'please', 'hi', 'hello', 'hey',
  'want', 'need', 'know', 'tell', 'get', 'give', 'let', 'kindly', 'sir',
  'madam', 'team', 'pls', 'plz', 'thanks', 'thank',
  // generic question / filler words — they appear across every intent, so they
  // only dilute the IDF-weighted score.
  'which', 'every', 'ever', 'few', 'any', 'all', 'more', 'most', 'some',
  'still', 'yet', 'already', 'just', 'also', 'said', 'says', 'really',
  'actually', 'kind', 'sort', 'thing', 'stuff', 'okay', 'ok', 'yeah',
  'where', 'when', 'why', 'who', 'whom', 'whose', 'not', 'no', 'how', 'what',
  'see', 'show', 'view', 'check', 'look', 'find', 'go', 'come', 'make',
  'before', 'after', 'last', 'week', 'today', 'tomorrow', 'yesterday',
  'long', 'new', 'old', 'much', 'many',
  'employee', 'employees', 'employer', 'staff', 'worker', 'workers',
  'person', 'people', 'someone', 'colleague', 'company',
]);

/**
 * Words that only exist inside PHRASE_MAP patterns. They are added to the
 * spell-check vocabulary so a typo can be repaired into a phrase constituent
 * ("wrk from hom" -> "work from home" -> "wfh").
 */
const PHRASE_VOCAB = [
  'work', 'from', 'home', 'notice', 'period', 'sick', 'casual', 'slip',
  'time', 'off', 'day', 'days', 'form', 'provident', 'fund', 'report',
  'credited', 'length', 'serve', 'papers', 'money',
];

/**
 * Multi-word phrases collapsed before tokenising, so "time off" survives as a
 * single concept rather than two stopword-ish tokens.
 */
const PHRASE_MAP: [RegExp, string][] = [
  [/\btime[\s-]?off\b/g, 'leave'],
  [/\bdays?[\s-]?off\b/g, 'leave'],
  [/\bput(ting)?\s+in\s+(my\s+)?papers?\b/g, 'resignation'],
  [/\bsign(ed)?[\s-]?off\b/g, 'approve'],
  [/\bfull\s+and\s+final\b/g, 'fnf'],
  [/\bform\s*16\b/g, 'form16'],
  [/\bform\s*sixteen\b/g, 'form16'],
  [/\bwork[\s-]?from[\s-]?home\b/g, 'wfh'],
  [/\bsalary[\s-]?slip\b/g, 'payslip'],
  [/\bpay[\s-]?slip\b/g, 'payslip'],
  [/\bpay[\s-]?check\b/g, 'salary'],
  [/\bhealth[\s-]?insurance\b/g, 'insurance'],
  [/\bmedical[\s-]?insurance\b/g, 'insurance'],
  [/\bnotice[\s-]?period\b/g, 'noticeperiod'],
  [/\bprovident[\s-]?fund\b/g, 'pf'],
  [/\bhow[\s-]?many\b/g, 'howmany'],
  [/\bhow[\s-]?much\b/g, 'howmuch'],
  [/\bleft[\s-]?over\b/g, 'remaining'],
  [/\bsick[\s-]?(leave|days?)\b/g, 'sickleave'],
  [/\bcasual[\s-]?leave\b/g, 'casualleave'],
  [/\bmaternity[\s-]?leave\b/g, 'maternity'],
  [/\bpaternity[\s-]?leave\b/g, 'paternity'],
  [/\blaptop[\s-]?request\b/g, 'asset'],
  [/\bexpense[\s-]?report\b/g, 'expense'],
  [/\bnot[\s-]?credited\b/g, 'notcredited'],
  [/\b(salary|pay|payment)\s+(has\s+|is\s+|have\s+)?not\s+(come|arrived?|credited?|received?|reflected?)\b/g, 'salary notcredited'],
];

/** Contractions and common chat shorthand. */
const CONTRACTIONS: Record<string, string> = {
  "whats": 'what', "what's": 'what', "hows": 'how', "how's": 'how',
  "im": 'i', "i'm": 'i', "ive": 'i have', "i've": 'i have',
  "dont": 'do not', "don't": 'do not', "didnt": 'did not', "didn't": 'did not',
  "cant": 'can not', "can't": 'can not', "wont": 'will not', "won't": 'will not',
  "isnt": 'is not', "isn't": 'is not', "hasnt": 'has not', "hasn't": 'has not',
  "i'd": 'i would', "ill": 'i will', "i'll": 'i will',
  u: 'you', ur: 'your', r: 'are', n: 'and', pls: 'please', plz: 'please',
  info: 'information', asap: 'urgent', abt: 'about', bcz: 'because',
  cn: 'can', tmrw: 'tomorrow', tmr: 'tomorrow', '2day': 'today',
  hw: 'how', wat: 'what', wen: 'when', wher: 'where', bal: 'balance',
  msg: 'message', doc: 'document', docs: 'documents', mgr: 'manager',
};

/**
 * Synonym -> canonical term. Applied after spell-correction, so a misspelled
 * synonym ("vacaton") still lands on the canonical form.
 */
const SYNONYMS: Record<string, string> = {
  // leave. NB: "holiday" is deliberately NOT mapped to "leave" — a holiday
  // calendar question is a different intent from a leave question.
  vacation: 'leave', pto: 'leave', absence: 'leave',
  absent: 'leave', leaves: 'leave', leav: 'leave', offday: 'leave',
  furlough: 'leave', sick: 'sickleave',
  // parental
  father: 'paternity', fathers: 'paternity', dad: 'paternity',
  mother: 'maternity', mothers: 'maternity', mom: 'maternity',
  pregnant: 'maternity', pregnancy: 'maternity', expecting: 'maternity',
  baby: 'maternity', newborn: 'maternity', childbirth: 'maternity',
  adoption: 'maternity', parental: 'maternity',
  // public holidays
  holiday: 'holiday', holidays: 'holiday', festival: 'holiday',
  festivals: 'holiday', calendar: 'holiday', diwali: 'holiday',
  // balance
  balance: 'balance', remaining: 'balance', left: 'balance', available: 'balance',
  quota: 'balance', accrued: 'balance', unused: 'balance',
  // salary
  salary: 'salary', pay: 'salary', wage: 'salary', wages: 'salary',
  compensation: 'salary', ctc: 'salary', remuneration: 'salary',
  earnings: 'salary', credited: 'salary', disbursed: 'salary',
  // payslip
  payslip: 'payslip', payslips: 'payslip', paystub: 'payslip',
  // insurance
  // NB: "policy" stays generic — it qualifies many intents ("wfh policy",
  // "leave policy"), so mapping it to insurance mis-routes those queries.
  insurance: 'insurance', mediclaim: 'insurance', medclaim: 'insurance',
  medical: 'insurance', health: 'insurance', coverage: 'insurance',
  dental: 'insurance', vision: 'insurance', hospital: 'insurance',
  hospitalisation: 'insurance', spouse: 'insurance', wife: 'insurance',
  husband: 'insurance', dependent: 'insurance', dependents: 'insurance',
  // reimbursement
  reimbursement: 'expense', reimburse: 'expense', claim: 'expense',
  expenses: 'expense', bill: 'expense', bills: 'expense', receipt: 'expense',
  voucher: 'expense',
  // assets
  laptop: 'asset', hardware: 'asset', device: 'asset', equipment: 'asset',
  machine: 'asset', assets: 'asset', monitor: 'asset',
  // status
  status: 'status', progress: 'status', pending: 'status', approved: 'status',
  rejected: 'status', track: 'status', tracking: 'status', update: 'status',
  // apply
  apply: 'apply', applying: 'apply', request: 'apply', raise: 'apply',
  submit: 'apply', book: 'apply', file: 'apply',
  // tax
  tax: 'tax', taxes: 'tax', tds: 'tax', form16: 'tax', deduction: 'tax',
  investment: 'tax', 80: 'tax',
  // grievance / sensitive
  harassment: 'harassment', harass: 'harassment', harassed: 'harassment',
  abuse: 'harassment', abusive: 'harassment', assault: 'harassment',
  molest: 'harassment', molested: 'harassment', inappropriate: 'harassment',
  misconduct: 'harassment', bully: 'harassment', bullying: 'harassment',
  bullied: 'harassment', threat: 'harassment', threatened: 'harassment',
  discriminate: 'discrimination', discriminated: 'discrimination',
  discrimination: 'discrimination', racist: 'discrimination',
  sexist: 'discrimination', retaliation: 'grievance', retaliate: 'grievance',
  grievance: 'grievance', complaint: 'grievance', complain: 'grievance',
  hostile: 'grievance', unfair: 'grievance', toxic: 'grievance',
  // resignation
  resign: 'resignation', resignation: 'resignation', quit: 'resignation',
  quitting: 'resignation', exit: 'resignation', relieving: 'resignation',
  fnf: 'resignation', settlement: 'resignation', papers: 'resignation',
  serve: 'noticeperiod', notice: 'noticeperiod',
  // referrals
  recommend: 'referral', refers: 'referral', friend: 'referral',
  candidate: 'referral', referring: 'referral',
  // appraisal
  appraisal: 'appraisal', promotion: 'appraisal', hike: 'appraisal',
  increment: 'appraisal', review: 'appraisal', rating: 'appraisal',
  performance: 'appraisal',
  // misc
  wfh: 'wfh', remote: 'wfh', hybrid: 'wfh',
  password: 'password', login: 'password', locked: 'password',
  referral: 'referral', refer: 'referral', bonus: 'bonus', incentive: 'bonus',
};

/** Irregular stems the suffix stripper would get wrong. */
const IRREGULAR_STEMS: Record<string, string> = {
  applied: 'apply', applies: 'apply', got: 'get', taken: 'take',
  took: 'take', paid: 'pay', gave: 'give', went: 'go', children: 'child',
};

// ---------------------------------------------------------------------------
// Knowledge base
// ---------------------------------------------------------------------------

export const INTENTS: Intent[] = [
  // ---------------- Sensitive: routed immediately, never queued -------------
  {
    id: 'sensitive_harassment',
    domain: 'Employee Relations',
    sensitive: true,
    route: 'hr_head',
    tags: ['sensitive', 'posh', 'urgent', 'confidential'],
    keywords: ['harassment', 'discrimination', 'assault', 'unsafe'],
    utterances: [
      'i am being harassed at work',
      'my manager is harassing me',
      'someone touched me inappropriately',
      'i want to report sexual harassment',
      'a colleague is bullying me',
      'i am facing discrimination because of my gender',
      'i feel unsafe at the office',
      'i am being threatened by my teammate',
    ],
    answer:
      "Thank you for telling me. I'm sorry you're dealing with this, and I want to make sure the right person helps you directly.\n\nI've routed this **straight to the HR Head and your HRBP** as a confidential priority matter — it does not go into the general HR queue, and it is not visible to your manager.\n\n**What happens next:** an HRBP will reach out to you privately within **4 business hours**. You can also reach the POSH committee directly at **posh@leadsquared.com** or the confidential helpline at **1800-XXX-XXXX**.\n\nIf you are in immediate danger, please contact local emergency services first.",
  },
  {
    id: 'sensitive_grievance',
    domain: 'Employee Relations',
    sensitive: true,
    route: 'hrbp',
    tags: ['sensitive', 'grievance', 'confidential'],
    keywords: ['grievance', 'complaint', 'hostile', 'retaliation'],
    utterances: [
      'i want to file a grievance',
      'i have a complaint about my manager',
      'i am facing retaliation for reporting an issue',
      'my workplace has become hostile',
      'i am being treated unfairly by my lead',
      'i want to raise a formal complaint confidentially',
    ],
    answer:
      "Thanks for raising this — grievances are handled confidentially and I've escalated yours directly to your **HRBP**, bypassing the general queue.\n\n**What happens next:** your HRBP will contact you privately within **1 business day** to understand the details and agree on next steps with you before anything is actioned.\n\nNothing is shared with your reporting manager without your consent.",
  },

  // ---------------- Darwinbox-backed, live data ---------------------------
  {
    id: 'leave_balance',
    domain: 'Leave',
    action: 'leave_balance',
    requiresAuth: true,
    keywords: ['leave', 'balance', 'sickleave', 'casualleave', 'earned', 'accrued'],
    utterances: [
      'what is my leave balance',
      'how many leaves do i have left',
      'leave balance',
      'how much casual leave is remaining',
      'do i have any sick leave left',
      'show me my available leaves',
      'remaining leave quota this year',
      'how many days off do i still have',
      'balance leave',
    ],
  },
  {
    id: 'payslip_summary',
    domain: 'Payroll',
    action: 'payslip_summary',
    requiresAuth: true,
    keywords: ['payslip', 'breakup', 'netpay', 'download'],
    utterances: [
      'show me my latest payslip',
      'payslip summary',
      'what was my last salary credited',
      'my payslip for last month',
      'how much was i paid last month',
      'salary breakup of my last payslip',
      'net pay last month',
      'send me my salary slip',
    ],
  },
  {
    id: 'leave_status',
    domain: 'Leave',
    action: 'leave_status',
    requiresAuth: true,
    keywords: ['leave', 'status', 'approve', 'manager', 'application'],
    utterances: [
      'what is the status of my leave request',
      'has my leave been approved',
      'is my leave application approved yet',
      'leave request status',
      'did my manager approve my leave',
      'my leave is still pending',
      'track my leave application',
    ],
  },
  {
    id: 'expense_status',
    domain: 'Expenses',
    action: 'expense_status',
    requiresAuth: true,
    keywords: ['expense', 'status', 'money', 'processed', 'travel', 'reimbursed'],
    utterances: [
      'what is the status of my expense claim',
      'has my reimbursement been processed',
      'expense claim status',
      'when will i get my reimbursement',
      'my travel bills are not reimbursed yet',
      'track my expense report',
      'reimbursement pending',
    ],
  },
  {
    id: 'asset_status',
    domain: 'IT Assets',
    action: 'asset_status',
    requiresAuth: true,
    keywords: ['asset', 'status', 'hardware', 'allocated'],
    utterances: [
      'what is the status of my laptop request',
      'asset request status',
      'when will i get my new laptop',
      'has my hardware request been approved',
      'track my asset request',
      'i requested a monitor last week',
    ],
  },

  // ---------------- Policy answers ---------------------------------------
  {
    id: 'leave_apply',
    domain: 'Leave',
    keywords: ['leave', 'apply', 'take', 'process', 'steps', 'procedure',
      'inform', 'notify', 'approval', 'manager'],
    utterances: [
      'how do i apply for leave',
      'how to raise a leave request',
      'process to apply for vacation',
      'where do i submit my leave application',
      'i want to take leave next week',
      'steps to book time off',
    ],
  },
  {
    id: 'leave_policy',
    domain: 'Leave',
    keywords: ['leave', 'policy', 'entitlement', 'type', 'annual', 'carryforward'],
    utterances: [
      'what is the leave policy',
      'how many leaves do we get in a year',
      'types of leave available',
      'annual leave entitlement',
      'explain the leave policy to me',
      'what leave types exist',
    ],
  },
  {
    id: 'maternity_paternity',
    domain: 'Leave',
    keywords: ['maternity', 'paternity'],
    utterances: [
      'what is the maternity leave policy',
      'how long is paternity leave',
      'i am expecting a baby what leave can i take',
      'parental leave entitlement',
      'maternity benefits',
    ],
  },
  {
    id: 'salary_date',
    domain: 'Payroll',
    keywords: ['salary', 'date', 'day', 'month', 'payday', 'notcredited', 'payroll'],
    utterances: [
      'when will i get my salary',
      'what date is payday',
      'salary credit date',
      'my salary has not been credited yet',
      'when is payroll processed',
      'salary notcredited',
    ],
  },
  {
    id: 'tax_form16',
    domain: 'Payroll',
    keywords: ['tax', 'form16', 'declaration', 'proof', '80c'],
    utterances: [
      'where can i find form 16',
      'how do i declare my tax investments',
      'tds deduction on my salary',
      'tax saving declaration deadline',
      'why is so much tax deducted',
      'form 16 download',
    ],
  },
  {
    id: 'insurance_benefits',
    domain: 'Benefits',
    keywords: ['insurance', 'benefit', 'suminsured', 'cashless', 'ecard', 'family'],
    utterances: [
      'what health insurance do i have',
      'is my family covered under mediclaim',
      'insurance coverage details',
      'how do i add my spouse to my insurance',
      'what is my sum insured',
      'dental and vision coverage',
      'how to claim medical insurance',
    ],
  },
  {
    id: 'wfh_policy',
    domain: 'Workplace',
    keywords: ['wfh', 'work', 'home', 'office', 'hybrid', 'anchor'],
    utterances: [
      'what is the work from home policy',
      'how many days can i work remotely',
      'is hybrid working allowed',
      'can i work from home permanently',
      'remote work rules',
      'wfh policy',
    ],
  },
  {
    id: 'expense_submit',
    domain: 'Expenses',
    keywords: ['expense', 'apply', 'upload', 'limit', 'category'],
    utterances: [
      'how do i submit an expense report',
      'how to claim reimbursement',
      'where do i upload my travel bills',
      'process for expense claim',
      'what can i claim as an expense',
    ],
  },
  {
    id: 'resignation_exit',
    domain: 'Exit',
    keywords: ['resignation', 'noticeperiod', 'papers', 'fnf', 'clearance', 'length'],
    utterances: [
      'what is my notice period',
      'how many days notice must i serve',
      'how long do i need to serve notice before leaving',
      'how do i resign',
      'resignation process',
      'when will i get my full and final settlement',
      'i want to quit my job',
      'relieving letter request',
      'exit formalities',
    ],
  },
  {
    id: 'appraisal_cycle',
    domain: 'Performance',
    keywords: ['appraisal', 'cycle', 'goal', 'calibration', 'happen'],
    utterances: [
      'when is the appraisal cycle',
      'how does the performance review work',
      'when will i get my hike',
      'promotion criteria',
      'my rating seems unfair',
      'increment letter',
    ],
  },
  {
    id: 'holiday_calendar',
    domain: 'Leave',
    keywords: ['holiday', 'list', 'public', 'optional', 'floating', 'year'],
    utterances: [
      'what are the holidays this year',
      'holiday calendar',
      'is next monday a holiday',
      'list of public holidays',
      'how many festival holidays do we get',
    ],
  },
  {
    id: 'password_access',
    domain: 'IT Support',
    keywords: ['password', 'reset', 'locked', 'sso', 'account'],
    utterances: [
      'i forgot my darwinbox password',
      'how do i reset my password',
      'my account is locked',
      'cannot login to darwinbox',
      'sso not working',
    ],
  },
  {
    id: 'referral_bonus',
    domain: 'Talent',
    keywords: ['referral', 'bonus', 'payout', 'program', 'job'],
    utterances: [
      'how does the referral program work',
      'referral bonus amount',
      'i want to refer a friend',
      'when do i get my referral payout',
    ],
  },
  {
    id: 'pf_provident',
    domain: 'Benefits',
    keywords: ['pf', 'uan', 'epfo', 'withdraw', 'transfer', 'contribution'],
    utterances: [
      'what is my pf balance',
      'how do i withdraw my provident fund',
      'uan number',
      'pf transfer from previous company',
      'epf contribution details',
    ],
  },
  {
    id: 'onboarding_docs',
    domain: 'Onboarding',
    keywords: ['onboarding', 'document', 'joiner', 'verification', 'paperwork', 'employeeid'],
    utterances: [
      'what documents do i need to submit as a new joiner',
      'onboarding formalities',
      'i just joined what should i do first',
      'employment verification letter',
      'when will i get my employee id',
    ],
  },
];

// ---------------------------------------------------------------------------
// Text processing
// ---------------------------------------------------------------------------

function expandContractions(text: string): string {
  return text
    .split(/\s+/)
    .map((w) => CONTRACTIONS[w] ?? w)
    .join(' ');
}

/** Lowercase, expand contractions, strip punctuation/emoji. No phrase collapsing. */
function normalizeBase(text: string): string {
  let t = text.toLowerCase().trim();
  t = t.replace(/[‘’]/g, "'").replace(/[“”]/g, '"');
  t = expandContractions(t);
  // Keep letters, digits and spaces only.
  return t.replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
}

function applyPhrases(text: string): string {
  let t = text;
  for (const [re, replacement] of PHRASE_MAP) t = t.replace(re, replacement);
  return t.replace(/\s+/g, ' ').trim();
}

/** Lowercase, strip punctuation/emoji, collapse known phrases. */
export function normalize(text: string): string {
  return applyPhrases(normalizeBase(text));
}

/** Light suffix stripping — deliberately conservative to avoid false merges. */
export function stem(word: string): string {
  if (IRREGULAR_STEMS[word]) return IRREGULAR_STEMS[word];
  if (word.length <= 4) return word;
  for (const suffix of ['ingly', 'edly', 'ing', 'ies', 'ied', 'ers', 'er', 'ed', 'es', 's']) {
    if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
      let base = word.slice(0, word.length - suffix.length);
      // "applies" -> "appli" -> "apply"
      if (base.endsWith('i')) base = base.slice(0, -1) + 'y';
      // "submitted" -> "submitt" -> "submit"; keep genuine doubles (ss, ll, ee, oo).
      if (
        base.length > 3 &&
        base[base.length - 1] === base[base.length - 2] &&
        !'slaeo'.includes(base[base.length - 1])
      ) {
        base = base.slice(0, -1);
      }
      return base;
    }
  }
  return word;
}

/**
 * Map a raw token to its canonical concept.
 *
 * Synonyms are applied both before and after stemming: "increments" is not a
 * synonym key, but its stem "increment" is — without the second pass it would
 * never reach the "appraisal" concept.
 */
function canonical(word: string): string {
  const pre = SYNONYMS[word] ?? word;
  const stemmed = stem(pre);
  const post = SYNONYMS[stemmed] ?? stemmed;
  return post === stemmed ? stemmed : stem(post);
}

/**
 * Damerau-Levenshtein distance with early exit once `max` is exceeded.
 * Handles transpositions, which are the most common real typing error
 * ("balnace" -> "balance").
 */
export function editDistance(a: string, b: string, max = 3): number {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > max) return max + 1;

  const prev2: number[] = new Array(b.length + 1);
  let prev: number[] = new Array(b.length + 1);
  let curr: number[] = new Array(b.length + 1);

  for (let j = 0; j <= b.length; j++) prev[j] = j;

  for (let i = 1; i <= a.length; i++) {
    curr[0] = i;
    let rowMin = curr[0];
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let v = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
      // transposition
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        v = Math.min(v, prev2[j - 2] + 1);
      }
      curr[j] = v;
      if (v < rowMin) rowMin = v;
    }
    if (rowMin > max) return max + 1;
    for (let j = 0; j <= b.length; j++) prev2[j] = prev[j];
    const swap = prev;
    prev = curr;
    curr = swap;
  }
  return prev[b.length];
}

// ---------------------------------------------------------------------------
// Vocabulary + IDF, built once from the knowledge base
// ---------------------------------------------------------------------------

interface IntentModel {
  intent: Intent;
  terms: Set<string>;
  /** Terms from `keywords` only — a stronger signal than an incidental
   *  word inside a training utterance, and what breaks ties on 1-2 word
   *  queries where every candidate reaches full coverage. */
  keyTerms: Set<string>;
  bigrams: Set<string>;
  utterances: string[];
}

let VOCAB: Set<string> = new Set();
let IDF: Map<string, number> = new Map();
/** How many intents each term appears in — used to break spell-correction ties. */
let DF: Map<string, number> = new Map();
let MODELS: IntentModel[] = [];
let builtFor = 0;

function rawTokens(text: string): string[] {
  return normalize(text).split(' ').filter(Boolean);
}

/** Tokens for indexing: no spell-correction needed (source is trusted). */
function indexTokens(text: string): string[] {
  return rawTokens(text)
    .filter((w) => !STOPWORDS.has(w))
    .map(canonical)
    .filter((w) => w.length > 1);
}

function bigramsOf(tokens: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < tokens.length - 1; i++) out.push(`${tokens[i]} ${tokens[i + 1]}`);
  return out;
}

/**
 * Build the matching model. FAQ questions are folded in as extra topics, so a
 * subject the seeded set knows how to phrase is still recognised and labelled.
 *
 * Their answer text is deliberately not carried across: nothing in this file
 * answers a question any more. Answers come from HR's indexed documents, and a
 * stored string sitting on an intent is one `?? intent.answer` away from being
 * served again.
 */
export function buildModel(faqs: FAQ[] = []): void {
  const signature = INTENTS.length + faqs.length * 1000;
  if (builtFor === signature && MODELS.length) return;

  const faqIntents: Intent[] = faqs.map((f) => ({
    id: `faq_${f.id}`,
    domain: f.category,
    utterances: [f.question],
    keywords: f.keywords ?? [],
  }));

  const all = [...INTENTS, ...faqIntents];

  MODELS = all.map((intent) => {
    const utterTokens = intent.utterances.flatMap(indexTokens);
    const keyTokens = intent.keywords.flatMap(indexTokens);
    const terms = new Set([...utterTokens, ...keyTokens]);
    const keyTerms = new Set(keyTokens);
    const bigrams = new Set(intent.utterances.flatMap((u) => bigramsOf(indexTokens(u))));
    return {
      intent,
      terms,
      keyTerms,
      bigrams,
      utterances: intent.utterances.map((u) => indexTokens(u).join(' ')),
    };
  });

  // Document frequency over intents -> IDF, so "leave" counts less than "maternity".
  const df = new Map<string, number>();
  for (const m of MODELS) for (const t of m.terms) df.set(t, (df.get(t) ?? 0) + 1);

  const n = MODELS.length;
  IDF = new Map();
  for (const [term, freq] of df) IDF.set(term, Math.log((n + 1) / (freq + 0.5)) + 0.25);

  DF = df;
  VOCAB = new Set(df.keys());
  // Keep unstemmed synonym keys spell-checkable too.
  for (const key of Object.keys(SYNONYMS)) VOCAB.add(key);
  for (const w of PHRASE_VOCAB) VOCAB.add(w);
  builtFor = signature;
}

function idf(term: string): number {
  return IDF.get(term) ?? 1.6; // unseen term -> treat as fairly informative
}

// ---------------------------------------------------------------------------
// Query understanding
// ---------------------------------------------------------------------------

export interface ParsedQuery {
  tokens: string[];
  bigrams: string[];
  normalized: string;
  corrections: { from: string; to: string }[];
}

/**
 * Spell-correct a single token against the domain vocabulary.
 *
 * Ties matter: "leav" is distance 1 from both "leave" and "lead". Iteration
 * order must not decide it, or a typo'd leave query lands on an unrelated
 * intent. Among equal-distance candidates we prefer the one appearing in more
 * intents (higher document frequency), then the closer length.
 */
function correctToken(token: string): string | null {
  if (token.length < 3 || VOCAB.has(token) || VOCAB.has(stem(token))) return null;
  const budget = token.length <= 5 ? 1 : 2;
  let best: string | null = null;
  let bestDist = budget + 1;
  let bestFirst = false;
  let bestDf = -1;

  for (const candidate of VOCAB) {
    const lenDiff = Math.abs(candidate.length - token.length);
    if (lenDiff > budget) continue;
    // Cheap gate: the first two letters usually survive a typo.
    if (candidate[0] !== token[0] && candidate[1] !== token[1]) continue;
    const d = editDistance(token, candidate, budget);
    if (d > budget) continue;

    // A 2-edit correction that also changes the length is usually not a typo at
    // all but a different word: "located" -> "locked" (one substitution, one
    // deletion) sent "where is LeadSquared located" into the password intents and
    // offered the employee a grievance form. Two edits are accepted only between
    // words of the same length, which is a transposition or two substitutions —
    // what a real typo looks like. Inventing a domain term for an out-of-scope
    // query is how the bot ends up answering something nobody asked.
    if (d >= 2 && lenDiff >= 1) continue;

    // Ranking, in order: closer edit distance, then a matching first letter,
    // then the term used by more intents. The first-letter rule matters most
    // for 3-letter tokens, where several vocabulary words sit at distance 1
    // ("hom" is 1 away from both "home" and "mom" — only one is plausible).
    const firstMatch = candidate[0] === token[0];
    const df = DF.get(canonical(candidate)) ?? 0;
    const better =
      d < bestDist ||
      (d === bestDist &&
        ((firstMatch && !bestFirst) || (firstMatch === bestFirst && df > bestDf)));

    if (better) {
      bestDist = d;
      bestFirst = firstMatch;
      bestDf = df;
      best = candidate;
    }
  }
  return bestDist <= budget ? best : null;
}

/**
 * Spell-correction runs BEFORE phrase collapsing, so a typo inside a phrase
 * still forms the phrase: "salry not credted" -> "salary not credited" ->
 * "salary notcredited", which is what actually identifies the intent.
 */
export function parseQuery(text: string): ParsedQuery {
  const corrections: { from: string; to: string }[] = [];

  const corrected = normalizeBase(text)
    .split(' ')
    .filter(Boolean)
    .map((w) => {
      if (STOPWORDS.has(w)) return w;
      const fixed = correctToken(w);
      if (fixed && fixed !== w) {
        corrections.push({ from: w, to: fixed });
        return fixed;
      }
      return w;
    })
    .join(' ');

  const normalized = applyPhrases(corrected);

  const tokens = normalized
    .split(' ')
    .filter(Boolean)
    .filter((w) => !STOPWORDS.has(w))
    .map(canonical)
    .filter((w) => w.length > 1);

  return { tokens, bigrams: bigramsOf(tokens), normalized, corrections };
}

function scoreIntent(q: ParsedQuery, model: IntentModel): number {
  if (!q.tokens.length) return 0;

  const unique = Array.from(new Set(q.tokens));
  let matched = 0;
  let total = 0;

  for (const t of unique) {
    let w = idf(t);
    // A token the domain has never seen ("tmrw", a name, a stray word) carries
    // no HR signal, so it must not be able to veto an otherwise strong match by
    // inflating the denominator. It still counts a little, so that a query made
    // entirely of unknown words scores low rather than matching arbitrarily.
    if (!VOCAB.has(t)) w *= 0.35;
    total += w;
    if (model.terms.has(t)) {
      matched += w;
    } else {
      // Partial credit for near-misses the corrector didn't catch
      // (e.g. compound words, or a stem that diverged slightly).
      let partial = 0;
      for (const term of model.terms) {
        if (term.length >= 4 && t.length >= 4) {
          if (term.startsWith(t) || t.startsWith(term)) partial = Math.max(partial, 0.65);
          else if (editDistance(term, t, 1) <= 1) partial = Math.max(partial, 0.55);
        }
      }
      matched += w * partial;
    }
  }

  let score = total > 0 ? matched / total : 0;

  // Phrase-level agreement: rewards word order, which bag-of-words misses.
  let bigramHits = 0;
  for (const bg of q.bigrams) if (model.bigrams.has(bg)) bigramHits++;
  if (bigramHits) score += Math.min(0.18, bigramHits * 0.09);

  // Near-duplicate of a training utterance.
  const joined = q.tokens.join(' ');
  for (const u of model.utterances) {
    if (!u) continue;
    if (u === joined) { score += 0.25; break; }
    if (u.includes(joined) || joined.includes(u)) { score += 0.12; break; }
  }

  // Very short queries ("payslip") shouldn't be capped by coverage alone —
  // if the one meaningful token is a strong, rare match, trust it.
  if (unique.length === 1 && model.terms.has(unique[0]) && idf(unique[0]) > 1.4) {
    score = Math.max(score, 0.72);
  }

  // A one- or two-word query reaches full coverage against every intent that
  // contains the word, leaving several tied at 1.0. A term the intent declares
  // as a *keyword* is a deliberate signal, so let that break the tie —
  // "salary" then resolves to salary_date rather than whichever intent merely
  // mentions the word in a training sentence.
  let keywordHits = 0;
  for (const t of unique) if (model.keyTerms.has(t)) keywordHits++;
  if (keywordHits) score += Math.min(0.08, keywordHits * 0.04);

  return Math.min(1, score);
}

/**
 * A protected characteristic on its own is not a complaint — "what is the
 * religious holiday policy" is an ordinary question. Discrimination is
 * inferred only when a protected characteristic co-occurs with language about
 * being mistreated, which is why this is a co-occurrence rule rather than a
 * keyword list.
 */
const PROTECTED_RE =
  /\b(religion|religious|caste|race|racial|ethnic|ethnicity|gender|sexual|disability|disabled|orientation|marital|pregnan\w*|nationality)\b/;
/** Unambiguous harassment language, without needing the word itself. */
const HARASSMENT_RE =
  /\b(harass\w*|assault\w*|molest\w*|stalk\w*|inappropriate\s+(touch\w*|behaviou?r|advances?|messages?)|touched\s+me|unsafe|posh)\b/;

/** Someone with power over the employee. Grievances are about these people. */
const AUTHORITY_RE =
  /\b(manager|supervisor|boss|reporting|lead|leader|senior|hod|teammate|colleague|coworker|co-worker|team)\b/;

/** Conduct worth escalating, beyond the softer MISTREAT list. */
const ABUSE_RE =
  /\b(threat\w*|bully\w*|bullied|abus\w*|shout\w*|yell\w*|scream\w*|insult\w*|humiliat\w*|retaliat\w*|intimidat\w*|hostile|toxic|verbally)\b/;

const MISTREAT_RE =
  /\b(singled|unfair\w*|different\w*|denied|deny|excluded?|bias\w*|prejudice\w*|favourit\w*|favorit\w*|targeted|mock\w*|insult\w*|slur\w*|remarks?|comments?|overlooked|humiliat\w*|belittl\w*|passed\s+over)\b/;

/** Sensitive matters are detected before any confidence threshold applies. */
function detectSensitive(q: ParsedQuery): Intent | null {
  const tokenSet = new Set(q.tokens);
  const text = q.normalized;

  const inferredDiscrimination = PROTECTED_RE.test(text) && MISTREAT_RE.test(text);
  const hasHarassment =
    tokenSet.has('harassment')
    || tokenSet.has('discrimination')
    || HARASSMENT_RE.test(text)
    || inferredDiscrimination;

  // Someone reporting mistreatment rarely uses the word "grievance". This has to
  // catch how it is actually said — "my manager is threatening me", "my lead
  // shouts at me" — because these used to be caught only incidentally, by the
  // grievance intent winning a fuzzy keyword score, and that same fuzziness
  // matched "where is LeadSquared located". Explicit patterns replace it: the
  // requirement is a person in authority AND mistreatment of them, in one
  // sentence, which ordinary policy questions do not satisfy.
  const hasGrievance =
    tokenSet.has('grievance')
    || tokenSet.has('complaint')
    || (AUTHORITY_RE.test(text) && (MISTREAT_RE.test(text) || ABUSE_RE.test(text)));

  if (hasHarassment) return INTENTS.find((i) => i.id === 'sensitive_harassment') ?? null;
  if (hasGrievance) return INTENTS.find((i) => i.id === 'sensitive_grievance') ?? null;
  return null;
}

/**
 * Builds the one clarifying question we're allowed to ask.
 *
 * Options are deduplicated by wording: the seeded FAQ set restates several
 * intents, and offering "Holiday calendar" beside "What is the company holiday
 * calendar?" makes the bot look broken and gives the employee no real choice.
 */
function buildClarification(candidates: NluCandidate[]): NluResult['clarify'] {
  const viable = candidates.filter(
    (c) =>
      c.confidence > 0.12 &&
      // Sensitive matters are never offered as a menu item. A weak signal on
      // "grievance" or "harassment" put "I want to file a grievance" in front of
      // someone asking where an office is — and worse, someone who genuinely
      // needs that route should reach it by saying so, not by picking it out of a
      // list of guesses. Detection stays direct; see the `sensitive` decision.
      !c.intent.sensitive
  );
  const options: { label: string; intentId: string }[] = [];
  const seen: string[][] = [];

  for (const c of viable) {
    if (options.length >= 3) break;
    const label = labelForIntent(c.intent);
    const words = label.toLowerCase().split(/[^a-z]+/).filter((w) => w.length > 3);

    const duplicate = seen.some((prev) => {
      if (!prev.length || !words.length) return false;
      const shared = words.filter((w) => prev.includes(w)).length;
      return shared / Math.min(words.length, prev.length) >= 0.6;
    });
    if (duplicate) continue;

    options.push({ label, intentId: c.intent.id });
    seen.push(words);
  }

  if (options.length < 2) return undefined;
  return {
    question: "I want to make sure I answer the right thing — which of these did you mean?",
    options,
  };
}

const INTENT_LABELS: Record<string, string> = {
  leave_balance: 'My leave balance',
  leave_status: 'Status of my leave request',
  leave_apply: 'How to apply for leave',
  leave_policy: 'Leave policy & entitlement',
  payslip_summary: 'My latest payslip',
  salary_date: 'When salary is credited',
  tax_form16: 'Tax / Form 16',
  insurance_benefits: 'Health insurance & benefits',
  expense_status: 'Status of my expense claim',
  expense_submit: 'How to claim an expense',
  asset_status: 'Status of my asset request',
  wfh_policy: 'Work-from-home policy',
  maternity_paternity: 'Maternity / paternity leave',
  resignation_exit: 'Notice period & exit',
  appraisal_cycle: 'Appraisal & increments',
  holiday_calendar: 'Holiday calendar',
  password_access: 'Password / login help',
  referral_bonus: 'Referral programme',
  pf_provident: 'Provident fund',
  onboarding_docs: 'Onboarding & documents',
};

export function labelForIntent(intent: Intent): string {
  return INTENT_LABELS[intent.id] ?? intent.utterances[0] ?? intent.domain;
}

/**
 * Main entry point. `alreadyClarified` prevents a clarification loop — we ask
 * at most one clarifying question per query, then commit to an answer.
 */
export function understand(
  text: string,
  faqs: FAQ[] = [],
  opts: { alreadyClarified?: boolean } = {}
): NluResult {
  buildModel(faqs);
  const q = parseQuery(text);

  const base = {
    candidates: [] as NluCandidate[],
    normalizedQuery: q.normalized,
    corrections: q.corrections,
  };

  // 1. Sensitive topics short-circuit everything.
  const sensitive = detectSensitive(q);
  if (sensitive) {
    return {
      ...base,
      decision: 'sensitive',
      confidence: 1,
      intent: sensitive,
      candidates: [{ intent: sensitive, confidence: 1 }],
      action: 'none',
      route: sensitive.route ?? 'hrbp',
      tags: sensitive.tags ?? ['sensitive'],
    };
  }

  // 2. Nothing meaningful left after stopword removal.
  if (!q.tokens.length) {
    return {
      ...base,
      decision: 'escalate',
      confidence: 0,
      action: 'none',
      route: 'hr_ops',
      tags: ['unparsed'],
    };
  }

  // 3. Score every intent.
  // Ties favour a declared intent over an auto-indexed FAQ topic: the seeded FAQ
  // set overlaps several intents, and the declared version is the one that can
  // carry a live-data action or a sensitive route. Which of two overlapping
  // topics wins no longer changes the answer — both only label it — but it does
  // decide which bucket the weekly digest counts it under.
  const candidates: NluCandidate[] = MODELS
    // Sensitive intents are excluded from ordinary scoring. They have exactly one
    // way in — `detectSensitive` above, which matches explicit language — because
    // their vocabulary is ordinary workplace words: "manager", "unfair", "lead".
    // "Where is LeadSquared located" scored 0.75 against the grievance intent on
    // the strength of "lead", and came back as a confident `answer` carrying a
    // sensitive intent, which is the worst of both outcomes: no routing to a
    // human, and a document search standing in for one.
    .filter((m) => !m.intent.sensitive)
    .map((m) => ({ intent: m.intent, confidence: scoreIntent(q, m) }))
    .sort((a, b) => {
      if (b.confidence !== a.confidence) return b.confidence - a.confidence;
      const aFaq = a.intent.id.startsWith('faq_') ? 1 : 0;
      const bFaq = b.intent.id.startsWith('faq_') ? 1 : 0;
      return aFaq - bFaq;
    });

  const top = candidates[0];
  const second = candidates[1];
  const result = { ...base, candidates: candidates.slice(0, 5) };

  // 4. Out of scope.
  if (!top || top.confidence < LOW_CONFIDENCE) {
    return {
      ...result,
      decision: 'escalate',
      confidence: top?.confidence ?? 0,
      action: 'none',
      route: 'hr_ops',
      tags: ['out-of-scope'],
    };
  }

  const ambiguous =
    !!second &&
    top.confidence - second.confidence < AMBIGUITY_MARGIN &&
    top.confidence < HIGH_CONFIDENCE;

  // 5. Confident, or already clarified once -> answer.
  if (top.confidence >= HIGH_CONFIDENCE || (opts.alreadyClarified && !ambiguous) || opts.alreadyClarified) {
    return {
      ...result,
      decision: 'answer',
      confidence: top.confidence,
      intent: top.intent,
      action: top.intent.action ?? 'none',
      route: 'none',
      tags: [top.intent.domain.toLowerCase()],
    };
  }

  // 6. Low confidence -> exactly one clarifying question.
  const clarify = buildClarification(candidates);
  if (clarify) {
    return {
      ...result,
      decision: 'clarify',
      confidence: top.confidence,
      intent: top.intent,
      clarify,
      action: 'none',
      route: 'none',
      tags: ['clarify'],
    };
  }

  // Below the answer threshold and nothing sensible to ask about: hand over.
  // Never present a weak match as an answer — a confidently-worded reply built
  // on a 30% match is worse than admitting we don't know.
  return {
    ...result,
    decision: 'escalate',
    confidence: top.confidence,
    action: 'none',
    route: 'hr_ops',
    tags: ['low-confidence'],
  };
}

export function intentById(id: string, faqs: FAQ[] = []): Intent | undefined {
  buildModel(faqs);
  return MODELS.find((m) => m.intent.id === id)?.intent;
}
