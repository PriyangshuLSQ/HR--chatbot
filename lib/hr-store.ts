/**
 * Shared store for escalation tickets and response feedback.
 *
 * Backed by MongoDB through the Spring Boot service, so a ticket raised in the
 * chat window shows up in the HR admin dashboard — on another machine, for
 * another person, after a restart. It was localStorage before, which meant the
 * dashboard only ever saw escalations raised in that same browser profile.
 *
 * Reads stay synchronous. The UI calls `getTickets()` during render, so the data
 * lives in a module-level cache that `hydrate()` fills on mount; a read before
 * hydration returns an empty list rather than blocking. Writes go to the server
 * and update the cache from its response, so the server's routing decision — not
 * a guess made here — is what ends up on screen.
 *
 * A `hr-store:change` event is dispatched on every change, letting open views
 * re-render, including the tab that made the change (which the native `storage`
 * event does not do).
 */

import {
  fetchFeedback,
  fetchTickets,
  patchTicketStatus,
  postFeedback,
  postTicket,
} from './hr-api';
import type { RouteTarget } from './nlu';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type TicketStatus = 'open' | 'in-progress' | 'resolved';
export type TicketPriority = 'critical' | 'high' | 'normal';

export interface Ticket {
  id: string;
  /** The employee's original wording — never paraphrased. */
  query: string;
  /** Why the bot handed this over. */
  reason: string;
  raisedBy: string;
  raisedByName: string;
  createdAt: string;
  status: TicketStatus;
  priority: TicketPriority;
  route: RouteTarget;
  assignee: string;
  tags: string[];
  /** Confidence the NLU had when it gave up, for triage and tuning. */
  confidence: number;
  /** Preceding turns, so HR has the thread rather than one orphaned line. */
  transcript: { role: 'user' | 'bot'; text: string }[];
  confidential: boolean;
  channel: string;
}

export type Rating = 'up' | 'down';

export interface Feedback {
  id: string;
  messageId: string;
  rating: Rating;
  /** Optional free-text the employee adds after a thumbs-down. */
  comment?: string;
  /** Anonymous by design — we keep the intent, never the employee identity. */
  intentId?: string;
  intentLabel?: string;
  query: string;
  confidence: number;
  createdAt: string;
  channel: string;
}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

export const ROUTE_LABELS: Record<RouteTarget, string> = {
  hr_ops: 'HR Operations',
  hrbp: 'HR Business Partner',
  hr_head: 'HR Head + HRBP',
  none: 'Unassigned',
};

/*
 * Who a ticket is assigned to, and whether it is confidential, is decided by
 * `tickets/Routing.java` on the server — deliberately in one place only. A copy
 * of that rule here would be a second thing to keep in step, and the failure mode
 * is a sensitive escalation landing in the shared HR Ops queue.
 *
 * `createTicket` sends `route` and `sensitive`; the response carries the decision.
 */

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

export const STORE_EVENT = 'hr-store:change';

let ticketCache: Ticket[] = [];
let feedbackCache: Feedback[] = [];
let hydrated = false;
let inFlight: Promise<void> | null = null;

function announce(): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(STORE_EVENT));
}

/**
 * Loads tickets and feedback from the server into the cache. Safe to call from
 * several components on the same page: concurrent callers share one request, and
 * a second call after it settles refetches (which is what the dashboard wants on
 * a manual refresh).
 *
 * Failures are surfaced, not swallowed — an admin looking at an empty dashboard
 * needs to know whether that means "no escalations" or "the store is down".
 */
export async function hydrate(): Promise<void> {
  if (inFlight) return inFlight;

  inFlight = (async () => {
    try {
      const [tickets, feedback] = await Promise.all([fetchTickets(), fetchFeedback()]);
      ticketCache = tickets;
      feedbackCache = feedback;
      hydrated = true;
      announce();
    } finally {
      inFlight = null;
    }
  })();

  return inFlight;
}

/** False until the first successful `hydrate()` — lets a view distinguish empty from unloaded. */
export function isHydrated(): boolean {
  return hydrated;
}

/** Subscribe to any store change. Returns an unsubscribe function. */
export function subscribe(fn: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = () => fn();
  window.addEventListener(STORE_EVENT, handler);
  window.addEventListener('storage', handler);
  return () => {
    window.removeEventListener(STORE_EVENT, handler);
    window.removeEventListener('storage', handler);
  };
}

// ---------------------------------------------------------------------------
// Tickets
// ---------------------------------------------------------------------------

let ticketSeq = 0;

function nextId(prefix: string): string {
  ticketSeq += 1;
  return `${prefix}-${Date.now().toString(36).toUpperCase().slice(-5)}${ticketSeq}`;
}

export function getTickets(): Ticket[] {
  return ticketCache;
}

export interface CreateTicketInput {
  query: string;
  reason: string;
  raisedBy: string;
  raisedByName: string;
  route: RouteTarget;
  sensitive: boolean;
  tags: string[];
  confidence: number;
  transcript: { role: 'user' | 'bot'; text: string }[];
  channel: string;
}

/**
 * Files an escalation and resolves with the stored ticket.
 *
 * The id is generated here so the number the employee is shown is the number in
 * the database, and it is sent with the request; the server refuses a duplicate
 * rather than overwriting. Routing is recomputed server-side and the response —
 * not the local guess — is what lands in the cache.
 *
 * Rejects if the write fails, so the caller can tell the employee the truth
 * instead of quoting a ticket number that does not exist.
 */
