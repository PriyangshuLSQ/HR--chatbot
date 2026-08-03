'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { DEFAULT_FAQS, type FAQ } from '@/lib/chatbot-data';
import {
  ACCEPTED_EXTENSIONS,
  addEntry,
  deleteDocument,
  fetchAiStatus,
  fetchKnowledge,
  formatBytes,
  isAcceptedFile,
  rebuildIndex,
  uploadDocuments,
  type AiStatus,
  type KnowledgeSnapshot,
  type UploadOutcome,
} from '@/lib/knowledge/api';
import type { KnowledgeDoc } from '@/lib/knowledge/types';
import {
  ROUTE_LABELS,
  buildWeeklyDigest,
  getFeedback,
  getTickets,
  hydrate,
  subscribe,
  updateTicketStatus,
  type Ticket,
  type TicketStatus,
} from '@/lib/hr-store';
import { useChatbotAuth } from '@/lib/chatbot-auth';
import {
  ADMIN_CONSOLE,
  addIamUser,
  createRole,
  deleteRole,
  fetchIamUsers,
  fetchRoles,
  removeIamUser,
  setIamUserRoles,
  slugify,
  updateRole,
  type IamUser,
  type PermissionDefinition,
  type Role,
} from '@/lib/iam-api';
import { PoweredByLeadSquared, RobinAvatar } from '@/components/Robin';
import { INTENTS } from '@/lib/nlu';
import { BarChart, RankedBars, SatisfactionDial, StatTile } from '@/components/Charts';
import RichText from '@/components/RichText';
import {
  AlertIcon,
  BotIcon,
  CheckCircleIcon,
  ClockIcon,
  FileIcon,
  MoonIcon,
  PlusIcon,
  RefreshIcon,
  ShieldIcon,
  SparkIcon,
  SunIcon,
  TicketIcon,
  TrashIcon,
  UploadIcon,
} from '@/components/Icons';

type Tab = 'overview' | 'tickets' | 'digest' | 'knowledge' | 'access';

const TABS: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'tickets', label: 'Escalations' },
  { id: 'digest', label: 'Weekly digest' },
  { id: 'knowledge', label: 'Knowledge base' },
  { id: 'access', label: 'Access' },
];

/** Deterministic weekday volume fixture — swap for real telemetry. */
const VOLUME = [
  { label: 'Mon', value: 412 },
  { label: 'Tue', value: 468 },
  { label: 'Wed', value: 501 },
  { label: 'Thu', value: 447 },
  { label: 'Fri', value: 523 },
  { label: 'Sat', value: 138 },
  { label: 'Sun', value: 96 },
];

