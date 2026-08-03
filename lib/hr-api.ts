/**
 * HTTP client for the ticket, feedback and chat-thread endpoints on the Spring
 * Boot service (`backend/`).
 *
 * Every path is relative, so it goes through the `rewrites()` proxy in
 * `next.config.mjs` — same origin, no CORS, and nothing here knows the backend is
 * Java. This mirrors `lib/knowledge/api.ts`, which does the same for the corpus.
 */

import type { Feedback, Ticket, TicketStatus } from './hr-store';

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    // Every failure from this backend carries `{error}`; fall back to the status
    // line for anything that does not (a proxy 502, say).
    const body = (await res.json().catch(() => null)) as { error?: string } | null;
    throw new Error(body?.error ?? `Request failed (${res.status})`);
  }
  return (await res.json()) as T;
}

// ---------------------------------------------------------------------------
// Tickets
// ---------------------------------------------------------------------------

export interface NewTicketBody {
  id: string;
  query: string;
  reason: string;
  raisedBy: string;
  raisedByName: string;
  route: string;
  sensitive: boolean;
  tags: string[];
  confidence: number;
  transcript: { role: 'user' | 'bot'; text: string }[];
  channel: string;
}

export async function fetchTickets(): Promise<Ticket[]> {
  const body = await json<{ tickets: Ticket[] }>(
    await fetch('/api/tickets', { cache: 'no-store' })
  );
  return body.tickets ?? [];
}

export async function postTicket(body: NewTicketBody): Promise<Ticket> {
  return json<Ticket>(
    await fetch('/api/tickets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  );
}

export async function patchTicketStatus(id: string, status: TicketStatus): Promise<Ticket> {
  return json<Ticket>(
    await fetch(`/api/tickets/${encodeURIComponent(id)}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
  );
}

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

export interface NewFeedbackBody {
  id: string;
  messageId: string;
  rating: 'up' | 'down';
  comment?: string;
  intentId?: string;
  intentLabel?: string;
  query: string;
  confidence: number;
  channel: string;
}

export async function fetchFeedback(): Promise<Feedback[]> {
  const body = await json<{ feedback: Feedback[] }>(
    await fetch('/api/feedback', { cache: 'no-store' })
  );
  return body.feedback ?? [];
}

export async function postFeedback(body: NewFeedbackBody): Promise<Feedback> {
  return json<Feedback>(
    await fetch('/api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  );
}

// ---------------------------------------------------------------------------
// Chat threads
// ---------------------------------------------------------------------------

/**
 * Shape-compatible with the chat window's own `Thread`. Declared loosely here
 * because the message payload includes the whole `BotTurn` tree, which only the
 * chat page interprets — the server stores it opaquely and hands it back.
 */
export interface StoredThread {
  id: string;
  title: string;
  updatedAt: number;
  messages: unknown[];
}

/**
 * The signed-in employee's own threads.
 *
 * There is no owner parameter: the server reads it from the Entra session. That
 * is the point — while the client named the owner, anyone who could reach the API
 * could ask for anyone's conversations.
 */
export async function fetchThreads(): Promise<StoredThread[]> {
  const body = await json<{ threads: StoredThread[] }>(
    await fetch('/api/threads', { cache: 'no-store' })
  );
  return body.threads ?? [];
}

export async function putThreads(threads: StoredThread[]): Promise<void> {
  await json<{ ok: boolean }>(
    await fetch('/api/threads', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ threads }),
    })
  );
}
