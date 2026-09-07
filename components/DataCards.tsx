'use client';

/**
 * Cards that render live Darwinbox data inside a chat message.
 *
 * Visual encoding notes:
 *  - Leave uses a part-of-whole meter per leave type: one hue for "used",
 *    neutral for the remainder, with the number always direct-labelled so the
 *    value never depends on reading the bar (or on color at all).
 *  - Request states use the reserved status palette and always pair the color
 *    with an icon AND a text label, so state is never carried by hue alone.
 */

import {
  formatCurrency,
  statusLabel,
  statusTone,
  type LeaveBalance,
  type PayslipSummary,
  type RequestStatus,
  type TrackedRequest,
} from '@/lib/darwinbox';
import type { Ticket } from '@/lib/hr-store';
import { ROUTE_LABELS } from '@/lib/hr-store';
import {
  AlertIcon,
  CalendarIcon,
  CheckCircleIcon,
  ClockIcon,
  LaptopIcon,
  ShieldIcon,
  TicketIcon,
  WalletIcon,
} from './Icons';

// ---------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------

function CardShell({
  icon,
  title,
  meta,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  meta?: string;
  children: React.ReactNode;
}) {
  return (
    <section
      className="card anim-scale"
      style={{ marginTop: '0.75rem', overflow: 'hidden' }}
      aria-label={title}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '0.5rem',
          padding: '0.75rem 0.9375rem',
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface-2)',
        }}
      >
        <span style={{ color: 'var(--primary)', display: 'flex' }}>{icon}</span>
        <h4 style={{ fontSize: '0.8125rem', fontWeight: 700, letterSpacing: '0.01em' }}>{title}</h4>
        {meta && (
          <span style={{ marginLeft: 'auto', fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>
            {meta}
          </span>
        )}
      </header>
      <div style={{ padding: '0.9375rem' }}>{children}</div>
    </section>
  );
}

