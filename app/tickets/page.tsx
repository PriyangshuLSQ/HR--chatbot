'use client';

/**
 * The employee's own escalations, read like a mailbox.
 *
 * <p>Full height, two panes, each scrolling independently: a list of tickets and the
 * thread for whichever is selected. That shape is deliberate — a ticket here <em>is</em>
 * a conversation, and a mailbox is the interface everyone already knows for reading
 * one. On a phone the shell shows one pane at a time.
 *
 * <p>Scoping is the server's job, not this page's. {@code GET /api/tickets} returns
 * only your own escalations unless you are an HR admin, and a ticket that is not
 * yours comes back as 404 rather than 403 so an id cannot be probed. Nothing here
 * filters by identity, because a filter in the browser is a suggestion.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useChatbotAuth } from '@/lib/chatbot-auth';
import { fetchTicket, fetchTickets, postTicketComment } from '@/lib/hr-api';
import { ROUTE_LABELS, type Ticket, type TicketStatus } from '@/lib/hr-store';
import { RobinAvatar } from '@/components/Robin';
import RichText from '@/components/RichText';
import { useTheme } from '@/lib/theme';
import {
  ChatIcon,
  CheckCircleIcon,
  ClockIcon,
  MoonIcon,
  SendIcon,
  ShieldIcon,
  SunIcon,
  TicketIcon,
} from '@/components/Icons';

type Filter = 'all' | TicketStatus;

const STATUS: Record<TicketStatus, { label: string; bg: string; ink: string }> = {
  open: { label: 'Open', bg: 'var(--warning-soft)', ink: 'var(--warning-ink)' },
  'in-progress': { label: 'In progress', bg: 'var(--info-soft)', ink: 'var(--info-ink)' },
  resolved: { label: 'Resolved', bg: 'var(--success-soft)', ink: 'var(--success-ink)' },
};

const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'open', label: 'Open' },
  { id: 'in-progress', label: 'Active' },
  { id: 'resolved', label: 'Resolved' },
];

function when(iso: string): string {
  const at = new Date(iso);
  const days = Math.floor((Date.now() - at.getTime()) / 86_400_000);
  if (days <= 0) return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days}d ago`;
  return at.toLocaleDateString([], { day: 'numeric', month: 'short' });
}

function longWhen(iso: string): string {
  return new Date(iso).toLocaleString([], {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function StatusChip({ status, large = false }: { status: TicketStatus; large?: boolean }) {
  const s = STATUS[status] ?? STATUS.open;
  return (
    <span
      style={{
        fontSize: large ? '0.75rem' : '0.6875rem',
        fontWeight: 600,
        padding: large ? '0.1875rem 0.625rem' : '0.125rem 0.5rem',
        borderRadius: 999,
        background: s.bg,
        color: s.ink,
        whiteSpace: 'nowrap',
        display: 'inline-flex',
        alignItems: 'center',
        gap: '0.25rem',
      }}
    >
      {status === 'resolved' ? <CheckCircleIcon size={11} /> : <ClockIcon size={11} />}
      {s.label}
    </span>
  );
}

export default function TicketsPage() {
  const router = useRouter();
  const { theme, toggleTheme } = useTheme();
  const { user, isLoading: authLoading } = useChatbotAuth();

  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [active, setActive] = useState<Ticket | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** Which pane a phone is showing. Ignored above 860px, where both are visible. */
  const [view, setView] = useState<'list' | 'thread'>('list');

  // Not gated on ssoEnabled: this page lists the tickets belonging to the session, so with no
  // session there is nothing here to show and /login is the page that fixes it. The gate used
  // to skip the check entirely wherever Entra was unconfigured.
  useEffect(() => {
    if (authLoading) return;
    if (!user) router.replace('/login');
  }, [authLoading, user, router]);

  const load = useCallback(async () => {
    try {
      const all = await fetchTickets();
      setTickets(all);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load your tickets.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const shown = useMemo(
    () => (filter === 'all' ? tickets : tickets.filter((t) => t.status === filter)),
    [tickets, filter]
  );

  const counts = useMemo(() => {
    const by = (f: Filter) => (f === 'all' ? tickets.length : tickets.filter((t) => t.status === f).length);
    return { all: by('all'), open: by('open'), 'in-progress': by('in-progress'), resolved: by('resolved') };
  }, [tickets]);

  // Auto-select the newest ticket on a wide screen only. Doing it on a phone would
  // open a thread the moment the page loads, hiding the list nobody asked to leave.
  useEffect(() => {
    if (activeId || shown.length === 0) return;
    if (typeof window !== 'undefined' && window.innerWidth <= 860) return;
    setActiveId(shown[0].id);
  }, [shown, activeId]);

  // Re-read on selection so opening a ticket shows any reply added since page load.
  useEffect(() => {
    if (!activeId) return;
    let cancelled = false;

    setActive(tickets.find((t) => t.id === activeId) ?? null);
    void fetchTicket(activeId)
      .then((fresh) => {
        if (!cancelled) setActive(fresh);
      })
      .catch(() => {
        // Keep the list's copy: a failed refresh must not blank a ticket being read.
      });

    return () => {
      cancelled = true;
    };
  }, [activeId, tickets]);

  const open = (id: string) => {
    setActiveId(id);
    setView('thread');
  };

  const onReplied = (updated: Ticket) => {
    setActive(updated);
    setTickets((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
  };

  return (
    <div className="tickets-shell" data-view={view} style={{ background: 'var(--background)' }}>
      <header
        style={{
          background: 'var(--surface)',
          borderBottom: '1px solid var(--border)',
          padding: '0.6875rem 1rem',
          display: 'flex',
          alignItems: 'center',
          gap: '0.75rem',
          flex: 'none',
        }}
      >
        <RobinAvatar size={28} rounded="badge" ring />
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 style={{ fontSize: '0.9375rem', fontWeight: 700, letterSpacing: '-0.01em' }}>
            My HR tickets
          </h1>
          <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
            {counts.all === 0
              ? 'Nothing escalated yet'
              : `${counts.all} ticket${counts.all === 1 ? '' : 's'} · ${counts.open + counts['in-progress']} still open`}
          </p>
        </div>
        {/*
          Same control as the chat header, so the theme can be changed from wherever the employee
          happens to be rather than only from the page that owned the toggle.
        */}
        <button
          className="btn btn-ghost"
          style={{ padding: '0.4375rem' }}
          onClick={toggleTheme}
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
        </button>

        <a href="/chat" className="btn btn-secondary btn-sm">
          <ChatIcon size={15} />
          <span className="hide-mobile">Back to chat</span>
        </a>
      </header>

      <div className="tickets-body">
        <section className="tickets-list">
          <div
            style={{
              padding: '0.625rem 0.75rem',
              borderBottom: '1px solid var(--border)',
              display: 'flex',
              gap: '0.3125rem',
              overflowX: 'auto',
              flex: 'none',
            }}
            className="scroll-slim"
          >
            {FILTERS.map((f) => {
              const on = filter === f.id;
              const n = counts[f.id];
              return (
                <button
                  key={f.id}
                  onClick={() => setFilter(f.id)}
                  style={{
                    padding: '0.3125rem 0.625rem',
                    borderRadius: 999,
                    fontSize: '0.75rem',
                    fontWeight: 600,
                    whiteSpace: 'nowrap',
                    background: on ? 'var(--primary)' : 'var(--surface-2)',
                    color: on ? 'var(--primary-foreground)' : 'var(--muted-foreground)',
                  }}
                  aria-pressed={on}
                >
                  {f.label}
                  {n > 0 && <span style={{ opacity: 0.75 }}> {n}</span>}
                </button>
              );
            })}
          </div>

          <div className="scroll-slim" style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
            {loading ? (
              <div style={{ display: 'grid', gap: '0.5rem', padding: '0.75rem' }}>
                <div className="skeleton" style={{ height: 66 }} />
                <div className="skeleton" style={{ height: 66 }} />
                <div className="skeleton" style={{ height: 66 }} />
              </div>
            ) : error ? (
              <p style={{ padding: '1rem', fontSize: '0.8125rem', color: 'var(--error-ink)' }}>
                {error}
              </p>
            ) : shown.length === 0 ? (
              <EmptyList hasAny={tickets.length > 0} />
            ) : (
              <ul style={{ listStyle: 'none' }}>
                {shown.map((t) => {
                  const selected = t.id === activeId;
                  const replies = t.comments?.length ?? 0;
                  const fromHr = t.comments?.some((c) => c.authorRole === 'hr') ?? false;
                  return (
                    <li key={t.id}>
                      <button
                        onClick={() => open(t.id)}
                        style={{
                          width: '100%',
                          textAlign: 'left',
                          padding: '0.75rem 0.875rem',
                          borderBottom: '1px solid var(--border)',
                          borderLeft: `3px solid ${selected ? 'var(--primary)' : 'transparent'}`,
                          background: selected ? 'var(--primary-soft)' : 'transparent',
                          display: 'grid',
                          gap: '0.3125rem',
                        }}
                        aria-current={selected ? 'true' : undefined}
                      >
                        <span
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            gap: '0.5rem',
                            alignItems: 'center',
                          }}
                        >
                          <span style={{ display: 'inline-flex', gap: '0.375rem', alignItems: 'center' }}>
                            {t.confidential && (
                              <span style={{ color: 'var(--primary)', display: 'inline-flex' }}>
                                <ShieldIcon size={11} />
                              </span>
                            )}
                            <code style={{ fontSize: '0.6875rem', color: 'var(--muted-foreground)' }}>
                              {t.id}
                            </code>
                          </span>
                          <span style={{ fontSize: '0.6875rem', color: 'var(--faint)' }}>
                            {when(t.createdAt)}
                          </span>
                        </span>

                        <span
                          style={{
                            fontSize: '0.8125rem',
                            fontWeight: fromHr ? 700 : 600,
                            lineHeight: 1.4,
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                          }}
                        >
                          {t.query}
                        </span>

                        <span style={{ display: 'flex', gap: '0.375rem', alignItems: 'center' }}>
                          <StatusChip status={t.status} />
                          {replies > 0 && (
                            <span
                              style={{
                                fontSize: '0.6875rem',
                                fontWeight: 600,
                                color: fromHr ? 'var(--primary)' : 'var(--muted-foreground)',
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: '0.1875rem',
                              }}
                            >
                              <ChatIcon size={11} />
                              {replies}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </section>

        <section className="tickets-thread">
          {active ? (
            <TicketThread
              ticket={active}
              onReplied={onReplied}
              onBack={() => setView('list')}
            />
          ) : (
            <div style={{ display: 'grid', placeItems: 'center', height: '100%', padding: '2rem' }}>
              <div style={{ textAlign: 'center', maxWidth: 300 }}>
                <TicketIcon size={26} />
                <p style={{ fontSize: '0.875rem', fontWeight: 600, marginTop: '0.625rem' }}>
                  {tickets.length === 0 ? 'No tickets yet' : 'Pick a ticket to read it'}
                </p>
                {tickets.length === 0 && (
                  <p
                    style={{
                      fontSize: '0.8125rem',
                      color: 'var(--muted-foreground)',
                      marginTop: '0.375rem',
                      lineHeight: 1.55,
                    }}
                  >
                    When Robin can&apos;t answer something, it offers to raise a ticket with HR.
                    Those appear here.
                  </p>
                )}
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function EmptyList({ hasAny }: { hasAny: boolean }) {
  return (
    <p
      style={{
        padding: '1.5rem 1rem',
        fontSize: '0.8125rem',
        color: 'var(--muted-foreground)',
        textAlign: 'center',
        lineHeight: 1.55,
      }}
    >
      {hasAny ? 'Nothing in this filter.' : 'No tickets yet.'}
    </p>
  );
}

/**
 * One ticket: a fixed header, the conversation, and a reply box pinned to the bottom.
 *
 * <p>The three are separate scroll regions on purpose. The subject and status stay
 * visible while reading a long thread, and the reply box never scrolls away — on a
 * page whose whole purpose is answering HR, hunting for the box would be the failure.
 */
function TicketThread({
  ticket,
  onReplied,
  onBack,
}: {
  ticket: Ticket;
  onReplied: (updated: Ticket) => void;
  onBack: () => void;
}) {
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  const thread = ticket.comments ?? [];

  // Newest message in view when a ticket is opened or a reply lands.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [ticket.id, thread.length]);

  const send = async () => {
    const text = reply.trim();
    if (!text) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await postTicketComment(ticket.id, text);
      setReply('');
      onReplied(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not send that reply.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div
        style={{
          padding: '0.875rem 1.25rem',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface)',
          flex: 'none',
        }}
      >
        <button
          className="btn btn-ghost btn-sm tickets-back"
          onClick={onBack}
          style={{ marginBottom: '0.375rem', paddingLeft: 0 }}
        >
          ← All tickets
        </button>

        <div
          style={{
            display: 'flex',
            gap: '0.75rem',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
          }}
        >
          <h2
            style={{
              fontSize: '1.0625rem',
              fontWeight: 700,
              lineHeight: 1.35,
              letterSpacing: '-0.01em',
              flex: 1,
              minWidth: 0,
            }}
          >
            {ticket.query}
          </h2>
          <StatusChip status={ticket.status} large />
        </div>

        <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)', marginTop: '0.375rem' }}>
          <code>{ticket.id}</code> · {longWhen(ticket.createdAt)} · with{' '}
          <strong>{ROUTE_LABELS[ticket.route] ?? ticket.route}</strong>
          {ticket.assignee ? ` · ${ticket.assignee}` : ''}
          {ticket.confidential && (
            <span style={{ color: 'var(--primary)', fontWeight: 600 }}> · Confidential</span>
          )}
        </p>
      </div>

      <div
        className="scroll-slim"
        style={{ flex: 1, overflowY: 'auto', minHeight: 0, padding: '1.25rem' }}
      >
        <div style={{ maxWidth: 720, margin: '0 auto', display: 'grid', gap: '0.875rem' }}>
          {/* What the employee actually asked, as the first message in the thread —
              it is theirs, and reading a reply without it makes no sense. */}
          <Bubble side="you" name="You" at={ticket.createdAt} body={ticket.query} />

          {ticket.reason && (
            <p
              style={{
                fontSize: '0.75rem',
                color: 'var(--faint)',
                textAlign: 'center',
                fontStyle: 'italic',
              }}
            >
              {ticket.reason}
            </p>
          )}

          {ticket.transcript?.length > 0 && (
            <details>
              <summary
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 600,
                  color: 'var(--muted-foreground)',
                  cursor: 'pointer',
                }}
              >
                The conversation attached for HR ({ticket.transcript.length} messages)
              </summary>
              <div
                style={{
                  marginTop: '0.625rem',
                  display: 'grid',
                  gap: '0.5rem',
                  padding: '0.875rem',
                  borderRadius: 10,
                  background: 'var(--surface-2)',
                }}
              >
                {ticket.transcript.map((turn, i) => (
                  <p key={i} style={{ fontSize: '0.75rem', lineHeight: 1.55 }}>
                    <strong style={{ color: 'var(--muted-foreground)' }}>
                      {turn.role === 'user' ? 'You' : 'Robin'}:
                    </strong>{' '}
                    {turn.text}
                  </p>
                ))}
              </div>
            </details>
          )}

          {thread.length === 0 ? (
            <div
              style={{
                padding: '1rem 1.125rem',
                borderRadius: 10,
                border: '1px dashed var(--border-strong)',
                fontSize: '0.8125rem',
                color: 'var(--muted-foreground)',
                lineHeight: 1.6,
              }}
            >
              HR has this and will reply here. Their response appears on this page —{' '}
              <strong>nothing is sent to your inbox</strong>, so check back or reply below to add
              anything.
            </div>
          ) : (
            thread.map((c) => (
              <Bubble
                key={c.id}
                side={c.authorRole === 'hr' ? 'hr' : 'you'}
                name={c.authorRole === 'hr' ? `${c.authorName || 'HR'} · HR` : 'You'}
                at={c.createdAt}
                body={c.body}
              />
            ))
          )}

          <div ref={endRef} />
        </div>
      </div>

      <div
        style={{
          borderTop: '1px solid var(--border)',
          background: 'var(--surface)',
          padding: '0.75rem 1.25rem',
          flex: 'none',
        }}
      >
        <div style={{ maxWidth: 720, margin: '0 auto', display: 'grid', gap: '0.5rem' }}>
          <textarea
            className="input"
            style={{ minHeight: 60, resize: 'vertical', fontSize: '0.8125rem' }}
            value={reply}
            onChange={(e) => setReply(e.target.value)}
            placeholder={
              ticket.status === 'resolved'
                ? 'This ticket is resolved — reply anyway if something is still open'
                : 'Add anything that would help HR…'
            }
          />
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <button className="btn btn-primary btn-sm" onClick={send} disabled={busy || !reply.trim()}>
              <SendIcon size={15} />
              {busy ? 'Sending…' : 'Send reply'}
            </button>
            {error && (
              <span style={{ fontSize: '0.75rem', color: 'var(--error-ink)' }}>{error}</span>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

/** One message. HR on the left, the employee on the right — the chat window's grammar. */
function Bubble({
  side,
  name,
  at,
  body,
}: {
  side: 'hr' | 'you';
  name: string;
  at: string;
  body: string;
}) {
  const hr = side === 'hr';
  return (
    <div style={{ display: 'flex', justifyContent: hr ? 'flex-start' : 'flex-end' }}>
      <div style={{ maxWidth: '85%', display: 'grid', gap: '0.25rem' }}>
        <span
          style={{
            fontSize: '0.6875rem',
            fontWeight: 700,
            color: hr ? 'var(--primary)' : 'var(--muted-foreground)',
            textAlign: hr ? 'left' : 'right',
          }}
        >
          {name}
          <span style={{ fontWeight: 400, color: 'var(--faint)' }}> · {when(at)}</span>
        </span>
        <div
          style={{
            padding: '0.75rem 0.9375rem',
            borderRadius: hr ? '4px 14px 14px 14px' : '14px 4px 14px 14px',
            background: hr ? 'var(--surface)' : 'var(--primary-soft)',
            border: `1px solid ${hr ? 'var(--border)' : 'transparent'}`,
            fontSize: '0.8125rem',
            lineHeight: 1.6,
          }}
        >
          <RichText text={body} />
        </div>
      </div>
    </div>
  );
}