export async function createTicket(input: CreateTicketInput): Promise<Ticket> {
  const stored = await postTicket({
    id: nextId(input.sensitive ? 'SEN' : 'HR'),
    query: input.query,
    reason: input.reason,
    raisedBy: input.raisedBy,
    raisedByName: input.raisedByName,
    route: input.route,
    sensitive: input.sensitive,
    tags: Array.from(new Set(input.tags)),
    confidence: input.confidence,
    transcript: input.transcript.slice(-6),
    channel: input.channel,
  });

  ticketCache = [stored, ...ticketCache.filter((t) => t.id !== stored.id)];
  announce();
  return stored;
}

/**
 * Moves a ticket through open → in-progress → resolved.
 *
 * The cache is updated optimistically so the dashboard responds to the click
 * immediately, then reconciled with the server's copy. On failure the previous
 * status is put back — a row that silently shows "resolved" when the write never
 * landed is worse than a click that visibly did nothing.
 */
export async function updateTicketStatus(id: string, status: TicketStatus): Promise<void> {
  const previous = ticketCache;
  ticketCache = ticketCache.map((t) => (t.id === id ? { ...t, status } : t));
  announce();

  try {
    const stored = await patchTicketStatus(id, status);
    ticketCache = ticketCache.map((t) => (t.id === stored.id ? stored : t));
    announce();
  } catch (err) {
    ticketCache = previous;
    announce();
    throw err;
  }
}

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

export function getFeedback(): Feedback[] {
  return feedbackCache;
}

export interface RecordFeedbackInput {
  messageId: string;
  rating: Rating;
  comment?: string;
  intentId?: string;
  intentLabel?: string;
  query: string;
  confidence: number;
  channel: string;
}

/**
 * One rating per message: re-rating replaces the previous entry rather than
 * stacking, so the digest counts employees and not clicks.
 *
 * Applied to the cache before the request so the thumb fills the instant it is
 * clicked, then reconciled with what the server stored. A failed rating reverts
 * and rejects; the caller decides whether that is worth interrupting someone for
 * (it is not — see `components/FeedbackBar.tsx`).
 */
export async function recordFeedback(input: RecordFeedbackInput): Promise<Feedback> {
  const optimistic: Feedback = {
    id: nextId('FB'),
    createdAt: new Date().toISOString(),
    ...input,
  };

  const previous = feedbackCache;
  const rest = feedbackCache.filter((f) => f.messageId !== input.messageId);
  feedbackCache = [optimistic, ...rest];
  announce();

  try {
    const stored = await postFeedback({ ...input, id: optimistic.id });
    feedbackCache = [stored, ...feedbackCache.filter((f) => f.messageId !== stored.messageId)];
    announce();
    return stored;
  } catch (err) {
    feedbackCache = previous;
    announce();
    throw err;
  }
}

export function feedbackForMessage(messageId: string): Feedback | undefined {
  return getFeedback().find((f) => f.messageId === messageId);
}

// ---------------------------------------------------------------------------
// Weekly digest
// ---------------------------------------------------------------------------

export interface DigestRow {
  intentLabel: string;
  intentId: string;
  down: number;
  up: number;
  satisfaction: number;
  examples: { query: string; comment?: string }[];
}

export interface WeeklyDigest {
  from: string;
  to: string;
  total: number;
  up: number;
  down: number;
  satisfaction: number;
  /** Worst-rated topics first — this is what admins actually action. */
  lowRated: DigestRow[];
  comments: { query: string; comment: string; createdAt: string }[];
}

/**
 * Aggregates the trailing `days` of feedback into the digest that gets mailed
 * to admins. Grouped by intent, because "which topic are we failing at" is the
 * actionable unit — not individual messages.
 */
export function buildWeeklyDigest(days = 7, now: Date = new Date()): WeeklyDigest {
  const cutoff = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
  const recent = getFeedback().filter((f) => new Date(f.createdAt) >= cutoff);

  const groups = new Map<string, DigestRow>();
  for (const f of recent) {
    const key = f.intentId ?? 'unmatched';
    const label = f.intentLabel ?? 'Unmatched queries';
    const row =
      groups.get(key) ??
      { intentId: key, intentLabel: label, down: 0, up: 0, satisfaction: 0, examples: [] };
    if (f.rating === 'down') {
      row.down += 1;
      if (row.examples.length < 3) row.examples.push({ query: f.query, comment: f.comment });
    } else {
      row.up += 1;
    }
    groups.set(key, row);
  }

  const rows = Array.from(groups.values()).map((r) => ({
    ...r,
    satisfaction: r.up + r.down > 0 ? r.up / (r.up + r.down) : 0,
  }));

  const up = recent.filter((f) => f.rating === 'up').length;
  const down = recent.length - up;

  return {
    from: cutoff.toISOString(),
    to: now.toISOString(),
    total: recent.length,
    up,
    down,
    satisfaction: recent.length ? up / recent.length : 0,
    lowRated: rows
      .filter((r) => r.down > 0)
      .sort((a, b) => b.down - a.down || a.satisfaction - b.satisfaction),
    comments: recent
      .filter((f) => f.comment && f.rating === 'down')
      .map((f) => ({ query: f.query, comment: f.comment as string, createdAt: f.createdAt }))
      .slice(0, 10),
  };
}
