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
  fetchEmployeeDataStatus,
  importEmployeeExtract,
  type EmployeeDataStatus,
  type EmployeeImportReport,
} from '@/lib/knowledge/api';
import type { KnowledgeDoc } from '@/lib/knowledge/types';
import { fetchAdminMetrics, postTicketComment, type AdminMetrics } from '@/lib/hr-api';
import {
  ROUTE_LABELS,
  buildWeeklyDigest,
  getFeedback,
  getTickets,
  hydrate,
  subscribe,
  updateTicketStatus,
  type Ticket,
  type TicketComment,
  type TicketStatus,
} from '@/lib/hr-store';
import {
  ADMIN_ACCESS,
  ADMIN_AUDIT,
  ADMIN_DIGEST,
  ADMIN_KNOWLEDGE,
  ADMIN_PAYROLL,
  ADMIN_TICKETS,
  useChatbotAuth,
} from '@/lib/chatbot-auth';
import { useTheme } from '@/lib/theme';
import {
  bandFor,
  fetchVariablePayPlan,
  saveVariablePayPlan,
  type Band,
  type VariablePayPlan,
} from '@/lib/variable-pay-api';
import {
  fetchFunctionPayPlans,
  saveFunctionPayPlan,
  slabKindLabel,
  type FunctionPayPlan,
} from '@/lib/function-pay-api';
import {
  ADMIN_CONSOLE,
  addIamUser,
  createRole,
  deleteRole,
  fetchAudit,
  fetchIamUsers,
  fetchLogins,
  fetchRoles,
  removeIamUser,
  setIamUserRoles,
  slugify,
  updateRole,
  AUDIT_ACTION_LABELS,
  type AuditEvent,
  type IamUser,
  type LoginRecord,
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
  ChatIcon,
  CheckCircleIcon,
  ClockIcon,
  FileIcon,
  LogoutIcon,
  MenuIcon,
  MoonIcon,
  PlusIcon,
  RefreshIcon,
  ShieldIcon,
  SparkIcon,
  SunIcon,
  TicketIcon,
  TrashIcon,
  XIcon,
  UploadIcon,
} from '@/components/Icons';

type Tab =
  | 'overview'
  | 'tickets'
  | 'digest'
  | 'knowledge'
  | 'variablepay'
  | 'access'
  | 'audit'
  | 'signins';

/**
 * `requires` names a permission the session must hold for the tab to appear.
 *
 * Presentation only — every endpoint behind these is gated server-side by `PermissionAccess` (or,
 * for escalations, per row inside `TicketController`) and re-decided per request. Hiding a tab
 * keeps someone from clicking into a guaranteed 403; it is not what stops them reading it.
 *
 * Overview carries none: reaching the console at all already means holding `admin.console`, and a
 * landing page that refuses to render would make a valid grant look broken.
 */
const TABS: { id: Tab; label: string; requires?: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'tickets', label: 'Escalations', requires: ADMIN_TICKETS },
  { id: 'digest', label: 'Weekly digest', requires: ADMIN_DIGEST },
  { id: 'knowledge', label: 'Knowledge base', requires: ADMIN_KNOWLEDGE },
  { id: 'variablepay', label: 'Variable pay', requires: ADMIN_PAYROLL },
  { id: 'access', label: 'Access', requires: ADMIN_ACCESS },
  { id: 'audit', label: 'Audit trail', requires: ADMIN_AUDIT },
  { id: 'signins', label: 'Sign-ins', requires: ADMIN_AUDIT },
];

/**
 * How wide the console is allowed to get.
 *
 * 1180 left about 370px of dead margin each side on a 1920 display — most of the screens this is
 * actually used on — while the tables and the escalation list were the things being squeezed. It
 * is still a cap rather than full width: prose needs a measure, and the paragraphs below carry
 * their own `maxWidth` so widening the shell cannot stretch a sentence to 200 characters.
 *
 * One constant because the header, the tab strip and the main column have to agree; they were
 * three separate copies of the same number.
 */
const SHELL_MAX = 1560;

/** Gutter that grows with the viewport, so wide screens are not edge-to-edge. */
const SHELL_PAD = 'clamp(1rem, 2.5vw, 2.25rem)';

/*
 * The weekday volume fixture that used to live here is gone. It summed to 2,585
 * conversations a week on a deployment whose real figure is around twenty, and the
 * "resolved without HR" tile divided the real ticket count by it — so a decorative
 * number was the denominator of a percentage HR was invited to read as a result.
 *
 * Volume now comes from GET /api/admin/metrics, which counts employee messages in
 * the `threads` collection. See ConversationMetricsService.
 */