export default function AdminPage() {
  const router = useRouter();
  const { user, isLoading: authLoading, ssoEnabled } = useChatbotAuth();

  // The escalation queue holds confidential matters, so this page is admin-only.
  // The backend enforces it too — every /api route behind this console is gated by
  // AdminConsoleAccess, which re-decides per request — so this guard is about
  // telling someone why they cannot be here, not about keeping them out.
  //
  // Signed out is a redirect, because there is a page that fixes it. Signed in
  // without access is not: bouncing someone to /chat reads as a broken link, and
  // they would keep clicking it. Say it plainly instead.
  useEffect(() => {
    if (authLoading || !ssoEnabled) return;
    if (!user) router.replace('/login');
  }, [authLoading, ssoEnabled, user, router]);

  const accessDenied = !authLoading && ssoEnabled && !!user && user.role !== 'hr_admin';

  const [tab, setTab] = useState<Tab>('overview');
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [faqs] = useState<FAQ[]>(DEFAULT_FAQS);
  const [theme, setTheme] = useState<'light' | 'dark' | null>(null);
  const [booted, setBooted] = useState(false);
  const [version, setVersion] = useState(0);
  /** Set when the ticket store could not be reached — shown as a banner. */
  const [storeError, setStoreError] = useState<string | null>(null);

  // The uploaded knowledge base and the local AI engine both live server-side.
  const [kb, setKb] = useState<KnowledgeSnapshot | null>(null);
  const [ai, setAi] = useState<AiStatus | null>(null);

  const refreshKnowledge = useCallback(async () => {
    // Settled rather than all: a stalled Ollama probe must not blank out the
    // document list, which is served from disk and always available.
    const [snapshot, status] = await Promise.allSettled([fetchKnowledge(), fetchAiStatus()]);
    if (snapshot.status === 'fulfilled') setKb(snapshot.value);
    if (status.status === 'fulfilled') setAi(status.value);
  }, []);

  useEffect(() => {
    void refreshKnowledge();
  }, [refreshKnowledge]);

  // Chat and this dashboard share one store, so re-read on any change.
  useEffect(() => {
    const load = () => {
      setTickets(getTickets());
      setVersion((v) => v + 1);
    };

    const unsubscribe = subscribe(load);

    // Tickets and feedback come from MongoDB via the backend. An admin looking at
    // three empty panels must be able to tell "a quiet week" from "the store is
    // down", so the failure is shown rather than logged.
    void hydrate()
      .catch((err: unknown) => {
        setStoreError(err instanceof Error ? err.message : 'Could not load tickets.');
      })
      .finally(() => {
        load();
        setBooted(true);
      });

    const stored = localStorage.getItem('hr_theme') as 'light' | 'dark' | null;
    if (stored) {
      setTheme(stored);
      document.documentElement.setAttribute('data-theme', stored);
    }

    return unsubscribe;
  }, []);

  const digest = useMemo(() => (booted ? buildWeeklyDigest(7) : null), [booted, version]);
  const feedbackCount = useMemo(() => (booted ? getFeedback().length : 0), [booted, version]);

  const openTickets = tickets.filter((t) => t.status !== 'resolved');
  const criticalOpen = openTickets.filter((t) => t.priority === 'critical');

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('hr_theme', next);
  };

  // After every hook, so the early return cannot change the hook order.
  if (accessDenied) return <AccessDenied email={user?.email ?? null} />;

  return (
    <div style={{ minHeight: '100vh', background: 'var(--background)' }}>
      <header
        style={{
          background: 'var(--surface)',
          borderBottom: '1px solid var(--border)',
          position: 'sticky',
          top: 0,
          zIndex: 20,
        }}
      >
        <div
          style={{
            maxWidth: 1180,
            margin: '0 auto',
            padding: '0.875rem 1.25rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.75rem',
            flexWrap: 'wrap',
          }}
        >
          <RobinAvatar size={34} rounded="badge" ring />
          <div style={{ minWidth: 0 }}>
            <h1 style={{ fontSize: '1rem', fontWeight: 700, lineHeight: 1.25 }}>Robin · HR Admin Console</h1>
            <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
              LeadSquared People Team
            </p>
          </div>

          <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            {criticalOpen.length > 0 && (
              <span className="badge badge-error">
                <AlertIcon size={11} />
                {criticalOpen.length} sensitive open
              </span>
            )}
            <button
              className="btn btn-ghost"
              style={{ padding: '0.4375rem' }}
              onClick={toggleTheme}
              aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            >
              {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
            </button>
            <a href="/chat" className="btn btn-secondary btn-sm">
              Open chat
            </a>
          </div>
        </div>

        <nav
          className="scroll-slim"
          style={{
            maxWidth: 1180,
            margin: '0 auto',
            padding: '0 1.25rem',
            display: 'flex',
            gap: '0.25rem',
            overflowX: 'auto',
          }}
        >
          {TABS.map((t) => {
            const isActive = tab === t.id;
            return (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                style={{
                  padding: '0.625rem 0.875rem',
                  fontSize: '0.875rem',
                  fontWeight: 600,
                  color: isActive ? 'var(--primary)' : 'var(--muted-foreground)',
                  borderBottom: '2px solid',
                  borderColor: isActive ? 'var(--primary)' : 'transparent',
                  whiteSpace: 'nowrap',
                  transition: 'color 0.16s, border-color 0.16s',
                }}
                aria-current={isActive ? 'page' : undefined}
              >
                {t.label}
                {t.id === 'tickets' && openTickets.length > 0 && (
                  <span
                    style={{
                      marginLeft: '0.375rem',
                      fontSize: '0.6875rem',
                      background: isActive ? 'var(--primary)' : 'var(--surface-3)',
                      color: isActive ? '#fff' : 'var(--muted-foreground)',
                      padding: '0.0625rem 0.375rem',
                      borderRadius: 999,
                    }}
                  >
                    {openTickets.length}
                  </span>
                )}
              </button>
            );
          })}
        </nav>
      </header>

      <main style={{ maxWidth: 1180, margin: '0 auto', padding: '1.5rem 1.25rem 3rem' }}>
        {!booted ? (
          <div style={{ display: 'grid', gap: '1rem' }}>
            <div className="skeleton" style={{ height: 96 }} />
            <div className="skeleton" style={{ height: 240 }} />
          </div>
        ) : (
          <>
            {storeError && (
              <div
                className="card"
                style={{
                  padding: '0.875rem 1rem',
                  marginBottom: '1.25rem',
                  borderColor: 'var(--error)',
                  background: 'var(--error-soft)',
                  fontSize: '0.8125rem',
                }}
              >
                <strong style={{ color: 'var(--error)' }}>Ticket store unavailable.</strong>{' '}
                {storeError} Escalations and ratings are not being shown — this is not an empty
                queue. The knowledge base below is unaffected.
              </div>
            )}
            {tab === 'overview' && (
              <Overview
                digest={digest}
                openTickets={openTickets}
                totalTickets={tickets.length}
                feedbackCount={feedbackCount}
                faqCount={faqs.length}
                ai={ai}
              />
            )}
            {tab === 'tickets' && <Tickets tickets={tickets} />}
            {tab === 'digest' && <Digest digest={digest} />}
            {tab === 'knowledge' && (
              <Knowledge faqs={faqs} kb={kb} ai={ai} onChanged={refreshKnowledge} />
            )}
            {tab === 'access' && <Access />}
          </>
        )}
        <div style={{ marginTop: '2rem' }}>
          <PoweredByLeadSquared />
        </div>
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

function Overview({
  digest,
  openTickets,
  totalTickets,
  feedbackCount,
  faqCount,
  ai,
}: {
  digest: ReturnType<typeof buildWeeklyDigest> | null;
  openTickets: Ticket[];
  totalTickets: number;
  feedbackCount: number;
  faqCount: number;
  ai: AiStatus | null;
}) {
  const weekTotal = VOLUME.reduce((s, d) => s + d.value, 0);
  const escalationRate = weekTotal > 0 ? totalTickets / weekTotal : 0;

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      <section
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
          gap: '0.875rem',
        }}
      >
        <StatTile
          label="Conversations this week"
          value={weekTotal.toLocaleString()}
          delta="12.5% vs last week"
          deltaGood
          icon={<SparkIcon size={15} />}
        />
        <StatTile
          label="Resolved without HR"
          value={`${Math.round((1 - escalationRate) * 100)}%`}
          note="Answered by the assistant alone"
          icon={<CheckCircleIcon size={15} />}
        />
        <StatTile
          label="Open escalations"
          value={String(openTickets.length)}
          note={`${totalTickets} raised in total`}
          icon={<TicketIcon size={15} />}
        />
        <StatTile
          label="Feedback collected"
          value={String(feedbackCount)}
          note="Anonymous thumbs up / down"
          icon={<BotIcon size={15} />}
        />
      </section>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
          gap: '1.25rem',
        }}
      >
        <section className="card" style={{ padding: '1.125rem' }}>
          <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
            Conversation volume
          </h2>
          <p
            style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}
          >
            Employee queries per day, last 7 days
          </p>
          <BarChart data={VOLUME} />
        </section>

        <section className="card" style={{ padding: '1.125rem' }}>
          <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
            Response satisfaction
          </h2>
          <p
            style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}
          >
            From anonymous ratings, last 7 days
          </p>
          {digest && digest.total > 0 ? (
            <SatisfactionDial ratio={digest.satisfaction} up={digest.up} down={digest.down} />
          ) : (
            <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
              No ratings collected yet this week.
            </p>
          )}
        </section>
      </div>

      <section className="card" style={{ padding: '1.125rem' }}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            gap: '1rem',
            flexWrap: 'wrap',
            marginBottom: '0.25rem',
          }}
        >
          <h2 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>Knowledge coverage</h2>
          {ai && <CapabilityBadge capability={ai.capability} />}
        </div>
        <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
          {ai
            ? `Answers come from ${ai.stats.docCount} uploaded document${
                ai.stats.docCount === 1 ? '' : 's'
              } (${ai.stats.chunkCount} searchable passages, ${ai.stats.embeddedChunks} embedded)`
            : 'Answers come from your uploaded documents'}
          {' — '}
          {INTENTS.filter((i) => !i.sensitive).length + faqCount} recognised topics route and label
          questions, but hold no answers of their own. Anything the documents don&apos;t cover is
          escalated to a human rather than guessed at, so coverage here is coverage in fact.
        </p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.375rem' }}>
          {Array.from(new Set(INTENTS.map((i) => i.domain))).map((domain) => (
            <span key={domain} className="badge badge-neutral">
              {domain}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Escalations
// ---------------------------------------------------------------------------

function Tickets({ tickets }: { tickets: Ticket[] }) {
  const [filter, setFilter] = useState<'all' | 'open' | 'sensitive'>('all');

  const rows = tickets.filter((t) => {
    if (filter === 'open') return t.status !== 'resolved';
    if (filter === 'sensitive') return t.priority === 'critical';
    return true;
  });

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <div style={{ display: 'flex', gap: '0.375rem', flexWrap: 'wrap' }}>
        {(['all', 'open', 'sensitive'] as const).map((f) => (
          <button
            key={f}
            className={filter === f ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
            onClick={() => setFilter(f)}
          >
            {f === 'all' ? 'All' : f === 'open' ? 'Open only' : 'Sensitive only'}
          </button>
        ))}
      </div>

      {!rows.length && (
        <p className="card" style={{ padding: '1.5rem', color: 'var(--muted-foreground)' }}>
          Nothing here. Escalations raised from the chat window appear immediately.
        </p>
      )}

      {rows.map((t) => (
        <TicketRow key={t.id} ticket={t} />
      ))}
    </div>
  );
}

function TicketRow({ ticket }: { ticket: Ticket }) {
  const critical = ticket.priority === 'critical';
  const [status, setStatus] = useState<TicketStatus>(ticket.status);
  const [failed, setFailed] = useState(false);

  /**
   * Moves the row immediately, then puts it back if the write did not land. HR
   * triages off this column — a status that looks saved but is not means someone
   * assumes a critical escalation is being handled when nobody has it.
   */
  const change = (next: TicketStatus) => {
    const previous = status;
    setStatus(next);
    setFailed(false);
    void updateTicketStatus(ticket.id, next).catch(() => {
      setStatus(previous);
      setFailed(true);
    });
  };

  return (
    <article
      className="card"
      style={{ padding: '1.125rem', borderColor: critical ? 'var(--error)' : 'var(--border)' }}
    >
      <div
        style={{
          display: 'flex',
          gap: '0.625rem',
          alignItems: 'center',
          flexWrap: 'wrap',
          marginBottom: '0.75rem',
        }}
      >
        <span style={{ display: 'flex', color: critical ? 'var(--error-ink)' : 'var(--primary)' }}>
          {critical ? <ShieldIcon size={16} /> : <TicketIcon size={16} />}
        </span>
        <strong style={{ fontSize: '0.875rem' }}>{ticket.id}</strong>

        {/* Status and priority are always icon + word, never colour alone. */}
        <span className={`badge badge-${critical ? 'error' : 'info'}`}>
          {critical ? <AlertIcon size={11} /> : <ClockIcon size={11} />}
          {critical ? 'Critical' : 'Normal'}
        </span>

        <span
          className={`badge badge-${
            status === 'resolved' ? 'success' : status === 'in-progress' ? 'warning' : 'neutral'
          }`}
        >
          {status === 'resolved' ? <CheckCircleIcon size={11} /> : <ClockIcon size={11} />}
          {status === 'in-progress' ? 'In progress' : status === 'resolved' ? 'Resolved' : 'Open'}
        </span>

        <span style={{ marginLeft: 'auto', fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
          {new Date(ticket.createdAt).toLocaleString('en-IN', {
            day: 'numeric',
            month: 'short',
            hour: '2-digit',
            minute: '2-digit',
          })}
        </span>
      </div>

      <blockquote
        style={{
          fontSize: '0.9375rem',
          fontWeight: 500,
          padding: '0.625rem 0.875rem',
          background: 'var(--surface-2)',
          borderLeft: '3px solid',
          borderColor: critical ? 'var(--error)' : 'var(--primary)',
          borderRadius: '0 var(--radius-sm) var(--radius-sm) 0',
          marginBottom: '0.75rem',
        }}
      >
        “{ticket.query}”
      </blockquote>

      <dl
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
          gap: '0.625rem 1rem',
          fontSize: '0.8125rem',
          marginBottom: '0.75rem',
        }}
      >
        <div>
          <dt className="label-caps">Routed to</dt>
          <dd>{ROUTE_LABELS[ticket.route]}</dd>
        </div>
        <div>
          <dt className="label-caps">Assignee</dt>
          <dd>{ticket.assignee}</dd>
        </div>
        <div>
          <dt className="label-caps">Raised by</dt>
          <dd>{ticket.raisedByName}</dd>
        </div>
        <div>
          <dt className="label-caps">Channel</dt>
          <dd>{ticket.channel}</dd>
        </div>
      </dl>

      <p
        style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '0.75rem' }}
      >
        <strong style={{ color: 'var(--foreground-secondary)' }}>Why it escalated:</strong>{' '}
        {ticket.reason} (NLU confidence {Math.round(ticket.confidence * 100)}%)
      </p>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.375rem', marginBottom: '0.875rem' }}>
        {ticket.tags.map((tag) => (
          <span key={tag} className="badge badge-neutral">
            #{tag}
          </span>
        ))}
      </div>

      {ticket.transcript.length > 0 && (
        <details style={{ marginBottom: '0.875rem' }}>
          <summary
            style={{
              fontSize: '0.8125rem',
              fontWeight: 600,
              cursor: 'pointer',
              color: 'var(--primary)',
            }}
          >
            View attached conversation ({ticket.transcript.length} messages)
          </summary>
          <div style={{ display: 'grid', gap: '0.375rem', marginTop: '0.625rem' }}>
            {ticket.transcript.map((m, i) => (
              <p
                key={i}
                style={{
                  fontSize: '0.75rem',
                  padding: '0.4375rem 0.625rem',
                  borderRadius: 'var(--radius-sm)',
                  background: m.role === 'user' ? 'var(--primary-soft)' : 'var(--surface-2)',
                  color: m.role === 'user' ? 'var(--info-ink)' : 'var(--foreground-secondary)',
                }}
              >
                <strong>{m.role === 'user' ? 'Employee' : 'Assistant'}:</strong>{' '}
                {m.text.length > 220 ? `${m.text.slice(0, 220)}…` : m.text}
              </p>
            ))}
          </div>
        </details>
      )}

      <div style={{ display: 'flex', gap: '0.375rem', flexWrap: 'wrap', alignItems: 'center' }}>
        {(['open', 'in-progress', 'resolved'] as TicketStatus[]).map((s) => (
          <button
            key={s}
            className={status === s ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
            onClick={() => change(s)}
          >
            {s === 'in-progress' ? 'In progress' : s === 'open' ? 'Open' : 'Resolved'}
          </button>
        ))}
        {failed && (
          <span style={{ fontSize: '0.75rem', color: 'var(--error)', fontWeight: 600 }}>
            Could not save — status unchanged. Try again.
          </span>
        )}
      </div>
    </article>
  );
}

// ---------------------------------------------------------------------------
// Weekly digest
// ---------------------------------------------------------------------------

function Digest({ digest }: { digest: ReturnType<typeof buildWeeklyDigest> | null }) {
  if (!digest) return null;

  const range = `${new Date(digest.from).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
  })} – ${new Date(digest.to).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}`;

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '1rem' }}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            gap: '1rem',
            flexWrap: 'wrap',
          }}
        >
          <div>
            <h2 style={{ fontSize: '1rem', fontWeight: 700 }}>Weekly digest</h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
              {range} · emailed to HR admins every Monday at 09:00 IST
            </p>
          </div>
          <span className="badge badge-info">
            <ClockIcon size={11} />
            {digest.total} ratings
          </span>
        </div>

        {digest.total === 0 ? (
          <p style={{ fontSize: '0.875rem', color: 'var(--muted-foreground)' }}>
            No feedback in this window yet. Rate a few answers in the chat window and they appear
            here immediately.
          </p>
        ) : (
          <SatisfactionDial ratio={digest.satisfaction} up={digest.up} down={digest.down} />
        )}
      </section>

      <section className="card" style={{ padding: '1.125rem' }}>
        <h3 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
          Lowest-rated topics
        </h3>
        <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
          Ranked by thumbs-down count — the queue for knowledge-base fixes
        </p>
        <RankedBars
          rows={digest.lowRated.map((r) => ({
            label: r.intentLabel,
            value: r.down,
            note:
              r.examples.length > 0
                ? `e.g. “${r.examples[0].query}”${
                    r.examples[0].comment ? ` — “${r.examples[0].comment}”` : ''
                  }`
                : undefined,
          }))}
          emptyNote="No negative ratings this week — nothing to action."
        />
      </section>

      <section className="card" style={{ padding: '1.125rem' }}>
        <h3 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
          Employee comments
        </h3>
        <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
          Anonymous — collected after a thumbs-down
        </p>
        {digest.comments.length === 0 ? (
          <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
            No written comments this week.
          </p>
        ) : (
          <ul style={{ display: 'grid', gap: '0.75rem', listStyle: 'none' }}>
            {digest.comments.map((c, i) => (
              <li
                key={i}
                style={{
                  padding: '0.75rem 0.875rem',
                  borderRadius: 'var(--radius)',
                  background: 'var(--surface-2)',
                  border: '1px solid var(--border)',
                }}
              >
                <p style={{ fontSize: '0.875rem', marginBottom: '0.3125rem' }}>“{c.comment}”</p>
                <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
                  asked: “{c.query}” ·{' '}
                  {new Date(c.createdAt).toLocaleDateString('en-IN', {
                    day: 'numeric',
                    month: 'short',
                  })}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Knowledge base
// ---------------------------------------------------------------------------

const CATEGORIES = [
  'Leave Management',
  'Payroll',
  'Benefits',
  'Compliance',
  'Workplace',
  'Onboarding',
  'Exit',
  'General',
];

function Knowledge({
  faqs,
  kb,
  ai,
  onChanged,
}: {
  faqs: FAQ[];
  kb: KnowledgeSnapshot | null;
  ai: AiStatus | null;
  onChanged: () => Promise<void>;
}) {
  const [category, setCategory] = useState('Leave Management');

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      <AiEnginePanel ai={ai} onChanged={onChanged} />

      <UploadZone category={category} onCategory={setCategory} onChanged={onChanged} />

      <AddEntryForm category={category} onCategory={setCategory} onChanged={onChanged} />

      <DocumentList docs={kb?.docs ?? []} onChanged={onChanged} />

      <BuiltInFaqs faqs={faqs} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// AI engine
// ---------------------------------------------------------------------------

function CapabilityBadge({ capability }: { capability: AiStatus['capability'] }) {
  const map = {
    generative: { cls: 'badge-success', label: 'Full AI · generating answers' },
    semantic: { cls: 'badge-info', label: 'Semantic search' },
    keyword: { cls: 'badge-warning', label: 'Keyword search only' },
  } as const;
  const { cls, label } = map[capability];

  // Icon plus word, never colour alone — matches the ticket badges elsewhere.
  return (
    <span className={`badge ${cls}`}>
      {capability === 'generative' ? <CheckCircleIcon size={11} /> : <AlertIcon size={11} />}
      {label}
    </span>
  );
}

/**
 * Status of the local AI backend, and the one command needed to fix it.
 *
 * This panel exists because the failure mode is invisible otherwise: without
 * Ollama the assistant still answers, just from keyword matches and quoted
 * passages. HR needs to know which mode they are actually in.
 */
function AiEnginePanel({
  ai,
  onChanged,
}: {
  ai: AiStatus | null;
  onChanged: () => Promise<void>;
}) {
  const [rebuilding, setRebuilding] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const rebuild = async () => {
    setRebuilding(true);
    setMessage(null);
    try {
      const result = await rebuildIndex();
      setMessage(result.message);
      await onChanged();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Rebuild failed.');
    } finally {
      setRebuilding(false);
    }
  };

  if (!ai) {
    return <div className="skeleton" style={{ height: 150, borderRadius: 'var(--radius)' }} />;
  }

  const { ollama, stats } = ai;

  return (
    <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '0.875rem' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: '1rem',
          flexWrap: 'wrap',
        }}
      >
        <div style={{ minWidth: 0 }}>
          <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
            AI engine
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', maxWidth: '62ch' }}>
            {ai.summary}
          </p>
        </div>
        <CapabilityBadge capability={ai.capability} />
      </div>

      <dl
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
          gap: '0.625rem 1rem',
          fontSize: '0.8125rem',
        }}
      >
        <div>
          <dt className="label-caps">Runtime</dt>
          <dd>{ollama.ok ? `Ollama · ${ollama.url}` : 'Not running'}</dd>
        </div>
        <div>
          <dt className="label-caps">Embedding model</dt>
          <dd>{ollama.embedModel ?? '—'}</dd>
        </div>
        <div>
          <dt className="label-caps">Answer model</dt>
          <dd>{ollama.chatModel ?? '—'}</dd>
        </div>
        <div>
          <dt className="label-caps">Index</dt>
          <dd>
            {stats.embeddedChunks}/{stats.chunkCount} passages embedded
          </dd>
        </div>
      </dl>

      {ollama.hint && (
        <div
          style={{
            fontSize: '0.8125rem',
            padding: '0.75rem 0.875rem',
            borderRadius: 'var(--radius-sm)',
            background: 'var(--warning-soft)',
            color: 'var(--warning-ink)',
            border: '1px solid var(--warning)',
          }}
        >
          <RichText text={ollama.hint} />
        </div>
      )}

      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <button
          className="btn btn-secondary btn-sm"
          onClick={rebuild}
          disabled={rebuilding || stats.chunkCount === 0}
        >
          <RefreshIcon size={14} />
          {rebuilding ? 'Rebuilding…' : 'Rebuild index'}
        </button>
        {stats.pendingChunks > 0 && (
          <span className="badge badge-warning">
            <AlertIcon size={11} />
            {stats.pendingChunks} passage{stats.pendingChunks === 1 ? '' : 's'} not embedded
          </span>
        )}
        {message && (
          <span style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>{message}</span>
        )}
      </div>

      <p style={{ fontSize: '0.75rem', color: 'var(--faint)', lineHeight: 1.6 }}>
        Everything runs on this machine — open-source models via Ollama, documents on local disk.
        No HR content is sent to any external service.
      </p>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Upload
// ---------------------------------------------------------------------------

function UploadZone({
  category,
  onCategory,
  onChanged,
}: {
  category: string;
  onCategory: (c: string) => void;
  onChanged: () => Promise<void>;
}) {
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<UploadOutcome[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const upload = async (files: File[]) => {
    const accepted = files.filter((f) => isAcceptedFile(f.name));
    const rejected = files.filter((f) => !isAcceptedFile(f.name));

    if (!accepted.length) {
      setError(
        `Nothing to upload — supported formats are ${ACCEPTED_EXTENSIONS.join(', ')}.`
      );
      setResults(null);
      return;
    }

    setBusy(true);
    setError(null);
    // Report unsupported files alongside the real results rather than dropping
    // them silently — HR would otherwise assume the .pdf went in.
    const skipped: UploadOutcome[] = rejected.map((f) => ({
      filename: f.name,
      ok: false,
      error: 'Unsupported format — convert to .docx, .txt, .md or .csv first.',
    }));

    try {
      const response = await uploadDocuments(accepted, category);
      setResults([...response.results, ...skipped]);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed.');
      setResults(skipped.length ? skipped : null);
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  };

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
        Upload HR documents
      </h2>
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
        Word (.docx), plain text (.txt), Markdown (.md) or spreadsheets exported as .csv. Each
        document is split into passages and indexed — employees can ask about it straight away,
        and there is no retraining step.
      </p>

      <label style={{ display: 'block', marginBottom: '0.875rem' }}>
        <span className="label-caps">Category</span>
        <select
          className="input"
          style={{ marginTop: '0.25rem', maxWidth: 280 }}
          value={category}
          onChange={(e) => onCategory(e.target.value)}
        >
          {CATEGORIES.map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
      </label>

      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          if (!busy) void upload(Array.from(e.dataTransfer.files));
        }}
        style={{
          border: '2px dashed',
          borderColor: dragging ? 'var(--primary)' : 'var(--border-strong)',
          background: dragging ? 'var(--primary-soft)' : 'var(--surface-2)',
          borderRadius: 'var(--radius)',
          padding: '1.75rem 1rem',
          textAlign: 'center',
          transition: 'border-color 0.16s, background 0.16s',
        }}
      >
        <span
          style={{
            display: 'inline-grid',
            placeItems: 'center',
            width: 40,
            height: 40,
            borderRadius: 12,
            background: 'var(--primary-soft)',
            color: 'var(--primary)',
            marginBottom: '0.625rem',
          }}
        >
          <UploadIcon size={20} />
        </span>

        <p style={{ fontSize: '0.875rem', fontWeight: 600, marginBottom: '0.25rem' }}>
          {busy ? 'Reading and indexing…' : 'Drop files here'}
        </p>
        <p
          style={{
            fontSize: '0.75rem',
            color: 'var(--muted-foreground)',
            marginBottom: '0.875rem',
          }}
        >
          {busy
            ? 'Embedding can take a few seconds per document on a local model.'
            : `${ACCEPTED_EXTENSIONS.join('  ·  ')}  ·  up to 10 MB each`}
        </p>

        <button
          type="button"
          className="btn btn-primary btn-sm"
          disabled={busy}
          onClick={() => inputRef.current?.click()}
        >
          {busy ? 'Working…' : 'Browse files'}
        </button>

        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPTED_EXTENSIONS.join(',')}
          className="sr-only"
          onChange={(e) => {
            const files = Array.from(e.target.files ?? []);
            if (files.length) void upload(files);
          }}
        />
      </div>

      {error && (
        <p
          style={{
            marginTop: '0.75rem',
            fontSize: '0.8125rem',
            color: 'var(--error-ink)',
          }}
        >
          {error}
        </p>
      )}

      {results && results.length > 0 && (
        <ul style={{ listStyle: 'none', display: 'grid', gap: '0.5rem', marginTop: '0.875rem' }}>
          {results.map((r) => (
            <li
              key={r.filename}
              style={{
                padding: '0.625rem 0.75rem',
                borderRadius: 'var(--radius-sm)',
                border: '1px solid',
                borderColor: r.ok ? 'var(--success)' : 'var(--error)',
                background: r.ok ? 'var(--success-soft)' : 'var(--error-soft)',
                fontSize: '0.8125rem',
              }}
            >
              <span
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.375rem',
                  fontWeight: 600,
                  color: r.ok ? 'var(--success-ink)' : 'var(--error-ink)',
                }}
              >
                {r.ok ? <CheckCircleIcon size={13} /> : <AlertIcon size={13} />}
                {r.filename}
              </span>

              <p style={{ marginTop: '0.25rem', color: 'var(--foreground-secondary)' }}>
                {r.ok
                  ? `Indexed as ${r.chunkCount} passage${r.chunkCount === 1 ? '' : 's'}` +
                    (r.embedded ? ' with semantic embeddings.' : ', keyword-searchable.')
                  : r.error}
              </p>

              {r.notes?.map((note) => (
                <p
                  key={note}
                  style={{ marginTop: '0.25rem', fontSize: '0.75rem', color: 'var(--faint)' }}
                >
                  {note}
                </p>
              ))}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Manual entry
// ---------------------------------------------------------------------------

function AddEntryForm({
  category,
  onCategory,
  onChanged,
}: {
  category: string;
  onCategory: (c: string) => void;
  onChanged: () => Promise<void>;
}) {
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!question.trim() || !answer.trim()) return;
    setBusy(true);
    setError(null);
    setStatus(null);
    try {
      await addEntry({ title: question.trim(), text: answer.trim(), category });
      setQuestion('');
      setAnswer('');
      setStatus('Added — employees can ask about it now.');
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save that entry.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
        Add an answer by hand
      </h2>
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
        For one-off answers that do not warrant a document. Stored and indexed exactly like an
        upload, so it is visible to every employee immediately.
      </p>

      <div style={{ display: 'grid', gap: '0.625rem' }}>
        <label>
          <span className="label-caps">Question</span>
          <input
            className="input"
            style={{ marginTop: '0.25rem' }}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="e.g. How do I claim relocation allowance?"
          />
        </label>
        <label>
          <span className="label-caps">Answer</span>
          <textarea
            className="input"
            style={{ marginTop: '0.25rem', minHeight: 90, resize: 'vertical' }}
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            placeholder="Supports **bold**, bullet lists and tables."
          />
        </label>
        <label>
          <span className="label-caps">Category</span>
          <select
            className="input"
            style={{ marginTop: '0.25rem' }}
            value={category}
            onChange={(e) => onCategory(e.target.value)}
          >
            {CATEGORIES.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <button
            className="btn btn-primary"
            onClick={submit}
            disabled={busy || !question.trim() || !answer.trim()}
          >
            <PlusIcon size={15} />
            {busy ? 'Saving…' : 'Add entry'}
          </button>
          {status && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--success-ink)' }}>{status}</span>
          )}
          {error && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--error-ink)' }}>{error}</span>
          )}
        </div>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Uploaded documents
// ---------------------------------------------------------------------------

function DocumentList({
  docs,
  onChanged,
}: {
  docs: KnowledgeDoc[];
  onChanged: () => Promise<void>;
}) {
  return (
    <section style={{ display: 'grid', gap: '0.75rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>
        {docs.length} document{docs.length === 1 ? '' : 's'} in the knowledge base
      </h2>

      {!docs.length && (
        <p className="card" style={{ padding: '1.5rem', color: 'var(--muted-foreground)' }}>
          Nothing uploaded yet. Drop a leave policy or a benefits FAQ above and the assistant will
          start answering from it.
        </p>
      )}

      {docs.map((doc) => (
        <DocumentRow key={doc.id} doc={doc} onChanged={onChanged} />
      ))}
    </section>
  );
}

function DocumentRow({ doc, onChanged }: { doc: KnowledgeDoc; onChanged: () => Promise<void> }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await deleteDocument(doc.id);
      await onChanged();
      setConfirming(false);
    } catch (err) {
      // Keep the confirm buttons up so the retry is one click away.
      setError(err instanceof Error ? err.message : 'Could not delete that document.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className="card" style={{ padding: '1rem' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: '0.75rem',
          alignItems: 'flex-start',
        }}
      >
        <div style={{ minWidth: 0 }}>
          <h3
            style={{
              fontSize: '0.875rem',
              fontWeight: 650,
              display: 'flex',
              alignItems: 'center',
              gap: '0.4375rem',
            }}
          >
            <span style={{ color: 'var(--muted-foreground)', display: 'flex' }}>
              <FileIcon size={14} />
            </span>
            {doc.title}
          </h3>

          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: '0.375rem',
              marginTop: '0.4375rem',
            }}
          >
            <span className="badge badge-neutral">{doc.category}</span>
            <span className="badge badge-neutral">{doc.kind.toUpperCase()}</span>
            <span className="badge badge-neutral">
              {doc.chunkCount} passage{doc.chunkCount === 1 ? '' : 's'}
            </span>
            {/* Embedding state is per document: one uploaded while Ollama was
                down stays keyword-only until the index is rebuilt. */}
            {doc.embeddingModel ? (
              <span className="badge badge-success">
                <CheckCircleIcon size={11} />
                Semantic
              </span>
            ) : (
              <span className="badge badge-warning">
                <AlertIcon size={11} />
                Keyword only
              </span>
            )}
          </div>
        </div>

        {/* Deleting a policy silently would be bad, so confirm in place. */}
        {confirming ? (
          <div style={{ display: 'flex', gap: '0.375rem', flexShrink: 0 }}>
            <button
              className="btn btn-sm"
              style={{ background: 'var(--error)', color: '#fff' }}
              onClick={remove}
              disabled={busy}
            >
              {busy ? 'Removing…' : 'Delete'}
            </button>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => setConfirming(false)}
              disabled={busy}
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => setConfirming(true)}
            aria-label={`Delete document: ${doc.title}`}
            style={{ color: 'var(--error-ink)', flexShrink: 0 }}
          >
            <TrashIcon size={14} />
          </button>
        )}
      </div>

      <p
        style={{
          fontSize: '0.8125rem',
          color: 'var(--foreground-secondary)',
          marginTop: '0.625rem',
        }}
      >
        {doc.preview}
        {doc.preview.length >= 240 ? '…' : ''}
      </p>

      {error && (
        <p style={{ fontSize: '0.8125rem', color: 'var(--error-ink)', marginTop: '0.5rem' }}>
          {error}
        </p>
      )}

      <p style={{ fontSize: '0.75rem', color: 'var(--faint)', marginTop: '0.5rem' }}>
        {doc.filename} · {formatBytes(doc.bytes)} · uploaded by {doc.uploadedBy} on{' '}
        {new Date(doc.uploadedAt).toLocaleDateString('en-IN', {
          day: 'numeric',
          month: 'short',
          year: 'numeric',
        })}
      </p>
    </article>
  );
}

// ---------------------------------------------------------------------------
// Built-in content
// ---------------------------------------------------------------------------

/**
 * The seeded FAQ set, shown read-only.
 *
 * These ship with the product and are matched by the offline NLU, not by
 * retrieval — separating them from uploads keeps it obvious which content HR
 * actually controls.
 */
function BuiltInFaqs({ faqs }: { faqs: FAQ[] }) {
  const [open, setOpen] = useState(false);

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <button
        onClick={() => setOpen((v) => !v)}
        style={{ display: 'block', textAlign: 'left', width: '100%' }}
        aria-expanded={open}
      >
        <h2 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>
          {faqs.length} recognised topics {open ? '▾' : '▸'}
        </h2>
        <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginTop: '0.25rem' }}>
          Phrasings the assistant recognises, so it can label a question and correct spelling. It
          does <strong>not</strong> answer from this list — every answer is generated from your
          uploaded documents and cited. A topic here with no document behind it gets escalated, not
          guessed at.
        </p>
      </button>

      {open && (
        <ul style={{ listStyle: 'none', display: 'grid', gap: '0.5rem', marginTop: '0.875rem' }}>
          {faqs.map((f) => (
            <li
              key={f.id}
              style={{
                padding: '0.625rem 0.75rem',
                borderRadius: 'var(--radius-sm)',
                background: 'var(--surface-2)',
                border: '1px solid var(--border)',
              }}
            >
              <p style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{f.question}</p>
              <p
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                  marginTop: '0.25rem',
                }}
              >
                {f.category}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Access (IAM)
// ---------------------------------------------------------------------------

/**
 * Shown to someone who is signed in but has no admin access.
 *
 * Deliberately a dead end with one way out, and deliberately not a redirect: a
 * bounce to /chat looks like a broken link and invites a second click. Naming the
 * account matters too — the usual cause is being signed in as the wrong one.
 */
function AccessDenied({ email }: { email: string | null }) {
  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'var(--background)',
        display: 'grid',
        placeItems: 'center',
        padding: '1.5rem',
      }}
    >
      <section className="card" style={{ padding: '2rem', maxWidth: 460, textAlign: 'center' }}>
        <div style={{ display: 'grid', placeItems: 'center', marginBottom: '1rem' }}>
          <div
            style={{
              width: 48,
              height: 48,
              borderRadius: 999,
              background: 'var(--error-soft)',
              display: 'grid',
              placeItems: 'center',
              color: 'var(--error)',
            }}
          >
            <ShieldIcon size={22} />
          </div>
        </div>

        <h1 style={{ fontSize: '1.125rem', fontWeight: 700, marginBottom: '0.5rem' }}>
          You don&apos;t have access to the HR admin console
        </h1>
        <p
          style={{
            fontSize: '0.875rem',
            color: 'var(--muted-foreground)',
            lineHeight: 1.6,
            marginBottom: '1.25rem',
          }}
        >
          {email ? (
            <>
              You&apos;re signed in as <strong>{email}</strong>, which doesn&apos;t hold a role that
              grants the console.
            </>
          ) : (
            <>Your account doesn&apos;t hold a role that grants the console.</>
          )}{' '}
          An existing HR admin can grant it under <strong>Access</strong>.
        </p>

        <div style={{ display: 'flex', gap: '0.625rem', justifyContent: 'center', flexWrap: 'wrap' }}>
          <a href="/chat" className="btn btn-primary">
            Back to the assistant
          </a>
        </div>
      </section>
    </div>
  );
}

/**
 * Roles and who holds them.
 *
 * One fetch pair feeds both panels because they are coupled: adding a role changes
 * the options in the user panel, and deleting one withdraws it from everybody. So
 * every mutation reloads both rather than patching local state and hoping the two
 * views agree.
 */
function Access() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<PermissionDefinition[]>([]);
  const [users, setUsers] = useState<IamUser[]>([]);
  const [breakGlass, setBreakGlass] = useState<string[]>([]);
  const [signedInAs, setSignedInAs] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError(null);
    try {
      const [roleData, userData] = await Promise.all([fetchRoles(), fetchIamUsers()]);
      setRoles(roleData.roles);
      setPermissions(roleData.permissions);
      setUsers(userData.users);
      setBreakGlass(userData.breakGlass);
      setSignedInAs(userData.signedInAs);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load access settings.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (loading) {
    return (
      <div style={{ display: 'grid', gap: '1rem' }}>
        <div className="skeleton" style={{ height: 180 }} />
        <div className="skeleton" style={{ height: 240 }} />
      </div>
    );
  }

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      {error && (
        <div
          className="card"
          style={{
            padding: '0.875rem 1rem',
            borderColor: 'var(--error)',
            background: 'var(--error-soft)',
            fontSize: '0.8125rem',
          }}
        >
          <strong style={{ color: 'var(--error)' }}>Access settings unavailable.</strong> {error}
        </div>
      )}

      <UsersPanel
        users={users}
        roles={roles}
        breakGlass={breakGlass}
        signedInAs={signedInAs}
        onChanged={reload}
      />
      <RolesPanel roles={roles} permissions={permissions} onChanged={reload} />
    </div>
  );
}

