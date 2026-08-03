'use client';

/**
 * The employee's own escalations, read like a mailbox.
 *
 * <p>List on the left, thread on the right. That shape is deliberate: a ticket here
 * <em>is</em> a conversation — the question the employee asked, the transcript that
 * led to it, and whatever HR has since said — and a mailbox is the interface everyone
 * already knows for reading one.
 *
 * <p>Scoping is the server's job, not this page's. {@code GET /api/tickets} returns
 * only your own escalations unless you are an HR admin, and a ticket that is not
 * yours comes back as 404 rather than 403 so an id cannot be probed. Nothing here
 * filters by identity, because a filter in the browser is a suggestion.
 */

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useChatbotAuth } from '@/lib/chatbot-auth';
import { fetchTicket, fetchTickets, postTicketComment } from '@/lib/hr-api';
import { ROUTE_LABELS, type Ticket, type TicketStatus } from '@/lib/hr-store';
import { PoweredByLeadSquared, RobinAvatar } from '@/components/Robin';
import RichText from '@/components/RichText';
import { ChatIcon, SendIcon, ShieldIcon, TicketIcon } from '@/components/Icons';

const STATUS_STYLE: Record<TicketStatus, { label: string; bg: string; ink: string }> = {
  open: { label: 'Open', bg: 'var(--warning-soft)', ink: 'var(--warning-ink)' },
  'in-progress': { label: 'In progress', bg: 'var(--info-soft)', ink: 'var(--info-ink)' },
  resolved: { label: 'Resolved', bg: 'var(--success-soft)', ink: 'var(--success-ink)' },
};