export default function AdminPage() {
  const router = useRouter();
  const { user, isLoading: authLoading, ssoEnabled, permissions, logout } = useChatbotAuth();

  // Tabs this session is allowed to see. A tab with no `requires` is open to any admin.
  const visibleTabs = TABS.filter((t) => !t.requires || permissions.includes(t.requires));

  // The escalation queue holds confidential matters, so this page is admin-only.
  // The backend enforces it too — every /api route behind this console is gated by
  // AdminConsoleAccess, which re-decides per request — so this guard is about
  // telling someone why they cannot be here, not about keeping them out.
  //
  // Signed out is a redirect, because there is a page that fixes it. Signed in
  // without access is not: bouncing someone to /chat reads as a broken link, and
  // they would keep clicking it. Say it plainly instead.
  //
  // The `!ssoEnabled` bail-out is gone: it meant that wherever Entra was not configured, an
  // unsigned visitor got the console instead of the login page. The role check below stays
  // gated on ssoEnabled, because without a configured identity provider there is no role to
  // check against — but "no session at all" is answerable in every posture.
  useEffect(() => {
    if (authLoading) return;
    if (!user) router.replace('/login');
  }, [authLoading, user, router]);

  const accessDenied = !authLoading && ssoEnabled && !!user && user.role !== 'hr_admin';

  const [tab, setTab] = useState<Tab>('overview');
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [faqs] = useState<FAQ[]>(DEFAULT_FAQS);
  const { theme, toggleTheme } = useTheme();
  const [booted, setBooted] = useState(false);
  const [version, setVersion] = useState(0);
  /** Set when the ticket store could not be reached — shown as a banner. */
  const [storeError, setStoreError] = useState<string | null>(null);
  /** Mobile only — the tab strip becomes a drawer under 861px. See globals.css. */
  const [navOpen, setNavOpen] = useState(false);

  // The uploaded knowledge base and the local AI engine both live server-side.
  const [kb, setKb] = useState<KnowledgeSnapshot | null>(null);
  const [ai, setAi] = useState<AiStatus | null>(null);

  const canKnowledge = permissions.includes(ADMIN_KNOWLEDGE);

  const refreshKnowledge = useCallback(async () => {
    // Not a permission check that protects anything — the endpoint refuses on its own. It stops
    // this page issuing a request it knows will 403 on every load for someone who was granted
    // the escalation queue and nothing else, and stops the overview reporting "0 documents" when
    // the truth is "not yours to see".
    if (!canKnowledge) return;

    // Settled rather than all: a stalled Ollama probe must not blank out the
    // document list, which is served from disk and always available.
    const [snapshot, status] = await Promise.allSettled([fetchKnowledge(), fetchAiStatus()]);
    if (snapshot.status === 'fulfilled') setKb(snapshot.value);
    if (status.status === 'fulfilled') setAi(status.value);
  }, [canKnowledge]);

  useEffect(() => {
    void refreshKnowledge();
  }, [refreshKnowledge]);

  /*
   * Drawer behaviour: Escape closes it, and the page behind it does not scroll.
   *
   * Both are what makes an overlay feel like a drawer rather than a floating panel —
   * without the scroll lock a swipe on the scrim scrolls the dashboard underneath,
   * which reads as the drawer being stuck to a moving page. Restores the previous
   * overflow rather than clearing it, so this cannot fight another lock.
   */
  useEffect(() => {
    if (!navOpen) return;

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setNavOpen(false);
    };
    window.addEventListener('keydown', onKey);

    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previous;
    };
  }, [navOpen]);

  /*
   * A drawer left open across a resize would sit over the desktop layout with no
   * hamburger to close it — the button is `only-mobile`. Closing on the same 860px
   * boundary the CSS uses keeps the two from disagreeing.
   */
  useEffect(() => {
    if (!navOpen) return;
    const wide = window.matchMedia('(min-width: 861px)');
    const onChange = () => {
      if (wide.matches) setNavOpen(false);
    };
    wide.addEventListener('change', onChange);
    return () => wide.removeEventListener('change', onChange);
  }, [navOpen]);

  // Chat and this dashboard share one store, so re-read on any change.
  useEffect(() => {
    const load = () => {
      setTickets(getTickets());
      setVersion((v) => v + 1);
    };

    const unsubscribe = subscribe(load);

    /*
     * Tickets and feedback come from MongoDB via the backend. An admin looking at
     * three empty panels must be able to tell "a quiet week" from "the store is
     * down", so a real failure is shown rather than logged.
     *
     * What is NOT shown is a withheld permission. `/api/tickets` needs admin.tickets
     * and `/api/feedback` needs admin.digest, so a role without one of them gets a 403
     * that is the correct answer, not a fault. Two things follow: only ask for what
     * this session may have, and never turn the other resource's 403 into a banner —
     * that is how removing the digest permission came to report the ticket store as
     * unavailable on every HR page.
     */
    void hydrate({
      tickets: permissions.includes(ADMIN_TICKETS),
      feedback: permissions.includes(ADMIN_DIGEST),
    })
      .then((result) => {
        // Only genuine failures. 'forbidden' is a decision someone made in the console.
        const broken = [result.ticketError, result.feedbackError].filter(Boolean);
        setStoreError(broken.length ? broken.join(' ') : null);
      })
      .finally(() => {
        load();
        setBooted(true);
      });

    return unsubscribe;
    // Re-runs when permissions arrive: they are empty on the first render, before
    // /api/auth/me has answered, and hydrating on that would ask for nothing.
  }, [permissions]);

  const digest = useMemo(() => (booted ? buildWeeklyDigest(7) : null), [booted, version]);
  const feedbackCount = useMemo(() => (booted ? getFeedback().length : 0), [booted, version]);

  /*
   * Conversation volume, from the server. Null while in flight and on failure —
   * the tiles render "—" rather than a zero, because a zero here would read as
   * "nobody used the assistant this week" when the truth is "we could not ask".
   *
   * Server-side because it aggregates across every employee's threads, which no
   * browser is allowed to enumerate; the endpoint returns counts only.
   */
  const [metrics, setMetrics] = useState<AdminMetrics | null>(null);
  const [metricsError, setMetricsError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    fetchAdminMetrics(7)
      .then((m) => {
        if (live) setMetrics(m);
      })
      .catch((err: unknown) => {
        if (live) setMetricsError(err instanceof Error ? err.message : 'Could not load metrics.');
      });
    return () => {
      live = false;
    };
  }, [version]);

  const openTickets = tickets.filter((t) => t.status !== 'resolved');
  const criticalOpen = openTickets.filter((t) => t.priority === 'critical');

  // After every hook, so the early return cannot change the hook order.
  //
  // Nothing renders without a session: the effect above is on its way to /login, and the
  // console is exactly the page that must not appear for an instant first.
  if (authLoading || !user) return null;

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
            maxWidth: SHELL_MAX,
            margin: '0 auto',
            padding: `0.875rem ${SHELL_PAD}`,
            display: 'flex',
            alignItems: 'center',
            gap: '0.75rem',
            flexWrap: 'wrap',
          }}
        >
          <button
            className="btn btn-ghost only-mobile"
            style={{ padding: '0.4375rem', marginLeft: '-0.25rem' }}
            onClick={() => setNavOpen(true)}
            aria-label="Open menu"
            aria-expanded={navOpen}
            aria-controls="admin-nav"
          >
            <MenuIcon size={18} />
          </button>

          <RobinAvatar size={34} rounded="badge" ring />
          <div style={{ minWidth: 0 }}>
            <h1
              style={{
                fontSize: '1rem',
                fontWeight: 700,
                lineHeight: 1.25,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              Robin · HR Admin Console
            </h1>
            <p
              className="hide-mobile"
              style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}
            >
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
              className="btn btn-ghost hide-mobile"
              style={{ padding: '0.4375rem' }}
              onClick={toggleTheme}
              aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            >
              {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
            </button>
            {/* Icon-only, to sit as a peer of the theme and sign-out buttons rather than a
                text button wedged between them. The label survives for anyone not reading
                shapes — the drawer keeps the full wording, where there is room for it. */}
            <a
              href="/chat"
              className="btn btn-ghost hide-mobile"
              style={{ padding: '0.4375rem' }}
              aria-label="Open chat"
              title="Open chat"
            >
              <ChatIcon size={16} />
            </a>
            {/*
              Who this is. An admin console that can revoke someone's access should say
              whose session is doing it — and it is the label the sign-out button needs
              to be unambiguous. Hidden on a phone, where the header has no room for an
              address; the button keeps its own aria-label there.
            */}
            {user?.email && (
              <span
                className="hide-mobile"
                title={user.email}
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                  maxWidth: '13rem',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {user.email}
              </span>
            )}
            <button
              className="btn btn-ghost"
              // error-ink rather than the raw error token: it stays legible on both
              // themes' surfaces, and the icon inherits it through currentColor. Same
              // treatment as the chat sidebar's sign-out.
              style={{ padding: '0.4375rem', color: 'var(--error-ink)' }}
              aria-label="Sign out"
              title="Sign out"
              onClick={() => {
                // A full redirect, not a router push: the session cookie is gone, so
                // every cached page behind this one is stale.
                void logout().then(() => {
                  window.location.href = '/login';
                });
              }}
            >
              <LogoutIcon size={16} />
            </button>
          </div>
        </div>

        {/* Tap-away close. only-mobile because the drawer itself does not exist above 860px. */}
        {navOpen && (
          <div className="sidebar-scrim only-mobile" onClick={() => setNavOpen(false)} />
        )}

        <nav
          id="admin-nav"
          // Width and padding come from .admin-nav in globals.css, not from here: the
          // drawer variant overrides both, and an inline style would outrank it.
          className={`admin-nav scroll-slim${navOpen ? ' admin-nav--open' : ''}`}
        >
          {/*
            The drawer overlays the header, so the hamburger that opened it is underneath.
            Escape and the scrim both close it, but neither is discoverable by looking —
            hence a visible close on the panel itself.
          */}
          <div
            className="only-mobile"
            style={{
              display: navOpen ? 'flex' : 'none',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '0.25rem 0.75rem 0.5rem',
            }}
          >
            <span
              style={{
                fontSize: '0.6875rem',
                fontWeight: 700,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'var(--muted-foreground)',
              }}
            >
              Sections
            </span>
            <button
              className="btn btn-ghost"
              style={{ padding: '0.375rem' }}
              onClick={() => setNavOpen(false)}
              aria-label="Close menu"
            >
              <XIcon size={16} />
            </button>
          </div>

          {visibleTabs.map((t) => {
            const isActive = tab === t.id;
            return (
              <button
                key={t.id}
                className="admin-tab"
                onClick={() => {
                  setTab(t.id);
                  // Picking a tab is the drawer's whole purpose; leaving it open
                  // would cover the thing just chosen.
                  setNavOpen(false);
                }}
                aria-current={isActive ? 'page' : undefined}
              >
                {t.label}
                {t.id === 'tickets' && openTickets.length > 0 && (
                  <span
                    style={{
                      fontSize: '0.6875rem',
                      background: isActive ? 'var(--primary)' : 'var(--surface-3)',
                      color: isActive ? '#fff' : 'var(--muted-foreground)',
                      padding: '0.0625rem 0.375rem',
                      borderRadius: 999,
                      marginLeft: 'auto',
                    }}
                  >
                    {openTickets.length}
                  </span>
                )}
              </button>
            );
          })}

          {/*
            The controls the phone header has no room for. Rendered inside the drawer and
            hidden above 860px, where they live in the header instead — so "Open chat" is
            a full-width action here rather than a button squeezed against the title.
          */}
          <div className="admin-drawer-extras">
            {user?.email && (
              <span
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {user.email}
              </span>
            )}
            <a
              href="/chat"
              className="btn btn-secondary btn-sm"
              style={{ width: '100%' }}
            >
              Open chat
            </a>
            <button
              className="btn btn-ghost btn-sm"
              style={{ width: '100%' }}
              onClick={toggleTheme}
            >
              {theme === 'dark' ? <SunIcon size={15} /> : <MoonIcon size={15} />}
              {theme === 'dark' ? 'Light theme' : 'Dark theme'}
            </button>
          </div>
        </nav>
      </header>

      <main
        style={{
          maxWidth: SHELL_MAX,
          margin: '0 auto',
          padding: `1.5rem ${SHELL_PAD} 3rem`,
        }}
      >
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
                <strong style={{ color: 'var(--error)' }}>Store unavailable.</strong>{' '}
                {storeError} What it covers is not being shown — this is not an empty queue. Other
                sections are unaffected.
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
                metrics={metrics}
                metricsError={metricsError}
              />
            )}
            {tab === 'tickets' && <Tickets tickets={tickets} />}
            {tab === 'digest' && <Digest digest={digest} />}
            {tab === 'knowledge' && (
              <Knowledge faqs={faqs} kb={kb} ai={ai} onChanged={refreshKnowledge} />
            )}
            {tab === 'variablepay' && (
              <div style={{ display: 'grid', gap: '1rem' }}>
                <VariablePayPlanPanel />
                <FunctionPayPanel />
              </div>
            )}
            {tab === 'access' && <Access />}
            {tab === 'audit' && <AuditTrail />}
            {tab === 'signins' && <SignIns />}
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
  metrics,
  metricsError,
}: {
  digest: ReturnType<typeof buildWeeklyDigest> | null;
  openTickets: Ticket[];
  totalTickets: number;
  feedbackCount: number;
  faqCount: number;
  ai: AiStatus | null;
  metrics: AdminMetrics | null;
  metricsError: string | null;
}) {
  // Em dash, not 0, for every measured value that is not in yet. See the metrics
  // fetch in AdminPage for why the difference is worth carrying this far.
  const DASH = '—';

  const volume = (metrics?.daily ?? []).map((d) => ({ label: d.label, value: d.questions }));

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      <section
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(190px, 100%), 1fr))',
          gap: '0.875rem',
        }}
      >
        <StatTile
          label="Conversations this week"
          value={metrics ? metrics.conversations.toLocaleString() : DASH}
          // Absent when there is no prior week to compare against, rather than
          // asserting a change of 0% we have not measured.
          delta={
            metrics?.deltaPct != null
              ? `${Math.abs(metrics.deltaPct).toFixed(1)}% vs last week`
              : undefined
          }
          deltaGood={(metrics?.deltaPct ?? 0) >= 0}
          note={
            metrics
              ? `${metrics.questions.toLocaleString()} questions asked`
              : (metricsError ?? 'Loading…')
          }
          icon={<SparkIcon size={15} />}
        />
        <StatTile
          label="Resolved without HR"
          // Both sides of this ratio are now the same seven days. It used to divide
          // the all-time ticket count by a hardcoded weekly volume, which is why it
          // sat at 99% regardless of what the assistant actually did.
          value={
            metrics?.resolvedWithoutHr != null
              ? `${Math.round(metrics.resolvedWithoutHr)}%`
              : DASH
          }
          note={
            metrics
              ? `${metrics.escalations} of ${metrics.conversations} needed a human`
              : 'Answered by the assistant alone'
          }
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
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(300px, 100%), 1fr))',
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
            {metrics ? ` · ${metrics.timezone}` : ''}
          </p>
          {/*
            * An explicit empty state rather than a chart of seven zero-height bars,
            * which looks like a rendering fault rather than a quiet week.
            */}
          {metrics && volume.some((d) => d.value > 0) ? (
            <BarChart data={volume} />
          ) : (
            <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
              {metricsError
                ? `Volume unavailable — ${metricsError}`
                : metrics
                  ? 'No questions asked in the last 7 days.'
                  : 'Loading…'}
            </p>
          )}
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

      {/*
        Two columns once there is room for them. A single stack of full-width rows was the worst
        use of a wide screen here: an escalation card is about 500px of content, so at 1560 each
        row was mostly empty and the queue needed twice the scrolling to read.
      */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(min(480px, 100%), 1fr))',
          gap: '1rem',
          alignItems: 'start',
        }}
      >
        {rows.map((t) => (
          <TicketRow key={t.id} ticket={t} />
        ))}
      </div>
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
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(160px, 100%), 1fr))',
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
        {/* Channel intentionally not shown — see DataCards. Still stored on the ticket. */}
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

      <ReplyThread ticket={ticket} />
    </article>
  );
}

