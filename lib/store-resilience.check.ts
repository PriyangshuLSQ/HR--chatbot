/**
 * Proves one withheld permission cannot take down the rest of the dashboard.
 *
 * The failure this guards against was live. `/api/tickets` is gated by `admin.tickets` and
 * `/api/feedback` by `admin.digest`. `hydrate()` fetched both inside a single `Promise.all`, so
 * removing the digest permission from a role — a deliberate, correct thing for an admin to do —
 * 403'd the feedback call, rejected the whole promise, and left the ticket cache empty. Every HR
 * page then showed:
 *
 *   "Ticket store unavailable. Your account does not have HR admin access."
 *
 * Both halves were false. The ticket store was healthy, and the account was an admin holding
 * seven of eight permissions. A 403 on one area had been reported as a total outage and a loss of
 * admin access.
 *
 * So the contract is: resources fail independently, a 403 is not an error, and only a genuine
 * failure produces a message for the banner.
 *
 * Run: pnpm test:store
 */

import { getFeedback, getStoreLoad, getTickets, hydrate, isHydrated } from './hr-store';

interface Stub {
  status: number;
  body: unknown;
}

const calls: string[] = [];

function stubFetch(routes: Array<[string, Stub]>): void {
  calls.length = 0;
  (globalThis as { fetch?: unknown }).fetch = async (input: unknown) => {
    const url = String(input);
    calls.push(url);
    const hit = routes.find(([fragment]) => url.includes(fragment));
    const stub: Stub = hit ? hit[1] : { status: 404, body: { error: 'no stub for ' + url } };
    return {
      ok: stub.status >= 200 && stub.status < 300,
      status: stub.status,
      json: async () => stub.body,
    };
  };
}

const ticket = (id: string) => ({
  id,
  query: 'q',
  reason: 'r',
  raisedBy: 'a@b.com',
  raisedByName: 'A',
  createdAt: '2026-01-01T00:00:00Z',
  status: 'open',
  priority: 'normal',
  route: 'hr_ops',
  assignee: 'HR Ops',
  tags: [],
  confidence: 1,
  transcript: [],
  confidential: false,
});

const rating = (id: string) => ({
  id,
  messageId: id,
  rating: 'up',
  query: 'q',
  confidence: 1,
  createdAt: '2026-01-01T00:00:00Z',
});

let failures = 0;

function check(label: string, pass: boolean, detail: string): void {
  if (!pass) failures += 1;
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${label}`);
  console.log(`        ${detail}`);
}

async function main(): Promise<void> {
  console.log('Store resilience report');
  console.log('=======================\n');

  // -- 1. Both permitted -----------------------------------------------------
  stubFetch([
    ['/api/tickets', { status: 200, body: { tickets: [ticket('T1'), ticket('T2')] } }],
    ['/api/feedback', { status: 200, body: { feedback: [rating('F1')] } }],
  ]);
  let load = await hydrate();
  check(
    'both permitted: everything loads, no banner',
    getTickets().length === 2 && getFeedback().length === 1 && !load.ticketError && !load.feedbackError,
    `tickets=${getTickets().length} feedback=${getFeedback().length} states=${load.tickets}/${load.feedback}`
  );

  // -- 2. THE BUG: digest permission removed --------------------------------
  stubFetch([
    ['/api/tickets', { status: 200, body: { tickets: [ticket('T9'), ticket('T8'), ticket('T7')] } }],
    ['/api/feedback', { status: 403, body: { error: 'Your account does not have HR admin access.' } }],
  ]);
  load = await hydrate();
  check(
    'digest permission removed: escalations still load',
    getTickets().length === 3,
    `tickets=${getTickets().length} (expected 3) feedback state=${load.feedback}`
  );
  check(
    'digest permission removed: no banner is raised',
    !load.ticketError && !load.feedbackError && load.feedback === 'forbidden' && load.tickets === 'ok',
    `ticketError=${load.ticketError ?? 'none'} feedbackError=${load.feedbackError ?? 'none'}`
  );
  check(
    'a deliberate 403 still counts as hydrated',
    isHydrated(),
    'a withheld permission is a known answer, not an unknown one'
  );

  // -- 3. A real outage must still surface ----------------------------------
  stubFetch([
    ['/api/tickets', { status: 500, body: { error: 'Mongo unreachable' } }],
    ['/api/feedback', { status: 200, body: { feedback: [rating('F2')] } }],
  ]);
  load = await hydrate();
  check(
    'a 500 on tickets IS reported',
    load.tickets === 'failed' && !!load.ticketError && !isHydrated(),
    `state=${load.tickets} error=${load.ticketError ?? 'none'}`
  );
  check(
    'and it does not suppress the resource that worked',
    load.feedback === 'ok' && getFeedback().length === 1,
    `feedback state=${load.feedback} count=${getFeedback().length}`
  );

  // -- 4. Skipping what the session may not have ----------------------------
  stubFetch([
    ['/api/tickets', { status: 200, body: { tickets: [ticket('T1')] } }],
    ['/api/feedback', { status: 403, body: { error: 'nope' } }],
  ]);
  await hydrate({ tickets: true, feedback: false });
  check(
    'feedback is not requested when the session lacks admin.digest',
    !calls.some((u) => u.includes('/api/feedback')),
    `requested: ${calls.join(', ') || '(none)'}`
  );

  console.log(`\n${failures === 0 ? 'PASS' : 'FAIL'}: ${failures} failing check(s)`);
  if (failures > 0) process.exit(1);
}

void main();