function StatusBadge({ status }: { status: RequestStatus }) {
  const tone = statusTone(status);
  const Icon =
    tone === 'success' ? CheckCircleIcon : tone === 'error' ? AlertIcon : ClockIcon;
  return (
    <span className={`badge badge-${tone === 'info' ? 'neutral' : tone}`}>
      <Icon size={12} />
      {statusLabel(status)}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Leave balance
// ---------------------------------------------------------------------------

export function LeaveBalanceCard({ balances }: { balances: LeaveBalance[] }) {
  const total = balances.reduce((s, b) => s + b.available, 0);

  return (
    <CardShell
      icon={<CalendarIcon size={15} />}
      title="Leave balance"
      meta={`${total} days available`}
    >
      <ul style={{ display: 'grid', gap: '0.875rem', listStyle: 'none' }}>
        {balances.map((b) => {
          const pct = b.entitled > 0 ? Math.min(100, (b.used / b.entitled) * 100) : 0;
          return (
            <li key={b.type}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'baseline',
                  gap: '0.5rem',
                  marginBottom: '0.375rem',
                }}
              >
                <span style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{b.type}</span>
                <span style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>
                  <strong style={{ color: 'var(--foreground)', fontVariantNumeric: 'tabular-nums' }}>
                    {b.available}
                  </strong>{' '}
                  of {b.entitled} left
                </span>
              </div>
              {/* Meter: used portion in one hue, remainder neutral. The value is
                  labelled above, so the bar is reinforcement, not the only cue. */}
              <div
                style={{
                  height: 6,
                  borderRadius: 999,
                  background: 'var(--chart-remainder)',
                  overflow: 'hidden',
                }}
                role="img"
                aria-label={`${b.type}: ${b.used} of ${b.entitled} days used`}
              >
                <div
                  style={{
                    width: `${pct}%`,
                    height: '100%',
                    borderRadius: 999,
                    background: 'var(--chart-series-1)',
                    transition: 'width 0.5s cubic-bezier(0.22, 1, 0.36, 1)',
                  }}
                />
              </div>
              {b.pending > 0 && (
                <p style={{ fontSize: '0.75rem', color: 'var(--warning-ink)', marginTop: '0.3125rem' }}>
                  {b.pending} day(s) pending approval
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// Payslip
// ---------------------------------------------------------------------------

export function PayslipCard({ payslip }: { payslip: PayslipSummary }) {
  const Row = ({ label, amount, muted }: { label: string; amount: number; muted?: boolean }) => (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        gap: '1rem',
        padding: '0.3125rem 0',
        fontSize: '0.8125rem',
        color: muted ? 'var(--muted-foreground)' : 'var(--foreground)',
      }}
    >
      <span>{label}</span>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(amount)}</span>
    </div>
  );

  return (
    <CardShell icon={<WalletIcon size={15} />} title="Payslip" meta={payslip.month}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(180px, 100%), 1fr))',
          gap: '1.25rem',
        }}
      >
        <div>
          <p className="label-caps" style={{ marginBottom: '0.375rem' }}>
            Earnings
          </p>
          {payslip.earnings.map((e) => (
            <Row key={e.label} label={e.label} amount={e.amount} />
          ))}
          <div className="divider" style={{ margin: '0.4375rem 0' }} />
          <Row label="Gross" amount={payslip.gross} />
        </div>
        <div>
          <p className="label-caps" style={{ marginBottom: '0.375rem' }}>
            Deductions
          </p>
          {payslip.deductions.map((d) => (
            <Row key={d.label} label={d.label} amount={d.amount} muted />
          ))}
        </div>
      </div>

      <div
        style={{
          marginTop: '1rem',
          padding: '0.75rem 0.875rem',
          borderRadius: 'var(--radius)',
          background: 'var(--primary-soft)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: '1rem',
          flexWrap: 'wrap',
        }}
      >
        <div>
          <p className="label-caps" style={{ color: 'var(--info-ink)' }}>
            Net paid
          </p>
          <p
            style={{
              fontSize: '1.375rem',
              fontWeight: 700,
              color: 'var(--info-ink)',
              lineHeight: 1.2,
            }}
          >
            {formatCurrency(payslip.net)}
          </p>
        </div>
        <p style={{ fontSize: '0.75rem', color: 'var(--info-ink)', textAlign: 'right' }}>
          FY to date
          <br />
          <strong>{formatCurrency(payslip.ytdGross)}</strong> gross
        </p>
      </div>
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// Tracked requests
// ---------------------------------------------------------------------------

const KIND_ICON = {
  leave: CalendarIcon,
  expense: WalletIcon,
  asset: LaptopIcon,
} as const;

export function RequestsCard({
  requests,
  heading,
}: {
  requests: TrackedRequest[];
  heading: string;
}) {
  const Icon = KIND_ICON[requests[0]?.kind ?? 'leave'];

  return (
    <CardShell icon={<Icon size={15} />} title={heading} meta={`${requests.length} on record`}>
      <ul style={{ display: 'grid', gap: '0.75rem', listStyle: 'none' }}>
        {requests.map((r) => (
          <li
            key={r.id}
            style={{
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius)',
              padding: '0.75rem 0.875rem',
              background: 'var(--surface)',
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: '0.75rem',
                alignItems: 'flex-start',
                flexWrap: 'wrap',
              }}
            >
              <div style={{ minWidth: 0 }}>
                <p style={{ fontSize: '0.8125rem', fontWeight: 650 }}>{r.title}</p>
                <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>{r.detail}</p>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                {r.amount != null && (
                  <span
                    style={{
                      fontSize: '0.8125rem',
                      fontWeight: 650,
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {formatCurrency(r.amount)}
                  </span>
                )}
                <StatusBadge status={r.status} />
              </div>
            </div>

            {/* Step trail: a filled dot marks a completed step. */}
            <ol
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: '0.5rem 1rem',
                marginTop: '0.625rem',
                listStyle: 'none',
              }}
            >
              {r.timeline.map((step) => (
                <li
                  key={step.label}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.375rem',
                    fontSize: '0.6875rem',
                    color: step.done ? 'var(--foreground-secondary)' : 'var(--faint)',
                  }}
                >
                  <span
                    aria-hidden
                    style={{
                      width: 7,
                      height: 7,
                      borderRadius: '50%',
                      flexShrink: 0,
                      background: step.done ? 'var(--chart-series-1)' : 'transparent',
                      border: step.done ? 'none' : '1.5px solid var(--border-strong)',
                    }}
                  />
                  <span>
                    {step.label}
                    <span style={{ color: 'var(--faint)' }}> · {step.at}</span>
                  </span>
                </li>
              ))}
            </ol>

            <p
              style={{
                fontSize: '0.6875rem',
                color: 'var(--muted-foreground)',
                marginTop: '0.5rem',
                paddingTop: '0.5rem',
                borderTop: '1px dashed var(--border)',
              }}
            >
              <strong style={{ color: 'var(--foreground-secondary)' }}>{r.id}</strong> · with{' '}
              {r.approver}
            </p>
          </li>
        ))}
      </ul>
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// Ticket confirmation
// ---------------------------------------------------------------------------

export function TicketCard({ ticket }: { ticket: Ticket }) {
  const critical = ticket.priority === 'critical';

  return (
    <section
      className="card anim-scale"
      style={{
        marginTop: '0.75rem',
        overflow: 'hidden',
        borderColor: critical ? 'var(--error)' : 'var(--border)',
      }}
      aria-label={`Ticket ${ticket.id}`}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '0.5rem',
          padding: '0.75rem 0.9375rem',
          background: critical ? 'var(--error-soft)' : 'var(--surface-2)',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <span style={{ color: critical ? 'var(--error-ink)' : 'var(--primary)', display: 'flex' }}>
          {critical ? <ShieldIcon size={15} /> : <TicketIcon size={15} />}
        </span>
        <h4
          style={{
            fontSize: '0.8125rem',
            fontWeight: 700,
            color: critical ? 'var(--error-ink)' : 'var(--foreground)',
          }}
        >
          {ticket.id}
        </h4>
        {/* Priority is stated in words, not implied by the border colour. */}
        <span className={`badge badge-${critical ? 'error' : 'info'}`} style={{ marginLeft: 'auto' }}>
          {critical ? <AlertIcon size={11} /> : <ClockIcon size={11} />}
          {critical ? 'Critical priority' : 'Normal priority'}
        </span>
      </header>

      <div style={{ padding: '0.9375rem', display: 'grid', gap: '0.625rem' }}>
        <div style={{ display: 'flex', gap: '0.5rem', fontSize: '0.8125rem' }}>
          <span style={{ color: 'var(--muted-foreground)', minWidth: 74 }}>Assigned to</span>
          <strong>{ticket.assignee}</strong>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem', fontSize: '0.8125rem' }}>
          <span style={{ color: 'var(--muted-foreground)', minWidth: 74 }}>Routed to</span>
          <span>{ROUTE_LABELS[ticket.route]}</span>
        </div>
        {/*
          No "Channel" row. Which surface the employee happened to raise this from tells them
          nothing they do not already know — they are looking at it. The field is still stamped on
          the ticket for HR's own analytics; it is just not something to show back to them.
        */}

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.375rem', marginTop: '0.125rem' }}>
          {ticket.tags.map((tag) => (
            <span key={tag} className="badge badge-neutral">
              #{tag}
            </span>
          ))}
        </div>

        {ticket.confidential && (
          <p
            style={{
              fontSize: '0.75rem',
              color: 'var(--error-ink)',
              background: 'var(--error-soft)',
              padding: '0.5rem 0.625rem',
              borderRadius: 'var(--radius-sm)',
              display: 'flex',
              gap: '0.375rem',
              alignItems: 'flex-start',
            }}
          >
            <ShieldIcon size={13} />
            <span>
              Confidential — visible only to the HR Head and your HRBP. Not shared with your
              reporting manager.
            </span>
          </p>
        )}
      </div>
    </section>
  );
}