/** Who holds what. */
function UsersPanel({
  users,
  roles,
  breakGlass,
  signedInAs,
  onChanged,
}: {
  users: IamUser[];
  roles: Role[];
  breakGlass: string[];
  signedInAs: string | null;
  onChanged: () => Promise<void>;
}) {
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const adminRoles = new Set(
    roles.filter((r) => r.permissions.includes(ADMIN_CONSOLE)).map((r) => r.name)
  );

  const add = async () => {
    if (!email.trim()) return;
    setBusy(true);
    setError(null);
    setStatus(null);
    try {
      await addIamUser({ email: email.trim(), name: name.trim(), roles: selected });
      setEmail('');
      setName('');
      setSelected([]);
      setStatus('Saved. Access applies on their next request — no re-sign-in needed.');
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save that person.');
    } finally {
      setBusy(false);
    }
  };

  const changeRoles = async (target: IamUser, roleName: string, checked: boolean) => {
    const next = checked
      ? [...target.roles, roleName]
      : target.roles.filter((r) => r !== roleName);
    setError(null);
    try {
      await setIamUserRoles(target.email, next);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change those roles.');
    }
  };

  const remove = async (target: IamUser) => {
    setError(null);
    try {
      await removeIamUser(target.email);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not remove that person.');
    }
  };

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
        People and access
      </h2>
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
        Microsoft Entra decides who can sign in; this decides what they can reach once they have.
        Adding someone here grants nobody a way in, and anyone signing in without a record is a
        normal employee.
      </p>

      {breakGlass.length > 0 && (
        <div
          style={{
            padding: '0.75rem 0.875rem',
            marginBottom: '1rem',
            borderRadius: 8,
            background: 'var(--surface-2)',
            fontSize: '0.8125rem',
            color: 'var(--muted-foreground)',
            lineHeight: 1.55,
          }}
        >
          <strong style={{ color: 'var(--foreground-secondary)' }}>
            Also admin, from configuration:
          </strong>{' '}
          {breakGlass.join(', ')}. These come from <code>hr.auth.admin-emails</code> and cannot be
          revoked here — that is deliberate, so an empty list below can never lock everyone out.
        </div>
      )}

      <div style={{ display: 'grid', gap: '0.625rem', marginBottom: '1.25rem' }}>
        <div style={{ display: 'flex', gap: '0.625rem', flexWrap: 'wrap' }}>
          <label style={{ flex: '1 1 220px' }}>
            <span className="label-caps">Work email</span>
            <input
              className="input"
              style={{ marginTop: '0.25rem' }}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="someone@leadsquared.com"
            />
          </label>
          <label style={{ flex: '1 1 180px' }}>
            <span className="label-caps">Name (optional)</span>
            <input
              className="input"
              style={{ marginTop: '0.25rem' }}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Ananya Sharma"
            />
          </label>
        </div>

        <div>
          <span className="label-caps">Roles</span>
          <div
            style={{
              display: 'flex',
              gap: '0.5rem',
              flexWrap: 'wrap',
              marginTop: '0.375rem',
            }}
          >
            {roles.map((role) => {
              const checked = selected.includes(role.name);
              return (
                <label
                  key={role.name}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '0.375rem',
                    padding: '0.3125rem 0.625rem',
                    borderRadius: 999,
                    border: '1px solid',
                    borderColor: checked ? 'var(--primary)' : 'var(--border)',
                    background: checked ? 'var(--primary-soft)' : 'transparent',
                    fontSize: '0.8125rem',
                    cursor: 'pointer',
                  }}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...selected, role.name]
                          : selected.filter((r) => r !== role.name)
                      )
                    }
                  />
                  {role.label}
                  {adminRoles.has(role.name) && (
                    <ShieldIcon size={12} aria-label="grants the admin console" />
                  )}
                </label>
              );
            })}
          </div>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <button className="btn btn-primary" onClick={add} disabled={busy || !email.trim()}>
            <PlusIcon size={15} />
            {busy ? 'Saving…' : 'Add person'}
          </button>
          {status && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--success-ink)' }}>{status}</span>
          )}
          {error && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--error-ink)' }}>{error}</span>
          )}
        </div>
      </div>

      {users.length === 0 ? (
        <p style={{ fontSize: '0.8125rem', color: 'var(--faint)' }}>
          Nobody has been given a role yet. Everyone who signs in is an employee.
        </p>
      ) : (
        <div style={{ display: 'grid', gap: '0.5rem' }}>
          {users.map((person) => {
            const isSelf = signedInAs != null && signedInAs.toLowerCase() === person.email;
            const isAdmin = person.roles.some((r) => adminRoles.has(r));

            return (
              <div
                key={person.email}
                style={{
                  padding: '0.75rem 0.875rem',
                  borderRadius: 8,
                  border: '1px solid var(--border)',
                  display: 'grid',
                  gap: '0.5rem',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: '0.75rem',
                    flexWrap: 'wrap',
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: '0.875rem', fontWeight: 600 }}>
                      {person.name}
                      {isSelf && (
                        <span style={{ color: 'var(--faint)', fontWeight: 400 }}> · you</span>
                      )}
                    </div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
                      {person.email}
                    </div>
                  </div>

                  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                    <span
                      style={{
                        fontSize: '0.6875rem',
                        fontWeight: 600,
                        padding: '0.125rem 0.5rem',
                        borderRadius: 999,
                        background: isAdmin ? 'var(--primary-soft)' : 'var(--surface-3)',
                        color: isAdmin ? 'var(--primary)' : 'var(--muted-foreground)',
                      }}
                    >
                      {isAdmin ? 'HR admin' : 'Employee'}
                    </span>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => remove(person)}
                      disabled={isSelf}
                      title={
                        isSelf
                          ? 'Another admin has to remove your own access, so you cannot lock yourself out'
                          : `Remove ${person.email}`
                      }
                    >
                      <TrashIcon size={14} />
                    </button>
                  </div>
                </div>

                <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                  {roles.map((role) => {
                    const held = person.roles.includes(role.name);
                    return (
                      <label
                        key={role.name}
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: '0.3125rem',
                          fontSize: '0.75rem',
                          color: held ? 'var(--foreground-secondary)' : 'var(--muted-foreground)',
                          cursor: 'pointer',
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={held}
                          onChange={(e) => changeRoles(person, role.name, e.target.checked)}
                        />
                        {role.label}
                      </label>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

/** Role definitions, and what each one permits. */
function RolesPanel({
  roles,
  permissions,
  onChanged,
}: {
  roles: Role[];
  permissions: PermissionDefinition[];
  onChanged: () => Promise<void>;
}) {
  const [label, setLabel] = useState('');
  const [description, setDescription] = useState('');
  const [granted, setGranted] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Shown live, because the name is what assignments key on and it is the one
  // thing about a role that cannot be changed afterwards.
  const name = slugify(label);

  const create = async () => {
    if (!label.trim()) return;
    setBusy(true);
    setError(null);
    setStatus(null);
    try {
      await createRole({
        name,
        label: label.trim(),
        description: description.trim(),
        permissions: granted,
      });
      setLabel('');
      setDescription('');
      setGranted([]);
      setStatus('Role created.');
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create that role.');
    } finally {
      setBusy(false);
    }
  };

  const togglePermission = async (role: Role, key: string, checked: boolean) => {
    setError(null);
    try {
      await updateRole(role.name, {
        label: role.label,
        description: role.description,
        permissions: checked
          ? [...role.permissions, key]
          : role.permissions.filter((p) => p !== key),
      });
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change that permission.');
    }
  };

  const remove = async (role: Role) => {
    setError(null);
    try {
      await deleteRole(role.name);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete that role.');
    }
  };

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>Roles</h2>
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
        A role is a named set of permissions. Built-in roles cannot be deleted and their
        permissions are fixed — <strong>HR admin</strong> losing console access would leave nobody
        able to hand it back out.
      </p>

      <div style={{ display: 'grid', gap: '0.5rem', marginBottom: '1.25rem' }}>
        {roles.map((role) => (
          <div
            key={role.name}
            style={{
              padding: '0.75rem 0.875rem',
              borderRadius: 8,
              border: '1px solid var(--border)',
              display: 'grid',
              gap: '0.5rem',
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: '0.75rem',
                flexWrap: 'wrap',
              }}
            >
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: '0.875rem', fontWeight: 600 }}>
                  {role.label}
                  {role.builtIn && (
                    <span
                      style={{
                        marginLeft: '0.5rem',
                        fontSize: '0.6875rem',
                        fontWeight: 600,
                        padding: '0.125rem 0.4375rem',
                        borderRadius: 999,
                        background: 'var(--surface-3)',
                        color: 'var(--muted-foreground)',
                      }}
                    >
                      built-in
                    </span>
                  )}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
                  <code>{role.name}</code>
                  {role.description ? ` · ${role.description}` : ''}
                </div>
              </div>

              {!role.builtIn && (
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => remove(role)}
                  title={`Delete ${role.label} and withdraw it from everyone holding it`}
                >
                  <TrashIcon size={14} />
                </button>
              )}
            </div>

            <div style={{ display: 'flex', gap: '0.875rem', flexWrap: 'wrap' }}>
              {permissions.map((permission) => {
                const held = role.permissions.includes(permission.key);
                return (
                  <label
                    key={permission.key}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '0.375rem',
                      fontSize: '0.75rem',
                      color: held ? 'var(--foreground-secondary)' : 'var(--muted-foreground)',
                      cursor: role.builtIn ? 'not-allowed' : 'pointer',
                      opacity: role.builtIn ? 0.7 : 1,
                    }}
                    title={permission.description}
                  >
                    <input
                      type="checkbox"
                      checked={held}
                      disabled={role.builtIn}
                      onChange={(e) => togglePermission(role, permission.key, e.target.checked)}
                    />
                    {permission.label}
                  </label>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gap: '0.625rem', borderTop: '1px solid var(--border)', paddingTop: '1rem' }}>
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>Add a role</h3>

        <div style={{ display: 'flex', gap: '0.625rem', flexWrap: 'wrap' }}>
          <label style={{ flex: '1 1 200px' }}>
            <span className="label-caps">Name</span>
            <input
              className="input"
              style={{ marginTop: '0.25rem' }}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="e.g. Payroll viewer"
            />
            {name && (
              <span style={{ fontSize: '0.6875rem', color: 'var(--faint)' }}>
                Saved as <code>{name}</code> — permanent, since assignments reference it.
              </span>
            )}
          </label>
          <label style={{ flex: '1 1 240px' }}>
            <span className="label-caps">Description (optional)</span>
            <input
              className="input"
              style={{ marginTop: '0.25rem' }}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Who this is for"
            />
          </label>
        </div>

        <div>
          <span className="label-caps">Permissions</span>
          <div style={{ display: 'grid', gap: '0.375rem', marginTop: '0.375rem' }}>
            {permissions.map((permission) => (
              <label
                key={permission.key}
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '0.5rem',
                  fontSize: '0.8125rem',
                  cursor: 'pointer',
                }}
              >
                <input
                  type="checkbox"
                  style={{ marginTop: '0.1875rem' }}
                  checked={granted.includes(permission.key)}
                  onChange={(e) =>
                    setGranted(
                      e.target.checked
                        ? [...granted, permission.key]
                        : granted.filter((p) => p !== permission.key)
                    )
                  }
                />
                <span>
                  {permission.label}
                  <span
                    style={{
                      display: 'block',
                      fontSize: '0.75rem',
                      color: 'var(--muted-foreground)',
                    }}
                  >
                    {permission.description}
                  </span>
                </span>
              </label>
            ))}
          </div>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <button className="btn btn-primary" onClick={create} disabled={busy || !name}>
            <PlusIcon size={15} />
            {busy ? 'Creating…' : 'Create role'}
          </button>
          {status && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--success-ink)' }}>{status}</span>
          )}
          {error && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--error-ink)' }}>{error}</span>
          )}
        </div>
      </div>
    </section>
  );
}