/**
 * The conversation with the employee, and the box to add to it.
 *
 * <p>Worth saying plainly in the UI that this reaches them: HR writing here is
 * writing to the person, on a page that person reads. Treating it as an internal
 * triage note would be a bad surprise for whoever assumed nobody was looking.
 */
function ReplyThread({ ticket }: { ticket: Ticket }) {
  const [thread, setThread] = useState<TicketComment[]>(ticket.comments ?? []);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    const text = draft.trim();
    if (!text) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await postTicketComment(ticket.id, text);
      setThread(updated.comments ?? []);
      setDraft('');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not post that reply.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ marginTop: '0.875rem', borderTop: '1px solid var(--border)', paddingTop: '0.875rem' }}>
      <p className="label-caps" style={{ marginBottom: '0.625rem' }}>
        {thread.length === 0 ? 'Reply to the employee' : `Thread · ${thread.length}`}
      </p>

      {thread.length > 0 && (
        <div style={{ display: 'grid', gap: '0.5rem', marginBottom: '0.75rem' }}>
          {thread.map((c) => {
            const fromHr = c.authorRole === 'hr';
            return (
              <div
                key={c.id}
                style={{
                  padding: '0.625rem 0.75rem',
                  borderRadius: 8,
                  background: fromHr ? 'var(--primary-soft)' : 'var(--surface-2)',
                  borderLeft: `3px solid ${fromHr ? 'var(--primary)' : 'var(--border-strong)'}`,
                }}
              >
                <p
                  style={{
                    fontSize: '0.6875rem',
                    fontWeight: 700,
                    color: fromHr ? 'var(--primary)' : 'var(--muted-foreground)',
                    marginBottom: '0.1875rem',
                  }}
                >
                  {fromHr ? `${c.authorName || 'HR'} · HR` : `${c.authorName || 'Employee'} · employee`}
                </p>
                <div style={{ fontSize: '0.8125rem', lineHeight: 1.55 }}>
                  <RichText text={c.body} />
                </div>
              </div>
            );
          })}
        </div>
      )}

      <textarea
        className="input"
        style={{ minHeight: 64, resize: 'vertical', fontSize: '0.8125rem' }}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder="The employee sees this on their own tickets page. Supports **bold** and lists."
      />

      <div
        style={{
          display: 'flex',
          gap: '0.75rem',
          alignItems: 'center',
          flexWrap: 'wrap',
          marginTop: '0.5rem',
        }}
      >
        <button className="btn btn-primary btn-sm" onClick={send} disabled={busy || !draft.trim()}>
          {busy ? 'Sending…' : 'Send to employee'}
        </button>
        <span style={{ fontSize: '0.75rem', color: 'var(--faint)' }}>
          Visible to the employee who raised this
        </span>
        {error && <span style={{ fontSize: '0.75rem', color: 'var(--error-ink)' }}>{error}</span>}
      </div>
    </div>
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

      {/*
        The two review panels sit side by side once there is room. They are read together — a
        low-rated topic and the comment explaining why — so putting them on one screen saves
        scrolling between them, and it fills width that stacking wasted.
      */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(420px, 100%), 1fr))',
          gap: '1.25rem',
          alignItems: 'start',
        }}
      >
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
    </div>
  );
}

