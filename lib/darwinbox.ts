/**
 * Darwinbox integration adapter.
 *
 * This is a MOCK adapter: it serves realistic fixtures over an async boundary
 * with simulated network latency, so the UI is written exactly as it would be
 * against the real HRMS. To go live, replace the bodies of the exported
 * `fetch*` functions with calls to the Darwinbox REST API and keep the return
 * shapes identical — no UI change is required.
 *
 *   Real endpoints (v1), for reference:
 *     POST /api/leave/getLeaveBalance      -> fetchLeaveBalances
 *     POST /api/payroll/getPayslip         -> fetchLatestPayslip
 *     POST /api/leave/getLeaveRequests     -> fetchLeaveRequests
 *     POST /api/expense/getClaims          -> fetchExpenseClaims
 *     POST /api/asset/getRequests          -> fetchAssetRequests
 *
 * Every function takes the authenticated user's email — no data is served
 * without it, mirroring the real per-employee scoping.
 */

export type RequestStatus =
  | 'pending'
  | 'approved'
  | 'rejected'
  | 'in-progress'
  | 'paid'
  | 'allocated';

export interface EmployeeProfile {
  employeeId: string;
  name: string;
  email: string;
  designation: string;
  department: string;
  manager: string;
  hrbp: string;
  location: string;
  dateOfJoining: string;
}

export interface LeaveBalance {
  type: string;
  entitled: number;
  used: number;
  available: number;
  pending: number;
}

export interface PayslipSummary {
  month: string;
  paidOn: string;
  gross: number;
  net: number;
  earnings: { label: string; amount: number }[];
  deductions: { label: string; amount: number }[];
  ytdGross: number;
  ytdTax: number;
}

export interface TrackedRequest {
  id: string;
  kind: 'leave' | 'expense' | 'asset';
  title: string;
  detail: string;
  amount?: number;
  status: RequestStatus;
  raisedOn: string;
  approver: string;
  lastUpdate: string;
  timeline: { label: string; at: string; done: boolean }[];
}

export interface DarwinboxSnapshot {
  profile: EmployeeProfile;
  leaveBalances: LeaveBalance[];
  payslip: PayslipSummary;
  requests: TrackedRequest[];
}

/** Simulated round-trip so loading states are exercised honestly. */
const LATENCY_MS = 450;

function delay<T>(value: T, ms = LATENCY_MS): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const DEFAULT_PROFILE: EmployeeProfile = {
  employeeId: 'LSQ-4821',
  name: 'Ananya Sharma',
  email: 'employee@company.com',
  designation: 'Senior Product Analyst',
  department: 'Product',
  manager: 'Rohit Verma',
  hrbp: 'Meera Iyer',
  location: 'Bengaluru',
  dateOfJoining: '2022-03-14',
};

const LEAVE_BALANCES: LeaveBalance[] = [
  { type: 'Casual Leave', entitled: 12, used: 7, available: 5, pending: 1 },
  { type: 'Sick Leave', entitled: 12, used: 3, available: 9, pending: 0 },
  { type: 'Earned Leave', entitled: 18, used: 6, available: 12, pending: 2 },
  { type: 'Comp-off', entitled: 4, used: 1, available: 3, pending: 0 },
];

const PAYSLIP: PayslipSummary = {
  month: 'June 2026',
  paidOn: '2026-06-28',
  gross: 182500,
  net: 148230,
  earnings: [
    { label: 'Basic', amount: 91250 },
    { label: 'House Rent Allowance', amount: 45625 },
    { label: 'Special Allowance', amount: 36500 },
    { label: 'Internet Reimbursement', amount: 1500 },
    { label: 'Performance Bonus', amount: 7625 },
  ],
  deductions: [
    { label: 'Income Tax (TDS)', amount: 21450 },
    { label: 'Provident Fund', amount: 10950 },
    { label: 'Professional Tax', amount: 200 },
    { label: 'Insurance Premium', amount: 1670 },
  ],
  ytdGross: 547500,
  ytdTax: 64350,
};