function when(iso: string): string {
  const at = new Date(iso);
  const days = Math.floor((Date.now() - at.getTime()) / 86_400_000);
  if (days <= 0) return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days} days ago`;
  return at.toLocaleDateString([], { day: 'numeric', month: 'short' });
}

function StatusChip({ status }: { status: TicketStatus }) {
  const s = STATUS_STYLE[status] ?? STATUS_STYLE.open;
  return (
    <span
      style={{
        fontSize: '0.6875rem',
        fontWeight: 600,
        padding: '0.125rem 0.5rem',
        borderRadius: 999,
        background: s.bg,
        color: s.ink,
        whiteSpace: 'nowrap',
      }}
    >
      {s.label}
    </span>
  );
}

export default function TicketsPage() {
  const router = useRouter();
  const { user, isLoading: authLoading, ssoEnabled } = useChatbotAuth();

  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [active, setActive] = useState<Ticket | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading || !ssoEnabled) return;
    if (!user) router.replace('/login');
  }, [authLoading, ssoEnabled, user, router]);

  const load = useCallback(async () => {
    try {
      const all = await fetchTickets();
      setTickets(all);
      setActiveId((current) => current ?? all[0]?.id ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load your tickets.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // The list carries the thread already, but it is re-read on selection so opening a
  // ticket shows any reply added since the page was loaded.
  useEffect(() => {
    if (!activeId) return;
    let cancelled = false;

    setActive(tickets.find((t) => t.id === activeId) ?? null);
    void fetchTicket(activeId)
      .then((fresh) => {
        if (!cancelled) setActive(fresh);
      })
      .catch(() => {
        // Keep the list's copy. A failed refresh should not blank a ticket the
        // employee is already reading.
      });

    return () => {
      cancelled = true;
    };
  }, [activeId, tickets]);

  const onReplied = (updated: Ticket) => {
    setActive(updated);
    setTickets((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'var(--background)',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <header
        style={{
          background: 'var(--surface)',
          borderBottom: '1px solid var(--border)',
          padding: '0.75rem 1.25rem',
          display: 'flex',
          alignItems: 'center',
          gap: '0.75rem',
        }}
      >
        <RobinAvatar size={30} rounded="badge" ring />
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>My HR tickets</h1>
          <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
            Everything you&apos;ve escalated, and what HR has said back
          </p>
        </div>
        <a href="/chat" className="btn btn-secondary btn-sm">
          <ChatIcon size={15} />
          <span className="hide-mobile">Back to chat</span>
        </a>
      </header>

      <main
        style={{
          flex: 1,
          maxWidth: 1180,
          width: '100%',
          margin: '0 auto',
          padding: '1.25rem',
          display: 'grid',
          gridTemplateColumns: 'minmax(240px, 340px) 1fr',
          gap: '1rem',
          alignItems: 'start',
        }}
        className="tickets-layout"
      >
        <section className="card" style={{ padding: '0.5rem', overflow: 'hidden' }}>
          {loading ? (
            <div style={{ display: 'grid', gap: '0.5rem', padding: '0.25rem' }}>
              <div className="skeleton" style={{ height: 62 }} />
              <div className="skeleton" style={{ height: 62 }} />
              <div className="skeleton" style={{ height: 62 }} />
            </div>
          ) : error ? (
            <p style={{ padding: '1rem', fontSize: '0.8125rem', color: 'var(--error-ink)' }}>
              {error}
            </p>
          ) : tickets.length === 0 ? (
            <div style={{ padding: '1.5rem 1rem', textAlign: 'center' }}>
              <TicketIcon size={22} />
              <p style={{ fontSize: '0.8125rem', fontWeight: 600, marginTop: '0.5rem' }}>
                No tickets yet
              </p>
              <p
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                  marginTop: '0.25rem',
                  lineHeight: 1.5,
                }}
              >
                When Robin can&apos;t answer something, it offers to raise a ticket with HR. Those
                appear here.
              </p>
            </div>
          ) : (
            <ul style={{ listStyle: 'none', display: 'grid', gap: '0.25rem' }}>
              {tickets.map((t) => {
                const selected = t.id === activeId;
                const replies = t.comments?.length ?? 0;
                return (
                  <li key={t.id}>
                    <button
                      onClick={() => setActiveId(t.id)}
                      style={{
                        width: '100%',
                        textAlign: 'left',
                        padding: '0.625rem 0.75rem',
                        borderRadius: 8,
                        background: selected ? 'var(--primary-soft)' : 'transparent',
                        display: 'grid',
                        gap: '0.25rem',
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
                        <code style={{ fontSize: '0.6875rem', color: 'var(--muted-foreground)' }}>
                          {t.id}
                        </code>
                        <span style={{ fontSize: '0.6875rem', color: 'var(--faint)' }}>
                          {when(t.createdAt)}
                        </span>
                      </span>

                      <span
                        style={{
                          fontSize: '0.8125rem',
                          fontWeight: 600,
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
                              color: 'var(--muted-foreground)',
                              display: 'inline-flex',
                              alignItems: 'center',
                              gap: '0.1875rem',
                            }}
                          >
                            <ChatIcon size={11} />
                            {replies}
                          </span>
                        )}
                        {t.confidential && (
                          <span
                            title="Confidential — handled by your HRBP"
                            style={{ color: 'var(--primary)', display: 'inline-flex' }}
                          >
                            <ShieldIcon size={11} />
                          </span>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </section>

        {active ? (
          <TicketThread ticket={active} onReplied={onReplied} />
        ) : (
          !loading &&
          tickets.length > 0 && (
            <section className="card" style={{ padding: '1.5rem' }}>
              <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
                Pick a ticket to read it.
              </p>
            </section>
          )
        )}
      </main>

      <div style={{ padding: '0 1.25rem 1.5rem' }}>
        <PoweredByLeadSquared />
      </div>
    </div>
  );
}

/** The original question, the conversation behind it, and the thread since. */
function TicketThread({
  ticket,
  onReplied,
}: {
  ticket: Ticket;
  onReplied: (updated: Ticket) => void;
}) {
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const thread = ticket.comments ?? [];
  const resolved = ticket.status === 'resolved';

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
    <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '1rem' }}>
      <div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: '0.75rem',
            alignItems: 'flex-start',
            flexWrap: 'wrap',
          }}
        >
          <h2 style={{ fontSize: '1rem', fontWeight: 700, lineHeight: 1.4, flex: 1, minWidth: 0 }}>
            {ticket.query}
          </h2>
          <StatusChip status={ticket.status} />
        </div>

        <p
          style={{
            fontSize: '0.75rem',
            color: 'var(--muted-foreground)',
            marginTop: '0.375rem',
          }}
        >
          <code>{ticket.id}</code> · raised {when(ticket.createdAt)} · with{' '}
          <strong>{ROUTE_LABELS[ticket.route] ?? ticket.route}</strong>
          {ticket.assignee ? ` · ${ticket.assignee}` : ''}
        </p>

        {ticket.reason && (
          <p style={{ fontSize: '0.75rem', color: 'var(--faint)', marginTop: '0.25rem' }}>
            {ticket.reason}
          </p>
        )}
      </div>

      {/* The conversation that produced the ticket, so the employee can see exactly
          what HR is looking at rather than trusting a summary of it. */}
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
            The conversation attached to this ticket ({ticket.transcript.length} messages)
          </summary>
          <div
            style={{
              marginTop: '0.625rem',
              display: 'grid',
              gap: '0.5rem',
              padding: '0.75rem',
              borderRadius: 8,
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

      <div style={{ borderTop: '1px solid var(--border)', paddingTop: '1rem' }}>
        <p className="label-caps" style={{ marginBottom: '0.75rem' }}>
          {thread.length === 0 ? 'No replies yet' : `Thread · ${thread.length}`}
        </p>

        {thread.length === 0 ? (
          <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', lineHeight: 1.6 }}>
            HR has this and will reply here. You&apos;ll see their response on this page — nothing
            is sent to your inbox.
          </p>
        ) : (
          <div style={{ display: 'grid', gap: '0.75rem' }}>
            {thread.map((c) => {
              const fromHr = c.authorRole === 'hr';
              return (
                <div
                  key={c.id}
                  style={{
                    padding: '0.75rem 0.875rem',
                    borderRadius: 10,
                    background: fromHr ? 'var(--primary-soft)' : 'var(--surface-2)',
                    borderLeft: `3px solid ${fromHr ? 'var(--primary)' : 'var(--border-strong)'}`,
                  }}
                >
                  <p
                    style={{
                      fontSize: '0.6875rem',
                      fontWeight: 700,
                      color: fromHr ? 'var(--primary)' : 'var(--muted-foreground)',
                      marginBottom: '0.25rem',
                    }}
                  >
                    {fromHr ? `${c.authorName || 'HR'} · HR` : 'You'}
                    <span style={{ fontWeight: 400, color: 'var(--faint)' }}>
                      {' '}
                      · {when(c.createdAt)}
                    </span>
                  </p>
                  <div style={{ fontSize: '0.8125rem', lineHeight: 1.6 }}>
                    <RichText text={c.body} />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div style={{ display: 'grid', gap: '0.5rem' }}>
        <textarea
          className="input"
          style={{ minHeight: 74, resize: 'vertical', fontSize: '0.8125rem' }}
          value={reply}
          onChange={(e) => setReply(e.target.value)}
          placeholder={
            resolved
              ? 'This ticket is resolved — reply anyway to reopen the conversation'
              : 'Add anything that would help HR…'
          }
        />
        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <button className="btn btn-primary" onClick={send} disabled={busy || !reply.trim()}>
            <SendIcon size={15} />
            {busy ? 'Sending…' : 'Send reply'}
          </button>
          {error && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--error-ink)' }}>{error}</span>
          )}
        </div>
      </div>
    </section>
  );
}