// ---------------------------------------------------------------------------
// Knowledge base
// ---------------------------------------------------------------------------

/**
 * Starting suggestions, not an allowlist.
 *
 * The backend accepts any category string and only substitutes "General" when one is missing,
 * so HR was never limited to these — the old `<select>` was the only thing enforcing them.
 * They now seed a datalist alongside whatever categories the knowledge base already contains.
 */
const CATEGORY_SUGGESTIONS = [
  'Leave Management',
  'Payroll',
  'Benefits',
  'Compliance',
  'Workplace',
  'Onboarding',
  'Exit',
  'General',
];

/** Shared by both category inputs, so a new one typed in either place is suggested in the other. */
const CATEGORY_LIST_ID = 'hr-category-suggestions';

/**
 * Suggestions plus every category already in use, de-duplicated case-insensitively.
 *
 * Showing what is already there is the point: free text without it invites "Insurance",
 * "insurance" and "Medical Insurance" as three separate categories, and nothing downstream
 * would notice them fragmenting.
 */
function categoryOptions(kb: KnowledgeSnapshot | null): string[] {
  const seen = new Map<string, string>();
  for (const value of [...CATEGORY_SUGGESTIONS, ...(kb?.docs ?? []).map((d) => d.category)]) {
    const label = (value ?? '').trim();
    if (label && !seen.has(label.toLowerCase())) seen.set(label.toLowerCase(), label);
  }
  return [...seen.values()].sort((a, b) => a.localeCompare(b));
}

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
  const options = categoryOptions(kb);

  return (
    <div style={{ display: 'grid', gap: '1.25rem' }}>
      {/* One datalist for both inputs below. Native, so it stays keyboard- and
          screen-reader-accessible without a custom combobox. */}
      <datalist id={CATEGORY_LIST_ID}>
        {options.map((c) => (
          <option key={c} value={c} />
        ))}
      </datalist>
      <AiEnginePanel ai={ai} onChanged={onChanged} />

      <EmployeeExtractPanel />

      <UploadZone category={category} onCategory={setCategory} onChanged={onChanged} />

      <AddEntryForm category={category} onCategory={setCategory} onChanged={onChanged} />

      <DocumentList docs={kb?.docs ?? []} onChanged={onChanged} />

      <BuiltInFaqs faqs={faqs} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Employee extract
// ---------------------------------------------------------------------------

/**
 * Re-reads the HR extract workbooks into the employee collection.
 *
 * <p>This existed as an endpoint with nothing calling it, which had a real cost: refreshing
 * an extract meant an engineer pasting a fetch into devtools, so HR could not do it at all.
 * It is also how a column added to the importer sat unused — the code shipped, the data never
 * moved, and the assistant kept saying it did not have a figure that was in the spreadsheet.
 *
 * <p>Deliberately here rather than on its own tab: this is the other half of "what the
 * assistant knows". Uploads above are the policy corpus; this is the employee record.
 */
function EmployeeExtractPanel() {
  const [status, setStatus] = useState<EmployeeDataStatus | null>(null);
  const [report, setReport] = useState<EmployeeImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await fetchEmployeeDataStatus());
    } catch {
      // A missing count is not worth a banner — the button below still works, and the
      // import's own result is the thing that matters.
      setStatus(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async () => {
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      const r = await importEmployeeExtract();
      setReport(r);
      await load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Import failed.');
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '0.875rem' }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'baseline' }}>
        <h2 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>Employee data</h2>
        {status && (
          <span className="badge badge-info">
            {status.recordCount.toLocaleString()} records
          </span>
        )}
      </div>

      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', lineHeight: 1.5, maxWidth: '62ch' }}>
        Re-reads the extract workbooks from the configured directory. Run this after HR Ops
        publishes a new extract — the assistant answers questions about someone&apos;s own
        salary, leave and attendance from these records, and it can only report what the last
        import brought in.
      </p>

      {/*
        A confirm step, because this replaces the collection rather than merging into it —
        by design, so that people who have left do not linger — and a stray click would drop
        every record until the next successful read.
      */}
      {!confirming ? (
        <div>
          <button className="btn btn-secondary btn-sm" onClick={() => setConfirming(true)} disabled={busy}>
            <RefreshIcon size={14} />
            Re-import extract
          </button>
        </div>
      ) : (
        <div
          style={{
            display: 'grid',
            gap: '0.625rem',
            padding: '0.875rem',
            borderRadius: 'var(--radius)',
            background: 'var(--warning-soft)',
            border: '1px solid var(--border)',
          }}
        >
          <p style={{ fontSize: '0.8125rem', fontWeight: 600, lineHeight: 1.45 }}>
            This replaces all employee records with what the workbooks currently say. Anyone
            absent from the new extract disappears from the assistant.
          </p>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button className="btn btn-primary btn-sm" onClick={() => void run()} disabled={busy}>
              {busy ? 'Importing…' : 'Yes, re-import'}
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {error && (
        <p style={{ fontSize: '0.8125rem', color: 'var(--error-ink)', lineHeight: 1.5 }}>
          <strong>Import failed.</strong> {error}
        </p>
      )}

      {/*
        The reconciliation numbers, not a bare "done". "5,000 imported, 5,000 matched on
        compensation" is the evidence the records match the extract; an import that silently
        dropped 40 rows would also have returned success.
      */}
      {report && (
        <div style={{ display: 'grid', gap: '0.625rem' }}>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(min(150px, 100%), 1fr))',
              gap: '0.625rem',
            }}
          >
            <ReportStat label="Imported" value={report.imported} />
            <ReportStat label="Master rows" value={report.masterRows} />
            <ReportStat label="Compensation matched" value={report.compensationMatched} />
            <ReportStat label="Leave matched" value={report.leaveMatched} />
            <ReportStat label="Attendance matched" value={report.attendanceMatched} />
            <ReportStat label="Unresolvable" value={report.unresolvable} bad={report.unresolvable > 0} />
          </div>

          {report.emailDomainRewrite && (
            <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
              Email domain rewritten: {report.emailDomainRewrite}
            </p>
          )}

          {report.warnings.length > 0 && (
            <div style={{ display: 'grid', gap: '0.3125rem' }}>
              <p style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--warning-ink)' }}>
                {report.warnings.length} warning{report.warnings.length === 1 ? '' : 's'}
              </p>
              <ul style={{ display: 'grid', gap: '0.25rem', paddingLeft: '1.125rem' }}>
                {report.warnings.map((w, i) => (
                  <li key={i} style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)', lineHeight: 1.5 }}>
                    {w}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function ReportStat({ label, value, bad }: { label: string; value: number; bad?: boolean }) {
  return (
    <div>
      <p
        style={{
          fontSize: '1.125rem',
          fontWeight: 700,
          fontVariantNumeric: 'tabular-nums',
          color: bad ? 'var(--error-ink)' : 'var(--foreground)',
        }}
      >
        {value.toLocaleString()}
      </p>
      <p className="label-caps" style={{ fontSize: '0.625rem' }}>
        {label}
      </p>
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

  const { ollama, claude, stats } = ai;

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
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(150px, 100%), 1fr))',
          gap: '0.625rem 1rem',
          fontSize: '0.8125rem',
        }}
      >
        <div>
          <dt className="label-caps">Embedder</dt>
          <dd>{ollama.ok ? `Ollama · ${ollama.url}` : 'Not running'}</dd>
        </div>
        <div>
          <dt className="label-caps">Embedding model</dt>
          <dd>{ollama.embedModel ?? '—'}</dd>
        </div>
        <div>
          <dt className="label-caps">Answer model</dt>
          <dd>
            {claude.configured ? claude.model : '—'}
            {claude.configured && claude.effort ? (
              <span style={{ color: 'var(--muted-foreground)' }}> · {claude.effort} effort</span>
            ) : null}
          </dd>
        </div>
        <div>
          <dt className="label-caps">Index</dt>
          <dd>
            {stats.embeddedChunks}/{stats.chunkCount} passages embedded
          </dd>
        </div>
      </dl>

      {/*
        Two independent hints, not one. The embedder and the answer model fail for
        unrelated reasons and are fixed in different places — collapsing them into a
        single banner would show one fix while hiding the other.
      */}
      {claude.hint && (
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
          <RichText text={claude.hint} />
        </div>
      )}

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
    // them silently — HR would otherwise assume the file went in.
    const skipped: UploadOutcome[] = rejected.map((f) => ({
      filename: f.name,
      ok: false,
      error: 'Unsupported format — convert to .pdf, .docx, .txt, .md or .csv first.',
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
        PDF, Word (.docx), plain text (.txt), Markdown (.md) or spreadsheets exported as .csv.
        Text inside images — a scanned circular, a screenshot of a table — is read and indexed
        too. Each document is split into passages and indexed: employees can ask about it
        straight away, and there is no retraining step.
      </p>

      <label style={{ display: 'block', marginBottom: '0.875rem' }}>
        <span className="label-caps">Category</span>
        <input
          className="input"
          style={{ marginTop: '0.25rem', maxWidth: 280 }}
          list={CATEGORY_LIST_ID}
          value={category}
          placeholder="Pick one or type a new category"
          onChange={(e) => onCategory(e.target.value)}
        />
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
            : `${ACCEPTED_EXTENSIONS.join('  ·  ')}  ·  up to 15 MB each`}
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
          <input
            className="input"
            style={{ marginTop: '0.25rem' }}
            list={CATEGORY_LIST_ID}
            value={category}
            placeholder="Pick one or type a new category"
            onChange={(e) => onCategory(e.target.value)}
          />
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

      {/*
        People on the left, what the roles mean on the right — they are read together, since
        assigning a role is the task and its definition is the reference for it. `auto-fit` with a
        440px floor collapses them back to a stack below roughly 1140px, so nothing is squeezed on
        a laptop.
      */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(440px, 100%), 1fr))',
          gap: '1.25rem',
          alignItems: 'start',
        }}
      >
        <UsersPanel
          users={users}
          roles={roles}
          breakGlass={breakGlass}
          signedInAs={signedInAs}
          onChanged={reload}
        />
        <RolesPanel roles={roles} permissions={permissions} onChanged={reload} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Audit trail (HR Ops Admin)
// ---------------------------------------------------------------------------

/** Relative time, because "who changed this and when" is nearly always a recency question. */
function timeAgo(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '—';

  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function exactTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

/** Access changes are the rows an admin is looking for, so they are tinted apart from uploads. */
function actionTone(action: string): { fg: string; bg: string } {
  if (action.startsWith('access.') || action.startsWith('role.')) {
    return { fg: 'var(--primary)', bg: 'var(--primary-soft, var(--surface-2))' };
  }
  if (action === 'policy.deleted') {
    return { fg: 'var(--error)', bg: 'var(--error-soft)' };
  }
  return { fg: 'var(--muted-foreground)', bg: 'var(--surface-2)' };
}

/**
 * Every policy change and access change, newest first.
 *
 * Read-only, and deliberately so: there is no control here to clear or edit a row, because the
 * people who can open this page are the people it records.
 */
// ---------------------------------------------------------------------------
// Variable pay plan
// ---------------------------------------------------------------------------

/**
 * The Variable Pay Policy as editable data.
 *
 * The figures that move most are at the top, because they are the ones somebody comes here to
 * change: the company's achieved revenue and GRR, declared once a year. The bands, weightings and
 * rating payouts below them change only between policy versions, but they are here rather than in
 * a config file for the same reason — the policy reserves the right to revise any clause, and a
 * number compiled into a build is a redeploy plus a chance of silently disagreeing with the PDF HR
 * is reading from.
 *
 * Nothing here computes anyone's payout. The band previews are a courtesy so an entered figure
 * shows its effect immediately; every payout an employee is told comes from the server, from the
 * stored plan.
 */
function VariablePayPlanPanel() {
  const [plan, setPlan] = useState<VariablePayPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError(null);
    try {
      setPlan(await fetchVariablePayPlan());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the variable pay plan.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const save = async () => {
    if (!plan) return;
    setSaving(true);
    setError(null);
    setSaved(null);
    try {
      const updated = await saveVariablePayPlan(plan);
      setPlan(updated);
      setSaved(`Saved ${new Date(updated.updatedAt).toLocaleString()}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the plan.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <section className="card" style={{ padding: '1.125rem' }}>Loading…</section>;
  if (!plan) {
    return (
      <section className="card" style={{ padding: '1.125rem' }}>
        <span style={{ color: 'var(--error-ink)' }}>{error ?? 'No plan available.'}</span>
      </section>
    );
  }

  /** Blank means "not declared", which is different from zero — see the record on the server. */
  const num = (raw: string): number | null => {
    const t = raw.trim();
    if (t === '') return null;
    const v = Number(t);
    return Number.isNaN(v) ? null : v;
  };

  const revenueBand = bandFor(plan.revenueBands, plan.revenueActualCr);
  const grrBand = bandFor(plan.grrBands, plan.grrActualPercent);

  const label: React.CSSProperties = {
    fontSize: '0.75rem',
    fontWeight: 600,
    color: 'var(--muted-foreground)',
    display: 'block',
    marginBottom: '0.25rem',
  };
  const note: React.CSSProperties = { fontSize: '0.75rem', color: 'var(--muted-foreground)' };

  return (
    <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '1.25rem' }}>
      <div>
        <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
          Variable pay plan — {plan.fyLabel}
        </h2>
        <p style={{ ...note, marginBottom: 0 }}>
          Variable Pay Policy v{plan.policyVersion} (Non-Sales). Payout is a company share plus an
          individual share, weighted by grade. Sales is on its own policy and is excluded.
          {plan.updatedBy ? ` Last changed by ${plan.updatedBy}.` : ''}
        </p>
      </div>

      <div style={{ display: 'grid', gap: '0.75rem' }}>
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>This year&apos;s company result</h3>
        <p style={note}>
          Leave these blank until the result is declared. Blank means &quot;not yet known&quot;, and
          the assistant then answers with the individual share only and says it is partial — a zero
          here would tell every employee the company missed.
        </p>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 14rem' }}>
            <label style={label}>Revenue achieved (₹ Cr)</label>
            <input
              className="input"
              inputMode="decimal"
              placeholder="not declared"
              value={plan.revenueActualCr ?? ''}
              onChange={(e) => setPlan({ ...plan, revenueActualCr: num(e.target.value) })}
              style={{ width: '100%' }}
            />
            <span style={note}>
              {revenueBand
                ? `→ ${revenueBand.name} (${revenueBand.funding}x)`
                : 'Not declared — company share not computed'}
            </span>
          </div>
          <div style={{ flex: '1 1 14rem' }}>
            <label style={label}>GRR achieved (%)</label>
            <input
              className="input"
              inputMode="decimal"
              placeholder="not declared"
              value={plan.grrActualPercent ?? ''}
              onChange={(e) => setPlan({ ...plan, grrActualPercent: num(e.target.value) })}
              style={{ width: '100%' }}
            />
            <span style={note}>
              {grrBand
                ? `→ ${grrBand.name} (${grrBand.funding}x)`
                : 'Not declared — company share not computed'}
            </span>
          </div>
        </div>
      </div>

      <BandEditor
        title="Revenue bands (₹ Cr)"
        bands={plan.revenueBands}
        onChange={(revenueBands) => setPlan({ ...plan, revenueBands })}
      />
      <BandEditor
        title="GRR bands (%)"
        bands={plan.grrBands}
        onChange={(grrBands) => setPlan({ ...plan, grrBands })}
      />

      <div style={{ display: 'grid', gap: '0.5rem' }}>
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>Grade weighting</h3>
        <p style={note}>
          How much of the payout rides on the company versus the individual. The policy writes these
          as X-grades; they are the same ladder as the L-grades in the employee records.
        </p>
        {plan.gradeWeights.map((w, i) => (
          <div key={`${w.fromLevel}-${w.toLevel}`} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ ...note, minWidth: '5rem' }}>
              L{w.fromLevel}–L{w.toLevel}
            </span>
            <input
              className="input"
              inputMode="decimal"
              value={w.companyPercent}
              onChange={(e) => {
                const next = [...plan.gradeWeights];
                next[i] = { ...w, companyPercent: Number(e.target.value) || 0 };
                setPlan({ ...plan, gradeWeights: next });
              }}
              style={{ width: '5.5rem' }}
            />
            <span style={note}>% company</span>
            <input
              className="input"
              inputMode="decimal"
              value={w.individualPercent}
              onChange={(e) => {
                const next = [...plan.gradeWeights];
                next[i] = { ...w, individualPercent: Number(e.target.value) || 0 };
                setPlan({ ...plan, gradeWeights: next });
              }}
              style={{ width: '5.5rem' }}
            />
            <span style={note}>% individual</span>
            {w.companyPercent + w.individualPercent !== 100 && (
              <span style={{ ...note, color: 'var(--error-ink)' }}>
                does not add to 100
              </span>
            )}
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gap: '0.5rem' }}>
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>Rating payouts</h3>
        <p style={note}>
          What each appraisal rating funds on the individual share. The policy words these as
          &quot;up to&quot; and leaves the final figure to the organisation, and the assistant says
          so whenever it quotes one.
        </p>
        {plan.ratingPayouts.map((r, i) => (
          <div key={r.rating} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            <span style={{ ...note, minWidth: '9rem' }}>
              {r.rating} — {r.label}
            </span>
            <input
              className="input"
              inputMode="decimal"
              value={r.payoutPercent}
              onChange={(e) => {
                const next = [...plan.ratingPayouts];
                next[i] = { ...r, payoutPercent: Number(e.target.value) || 0 };
                setPlan({ ...plan, ratingPayouts: next });
              }}
              style={{ width: '5.5rem' }}
            />
            <span style={note}>% of the individual share</span>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save plan'}
        </button>
        {saved && <span style={{ ...note, color: 'var(--success-ink)' }}>{saved}</span>}
        {error && <span style={{ ...note, color: 'var(--error-ink)' }}>{error}</span>}
      </div>
    </section>
  );
}

/**
 * The three revenue-function policies — US, India Sales, PS & CSM — as editable data.
 *
 * A generic editor rather than three bespoke forms, because the three differ in almost every
 * particular while sharing one shape: roles earn weighted components, each scored on a slab, with
 * kickers on top. Hand-written forms would have to be rewritten for next year's revision; this one
 * renders whatever the server holds.
 *
 * The org values sit first because they are what changes most — thresholds, hourly floors, service
 * base rates, and the BU-target-met flags that decide whether a 50% retention tier pays at all.
 */
function FunctionPayPanel() {
  const [plans, setPlans] = useState<FunctionPayPlan[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError(null);
    try {
      setPlans((await fetchFunctionPayPlans()).plans);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the pay plans.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (loading) return <section className="card" style={{ padding: '1.125rem' }}>Loading…</section>;
  if (!plans.length) {
    return (
      <section className="card" style={{ padding: '1.125rem' }}>
        <span style={{ color: 'var(--error-ink)' }}>{error ?? 'No plans available.'}</span>
      </section>
    );
  }

  const plan = plans[active];
  const patch = (next: FunctionPayPlan) =>
    setPlans(plans.map((p, i) => (i === active ? next : p)));

  const save = async () => {
    setSaving(true);
    setError(null);
    setSaved(null);
    try {
      const updated = await saveFunctionPayPlan(plan);
      setPlans(plans.map((p, i) => (i === active ? updated : p)));
      setSaved(`Saved ${new Date(updated.updatedAt).toLocaleString()}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the plan.');
    } finally {
      setSaving(false);
    }
  };

  /** Blank means "not declared", which the calculator treats differently from zero. */
  const num = (raw: string): number | null => {
    const t = raw.trim();
    if (t === '') return null;
    const v = Number(t);
    return Number.isNaN(v) ? null : v;
  };
  const note: React.CSSProperties = { fontSize: '0.75rem', color: 'var(--muted-foreground)' };
  const h3: React.CSSProperties = { fontSize: '0.8125rem', fontWeight: 700, marginTop: '0.5rem' };

  return (
    <section className="card" style={{ padding: '1.125rem', display: 'grid', gap: '1rem' }}>
      <div style={{ display: 'flex', gap: '0.375rem', flexWrap: 'wrap' }}>
        {plans.map((p, i) => (
          <button
            key={p.planKey}
            className={i === active ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'}
            onClick={() => setActive(i)}
          >
            {p.planKey}
          </button>
        ))}
      </div>

      <div>
        <h2 style={{ fontSize: '0.9375rem', fontWeight: 700 }}>{plan.label}</h2>
        <p style={{ ...note, marginBottom: 0 }}>
          v{plan.policyVersion} · {plan.fyLabel} · amounts in <strong>{plan.currency}</strong>
          {plan.updatedBy ? ` · last changed by ${plan.updatedBy}` : ''}
        </p>
        <p style={note}>
          These policies measure targets held in ACE, KAM Connect and the Sales Ops dashboards, which
          are not in the HR extract. The assistant computes a payout from figures an employee gives
          it, against the rules below.
        </p>
      </div>

      <div style={{ display: 'grid', gap: '0.5rem' }}>
        <h3 style={h3}>Organisation values</h3>
        <p style={note}>
          Thresholds, hourly floors and base rates. Leave a value blank for &quot;not declared&quot; —
          the BU-target flags decide whether a 50% retention tier pays at all, so a blank there is
          not the same as a zero.
        </p>
        {plan.orgValues.map((v, i) => (
          <div key={v.key} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ ...note, flex: '1 1 18rem', minWidth: 0 }} title={v.note ?? undefined}>
              {v.label}
            </span>
            <input
              className="input"
              inputMode="decimal"
              placeholder="not declared"
              value={v.value ?? ''}
              onChange={(e) => {
                const next = [...plan.orgValues];
                next[i] = { ...v, value: num(e.target.value) };
                patch({ ...plan, orgValues: next });
              }}
              style={{ width: '8rem' }}
            />
            <span style={{ ...note, width: '9rem' }}>{v.unit}</span>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gap: '0.75rem' }}>
        <h3 style={h3}>Payout slabs</h3>
        {plan.slabs.map((slab, si) => (
          <div key={slab.key} style={{ borderTop: '1px solid var(--border)', paddingTop: '0.5rem' }}>
            <div style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{slab.label}</div>
            <div style={note}>{slabKindLabel(slab.kind)}{slab.buTargetGates ? ' · 50% tier needs the BU target met' : ''}</div>
            {slab.bands.map((b, bi) => (
              <div key={b.name} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginTop: '0.25rem', flexWrap: 'wrap' }}>
                <span style={{ ...note, width: '11rem' }}>{b.name}</span>
                <input
                  className="input" inputMode="decimal" placeholder="—" style={{ width: '5.5rem' }}
                  value={b.fromExclusive ?? ''}
                  onChange={(e) => {
                    const slabs = [...plan.slabs];
                    const bands = [...slab.bands];
                    bands[bi] = { ...b, fromExclusive: num(e.target.value) };
                    slabs[si] = { ...slab, bands };
                    patch({ ...plan, slabs });
                  }}
                />
                <span style={note}>to</span>
                <input
                  className="input" inputMode="decimal" placeholder="—" style={{ width: '5.5rem' }}
                  value={b.toInclusive ?? ''}
                  onChange={(e) => {
                    const slabs = [...plan.slabs];
                    const bands = [...slab.bands];
                    bands[bi] = { ...b, toInclusive: num(e.target.value) };
                    slabs[si] = { ...slab, bands };
                    patch({ ...plan, slabs });
                  }}
                />
                <span style={note}>pays</span>
                <input
                  className="input" inputMode="decimal" style={{ width: '5rem' }}
                  value={b.factorPercent}
                  onChange={(e) => {
                    const slabs = [...plan.slabs];
                    const bands = [...slab.bands];
                    bands[bi] = { ...b, factorPercent: Number(e.target.value) || 0 };
                    slabs[si] = { ...slab, bands };
                    patch({ ...plan, slabs });
                  }}
                />
                <span style={note}>%</span>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gap: '0.75rem' }}>
        <h3 style={h3}>Roles — components and kicker rates</h3>
        {plan.roles.map((role, ri) => (
          <div key={role.key} style={{ borderTop: '1px solid var(--border)', paddingTop: '0.5rem' }}>
            <div style={{ fontSize: '0.8125rem', fontWeight: 600 }}>
              {role.function} — {role.level}
            </div>
            {role.components.map((c, ci) => (
              <div key={c.name} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginTop: '0.25rem', flexWrap: 'wrap' }}>
                <span style={{ ...note, flex: '1 1 16rem', minWidth: 0 }} title={c.note ?? undefined}>
                  {c.name} <em>({c.frequency})</em>
                </span>
                <input
                  className="input" inputMode="decimal" style={{ width: '5rem' }}
                  value={c.weightPercent}
                  onChange={(e) => {
                    const roles = [...plan.roles];
                    const components = [...role.components];
                    components[ci] = { ...c, weightPercent: Number(e.target.value) || 0 };
                    roles[ri] = { ...role, components };
                    patch({ ...plan, roles });
                  }}
                />
                <span style={note}>%</span>
              </div>
            ))}
            {role.components.reduce((t, c) => t + c.weightPercent, 0) !== 100 && (
              <div style={{ ...note, color: 'var(--error-ink)' }}>
                components do not total 100% — the policy says they always do
              </div>
            )}
            {role.kickers.map((k, ki) => (
              <div key={k.name} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginTop: '0.25rem', flexWrap: 'wrap' }}>
                <span style={{ ...note, flex: '1 1 16rem', minWidth: 0, opacity: 0.85 }}>
                  kicker · {k.name} — of {k.basis} ({k.frequency})
                </span>
                <input
                  className="input" inputMode="decimal" style={{ width: '5rem' }}
                  value={k.ratePercent}
                  onChange={(e) => {
                    const roles = [...plan.roles];
                    const kickers = [...role.kickers];
                    kickers[ki] = { ...k, ratePercent: Number(e.target.value) || 0 };
                    roles[ri] = { ...role, kickers };
                    patch({ ...plan, roles });
                  }}
                />
                <span style={note}>%</span>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <button className="btn btn-primary btn-sm" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : `Save ${plan.planKey}`}
        </button>
        {saved && <span style={{ ...note, color: 'var(--success-ink)' }}>{saved}</span>}
        {error && <span style={{ ...note, color: 'var(--error-ink)' }}>{error}</span>}
      </div>
    </section>
  );
}

/** One achievement ladder. Open-ended top and bottom bands are shown as such rather than as 0. */
function BandEditor({
  title,
  bands,
  onChange,
}: {
  title: string;
  bands: Band[];
  onChange: (bands: Band[]) => void;
}) {
  const note: React.CSSProperties = { fontSize: '0.75rem', color: 'var(--muted-foreground)' };
  const edit = (i: number, patch: Partial<Band>) => {
    const next = [...bands];
    next[i] = { ...next[i], ...patch };
    onChange(next);
  };
  const num = (raw: string): number | null => {
    const t = raw.trim();
    if (t === '') return null;
    const v = Number(t);
    return Number.isNaN(v) ? null : v;
  };

  return (
    <div style={{ display: 'grid', gap: '0.5rem' }}>
      <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>{title}</h3>
      {bands.map((b, i) => (
        <div key={b.name} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <span style={{ ...note, minWidth: '8.5rem' }}>{b.name}</span>
          <input
            className="input"
            inputMode="decimal"
            placeholder="—"
            value={b.fromInclusive ?? ''}
            onChange={(e) => edit(i, { fromInclusive: num(e.target.value) })}
            style={{ width: '6rem' }}
          />
          <span style={note}>to under</span>
          <input
            className="input"
            inputMode="decimal"
            placeholder="—"
            value={b.toExclusive ?? ''}
            onChange={(e) => edit(i, { toExclusive: num(e.target.value) })}
            style={{ width: '6rem' }}
          />
          <span style={note}>funds</span>
          <input
            className="input"
            inputMode="decimal"
            value={b.funding}
            onChange={(e) => edit(i, { funding: Number(e.target.value) || 0 })}
            style={{ width: '5rem' }}
          />
          <span style={note}>x</span>
        </div>
      ))}
    </div>
  );
}

function AuditTrail() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [actions, setActions] = useState<string[]>([]);
  const [filter, setFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async (action: string) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchAudit(action || undefined);
      setEvents(data.events);
      // Only replace the filter options on an unfiltered read: a filtered response still carries
      // the full catalogue, but taking it from the first load keeps the dropdown stable.
      if (data.actions.length > 0) setActions(data.actions);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the audit trail.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload(filter);
  }, [reload, filter]);

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
          <strong style={{ color: 'var(--error)' }}>Audit trail unavailable.</strong> {error}
        </div>
      )}

      <section className="card" style={{ padding: '1.125rem' }}>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: '0.75rem',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            marginBottom: '1rem',
          }}
        >
          <div>
            <h3 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
              Audit trail
            </h3>
            <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
              Policy uploads and access changes, newest first. Append-only — nothing here can be
              edited or removed from the console.
            </p>
          </div>

          <select
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            aria-label="Filter by action"
            style={{
              padding: '0.5rem 0.625rem',
              fontSize: '0.8125rem',
              borderRadius: 'var(--radius)',
              border: '1px solid var(--border)',
              background: 'var(--surface)',
              color: 'var(--foreground)',
            }}
          >
            <option value="">All actions</option>
            {actions.map((a) => (
              <option key={a} value={a}>
                {AUDIT_ACTION_LABELS[a] ?? a}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <div className="skeleton" style={{ height: 200 }} />
        ) : events.length === 0 ? (
          <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
            {filter
              ? 'Nothing recorded for that action yet.'
              : 'Nothing recorded yet. Uploading a policy or changing someone’s access will appear here.'}
          </p>
        ) : (
          <ul style={{ display: 'grid', gap: '0.625rem', listStyle: 'none' }}>
            {events.map((event) => {
              const tone = actionTone(event.action);
              return (
                <li
                  key={event.id}
                  style={{
                    padding: '0.75rem 0.875rem',
                    borderRadius: 'var(--radius)',
                    background: 'var(--surface-2)',
                    display: 'grid',
                    gap: '0.375rem',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: '0.5rem',
                      alignItems: 'center',
                    }}
                  >
                    <span
                      style={{
                        fontSize: '0.6875rem',
                        fontWeight: 700,
                        textTransform: 'uppercase',
                        letterSpacing: '0.03em',
                        padding: '0.125rem 0.5rem',
                        borderRadius: 999,
                        color: tone.fg,
                        background: tone.bg,
                      }}
                    >
                      {AUDIT_ACTION_LABELS[event.action] ?? event.action}
                    </span>
                    <strong style={{ fontSize: '0.8125rem' }}>{event.actor}</strong>
                    <span
                      title={exactTime(event.at)}
                      style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}
                    >
                      {timeAgo(event.at)}
                    </span>
                  </div>
                  {/* Capped measure: the shell is wide now, and a sentence running the full
                      1560px is hard to track back to the start of the next line. */}
                  <p
                    style={{
                      fontSize: '0.8125rem',
                      color: 'var(--foreground)',
                      maxWidth: '90ch',
                    }}
                  >
                    {event.detail}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sign-ins (HR Ops Admin)
// ---------------------------------------------------------------------------

/**
 * Who has signed in, and when they last did.
 *
 * Sign-ins rather than "employees": the list is keyed on the account that authenticated, so it
 * includes people the HR extract does not cover — new joiners, contractors, admin accounts. The
 * employee code is shown when the extract resolves one, and left blank when it does not, which is
 * more honest than implying everyone who signs in is in the extract.
 */
function SignIns() {
  const [logins, setLogins] = useState<LoginRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchLogins();
      setLogins(data.logins);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the sign-in list.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const cell: React.CSSProperties = {
    padding: '0.625rem 0.75rem',
    fontSize: '0.8125rem',
    textAlign: 'left',
    borderBottom: '1px solid var(--border)',
    whiteSpace: 'nowrap',
  };

  const head: React.CSSProperties = {
    ...cell,
    fontSize: '0.6875rem',
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.03em',
    color: 'var(--muted-foreground)',
  };

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
          <strong style={{ color: 'var(--error)' }}>Sign-in list unavailable.</strong> {error}
        </div>
      )}

      <section className="card" style={{ padding: '1.125rem' }}>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: '0.75rem',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            marginBottom: '1rem',
          }}
        >
          <div>
            <h3 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>
              Sign-ins
            </h3>
            <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
              Everyone who has signed in to the assistant, most recent first. Updated on each
              sign-in.
            </p>
          </div>
          <button className="btn btn-ghost" onClick={() => void reload()} disabled={loading}>
            <RefreshIcon />
            Refresh
          </button>
        </div>

        {loading ? (
          <div className="skeleton" style={{ height: 200 }} />
        ) : logins.length === 0 ? (
          <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
            No sign-ins recorded yet.
          </p>
        ) : (
          // Its own scroll container: the page body must never scroll sideways.
          <div style={{ overflowX: 'auto' }}>
            {/* .table-stack turns these rows into cards under 861px — see globals.css.
                Each cell carries a data-label, which is where the dropped column
                headings reappear. */}
            <table className="table-stack">
              <thead>
                <tr>
                  <th style={head}>Employee</th>
                  <th style={head}>Code</th>
                  <th style={head}>Last sign-in</th>
                  <th style={head}>Sign-ins</th>
                  <th style={head}>Method</th>
                </tr>
              </thead>
              <tbody>
                {logins.map((row) => (
                  <tr key={row.id}>
                    <td style={cell}>
                      <div style={{ fontWeight: 600 }}>{row.name}</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
                        {row.email}
                      </div>
                    </td>
                    <td data-label="Code" style={{ ...cell, color: 'var(--muted-foreground)' }}>
                      {row.employeeCode ?? '—'}
                    </td>
                    <td data-label="Last sign-in" style={cell} title={exactTime(row.lastLoginAt)}>
                      {timeAgo(row.lastLoginAt)}
                    </td>
                    <td data-label="Sign-ins" style={cell}>
                      {row.loginCount}
                    </td>
                    <td data-label="Method" style={{ ...cell, color: 'var(--muted-foreground)' }}>
                      {row.method}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
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
              placeholder="Full name, as it appears in Entra"
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
                      {/* What the badge reports is whether the console opens, which is now
                          decided by either of two roles — so it names the access, not a role. */}
                      {isAdmin ? 'Console access' : 'No console access'}
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
  const [error, setError] = useState<string | null>(null);

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

  const [newLabel, setNewLabel] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [newPermissions, setNewPermissions] = useState<string[]>([ADMIN_CONSOLE]);
  const [creating, setCreating] = useState(false);

  const create = async () => {
    setError(null);
    setCreating(true);
    try {
      await createRole({
        name: slugify(newLabel),
        label: newLabel,
        description: newDescription,
        permissions: newPermissions,
      });
      setNewLabel('');
      setNewDescription('');
      setNewPermissions([ADMIN_CONSOLE]);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create that role.');
    } finally {
      setCreating(false);
    }
  };

  const toggleNewPermission = (key: string, checked: boolean) =>
    setNewPermissions((held) =>
      checked ? [...held, key] : held.filter((p) => p !== key)
    );

  return (
    <section className="card" style={{ padding: '1.125rem' }}>
      <h2 style={{ fontSize: '0.9375rem', fontWeight: 700, marginBottom: '0.25rem' }}>Roles</h2>
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', marginBottom: '1rem' }}>
        Every role is defined here — nothing ships with any. Give each one only the areas it needs;
        a role without <strong>Open the HR admin console</strong> reaches nothing at all.
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

      {/*
        There was no form here while the role set was fixed at two — the server refused
        createRole, and a form that always errors reads as a broken feature rather than a
        deliberate constraint. Both halves of that changed together: the console's areas are
        separate permissions now, so a third role can differ from the built-ins by what it grants
        rather than only by its name.
      */}
      <div
        style={{
          display: 'grid',
          gap: '0.5rem',
          borderTop: '1px solid var(--border)',
          paddingTop: '1rem',
          marginTop: '1rem',
        }}
      >
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>Add a role</h3>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <input
            className="input"
            placeholder="Name, e.g. Policy Librarian"
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            style={{ flex: '1 1 12rem', minWidth: 0 }}
          />
          <input
            className="input"
            placeholder="What it is for (optional)"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            style={{ flex: '2 1 16rem', minWidth: 0 }}
          />
        </div>
        <div style={{ display: 'flex', gap: '0.875rem', flexWrap: 'wrap' }}>
          {permissions.map((permission) => {
            // The console permission is the front door: without it the rest reach nothing, so it
            // is checked and fixed rather than presented as a choice that silently breaks a role.
            const isConsole = permission.key === ADMIN_CONSOLE;
            return (
              <label
                key={permission.key}
                title={permission.description}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '0.375rem',
                  fontSize: '0.75rem',
                  color: 'var(--foreground-secondary)',
                  cursor: isConsole ? 'not-allowed' : 'pointer',
                  opacity: isConsole ? 0.7 : 1,
                }}
              >
                <input
                  type="checkbox"
                  checked={isConsole || newPermissions.includes(permission.key)}
                  disabled={isConsole}
                  onChange={(e) => toggleNewPermission(permission.key, e.target.checked)}
                />
                {permission.label}
              </label>
            );
          })}
        </div>
        <div>
          <button
            className="btn btn-primary btn-sm"
            onClick={create}
            disabled={creating || !newLabel.trim()}
          >
            {creating ? 'Creating…' : 'Create role'}
          </button>
        </div>
      </div>

      <div
        style={{
          display: 'grid',
          gap: '0.5rem',
          borderTop: '1px solid var(--border)',
          paddingTop: '1rem',
        }}
      >
        {error && (
          <span style={{ fontSize: '0.8125rem', color: 'var(--error-ink)' }}>{error}</span>
        )}
        <h3 style={{ fontSize: '0.8125rem', fontWeight: 700 }}>What the permissions mean</h3>
        <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
          Keep at least one role holding <strong>Manage roles and access</strong>, and someone
          holding that role. If none is left, only the break-glass addresses configured for this
          deployment can reach this page to grant it again.
        </p>
        <div style={{ display: 'grid', gap: '0.375rem', marginTop: '0.25rem' }}>
          {permissions.map((permission) => (
            <div key={permission.key} style={{ fontSize: '0.8125rem' }}>
              <strong>{permission.label}</strong>
              <span
                style={{
                  display: 'block',
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                }}
              >
                {permission.description}
              </span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