const REQUESTS: TrackedRequest[] = [
  {
    id: 'LV-20418',
    kind: 'leave',
    title: 'Earned Leave — 2 days',
    detail: '14 Aug 2026 to 15 Aug 2026 · Family function',
    status: 'pending',
    raisedOn: '2026-07-24',
    approver: 'Rohit Verma (Manager)',
    lastUpdate: 'Awaiting manager approval since 24 Jul',
    timeline: [
      { label: 'Submitted', at: '24 Jul, 10:12', done: true },
      { label: 'Manager review', at: 'In progress', done: false },
      { label: 'Approved', at: '—', done: false },
    ],
  },
  {
    id: 'LV-20387',
    kind: 'leave',
    title: 'Casual Leave — 1 day',
    detail: '09 Jul 2026 · Personal',
    status: 'approved',
    raisedOn: '2026-07-05',
    approver: 'Rohit Verma (Manager)',
    lastUpdate: 'Approved on 06 Jul',
    timeline: [
      { label: 'Submitted', at: '05 Jul, 16:40', done: true },
      { label: 'Manager review', at: '06 Jul, 09:15', done: true },
      { label: 'Approved', at: '06 Jul, 09:15', done: true },
    ],
  },
  {
    id: 'EXP-8842',
    kind: 'expense',
    title: 'Client travel — Mumbai',
    detail: 'Flights, cab and 2 nights stay · 3 receipts',
    amount: 24680,
    status: 'in-progress',
    raisedOn: '2026-07-18',
    approver: 'Finance — Payroll cycle',
    lastUpdate: 'Manager approved 21 Jul · queued for 28 Jul payout',
    timeline: [
      { label: 'Submitted', at: '18 Jul, 19:02', done: true },
      { label: 'Manager approved', at: '21 Jul, 11:30', done: true },
      { label: 'Finance verification', at: 'In progress', done: false },
      { label: 'Paid with payroll', at: 'Expected 28 Jul', done: false },
    ],
  },
  {
    id: 'EXP-8790',
    kind: 'expense',
    title: 'Internet reimbursement — June',
    detail: 'Broadband bill · 1 receipt',
    amount: 1500,
    status: 'paid',
    raisedOn: '2026-06-30',
    approver: 'Finance',
    lastUpdate: 'Paid with June payroll on 28 Jun',
    timeline: [
      { label: 'Submitted', at: '30 Jun, 08:45', done: true },
      { label: 'Manager approved', at: '30 Jun, 14:20', done: true },
      { label: 'Paid', at: '28 Jun', done: true },
    ],
  },
  {
    id: 'AST-1174',
    kind: 'asset',
    title: 'MacBook Pro 16" upgrade',
    detail: 'Current device out of warranty · IT ticket linked',
    status: 'approved',
    raisedOn: '2026-07-20',
    approver: 'IT Asset Management',
    lastUpdate: 'Approved 23 Jul · dispatch expected 02 Aug',
    timeline: [
      { label: 'Requested', at: '20 Jul, 12:05', done: true },
      { label: 'Manager approved', at: '22 Jul, 10:00', done: true },
      { label: 'IT approved', at: '23 Jul, 15:45', done: true },
      { label: 'Dispatched', at: 'Expected 02 Aug', done: false },
    ],
  },
  {
    id: 'AST-1102',
    kind: 'asset',
    title: 'External monitor 27"',
    detail: 'Home office setup',
    status: 'allocated',
    raisedOn: '2026-05-11',
    approver: 'IT Asset Management',
    lastUpdate: 'Delivered and acknowledged on 19 May',
    timeline: [
      { label: 'Requested', at: '11 May', done: true },
      { label: 'Approved', at: '13 May', done: true },
      { label: 'Delivered', at: '19 May', done: true },
    ],
  },
];

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

export class NotAuthenticatedError extends Error {
  constructor() {
    super('Darwinbox data requires an authenticated employee session.');
    this.name = 'NotAuthenticatedError';
  }
}

function requireEmail(email: string | undefined | null): string {
  if (!email) throw new NotAuthenticatedError();
  return email;
}

/**
 * The fixture set is single-employee. A real adapter keys off the email; here
 * we personalise the name so the demo reflects whoever signed in.
 */
export async function fetchProfile(email: string | undefined): Promise<EmployeeProfile> {
  const id = requireEmail(email);
  const handle = id.split('@')[0].replace(/[._-]+/g, ' ');
  const displayName =
    id === DEFAULT_PROFILE.email
      ? DEFAULT_PROFILE.name
      : handle.replace(/\b\w/g, (c) => c.toUpperCase());
  return delay({ ...DEFAULT_PROFILE, email: id, name: displayName });
}

export async function fetchLeaveBalances(email: string | undefined): Promise<LeaveBalance[]> {
  requireEmail(email);
  return delay(LEAVE_BALANCES.map((b) => ({ ...b })));
}

export async function fetchLatestPayslip(email: string | undefined): Promise<PayslipSummary> {
  requireEmail(email);
  return delay({ ...PAYSLIP });
}

export async function fetchRequests(
  email: string | undefined,
  kind?: TrackedRequest['kind']
): Promise<TrackedRequest[]> {
  requireEmail(email);
  const rows = kind ? REQUESTS.filter((r) => r.kind === kind) : REQUESTS;
  return delay(rows.map((r) => ({ ...r })));
}

export const fetchLeaveRequests = (email: string | undefined) => fetchRequests(email, 'leave');
export const fetchExpenseClaims = (email: string | undefined) => fetchRequests(email, 'expense');
export const fetchAssetRequests = (email: string | undefined) => fetchRequests(email, 'asset');

/** Everything the chat surface may need, in one round trip. */
export async function fetchSnapshot(email: string | undefined): Promise<DarwinboxSnapshot> {
  const id = requireEmail(email);
  const [profile, leaveBalances, payslip, requests] = await Promise.all([
    fetchProfile(id),
    fetchLeaveBalances(id),
    fetchLatestPayslip(id),
    fetchRequests(id),
  ]);
  return { profile, leaveBalances, payslip, requests };
}

// ---------------------------------------------------------------------------
// Formatting helpers shared by chat and admin
// ---------------------------------------------------------------------------

export function formatCurrency(amount: number): string {
  return `₹${amount.toLocaleString('en-IN')}`;
}

export function totalLeaveAvailable(balances: LeaveBalance[]): number {
  return balances.reduce((sum, b) => sum + b.available, 0);
}

export function statusTone(status: RequestStatus): 'success' | 'warning' | 'error' | 'info' {
  switch (status) {
    case 'approved':
    case 'paid':
    case 'allocated':
      return 'success';
    case 'pending':
    case 'in-progress':
      return 'warning';
    case 'rejected':
      return 'error';
    default:
      return 'info';
  }
}

export function statusLabel(status: RequestStatus): string {
  switch (status) {
    case 'in-progress':
      return 'In progress';
    default:
      return status.charAt(0).toUpperCase() + status.slice(1);
  }
}
