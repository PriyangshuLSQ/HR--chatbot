'use client';

/**
 * Small chart set for the admin dashboard.
 *
 * Encoding decisions, deliberately constrained:
 *  - Every chart here is SINGLE-SERIES, so no categorical palette is in play
 *    and no legend box is needed — the title names the series. Magnitude uses
 *    the sequential slot-1 blue; the remainder of a proportion is neutral.
 *  - Thumbs up/down is NOT encoded as green-vs-red: that pair fails CVD
 *    separation (ΔE 4.1 deutan). Satisfaction is shown as one blue arc/bar
 *    against a neutral remainder, with the numbers direct-labelled.
 *  - Bars carry a hover tooltip; values that matter are labelled in text so
 *    nothing depends on reading a pixel height.
 */

import { useId, useState } from 'react';

// ---------------------------------------------------------------------------
// Vertical bar chart — volume over time
// ---------------------------------------------------------------------------

export interface BarDatum {
  label: string;
  value: number;
}

export function BarChart({
  data,
  unit = '',
  height = 150,
}: {
  data: BarDatum[];
  unit?: string;
  height?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(...data.map((d) => d.value), 1);
  const peak = data.reduce((best, d, i) => (d.value > data[best].value ? i : best), 0);

  return (
    <div>
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: '2px',
          height,
          position: 'relative',
          borderBottom: '1px solid var(--chart-axis)',
        }}
      >
        {/* Recessive gridlines at 25/50/75% */}
        {[0.25, 0.5, 0.75].map((f) => (
          <span
            key={f}
            aria-hidden
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: `${f * 100}%`,
              height: 1,
              background: 'var(--chart-grid)',
            }}
          />
        ))}

        {data.map((d, i) => {
          const pct = (d.value / max) * 100;
          const isHover = hover === i;
          return (
            <div
              key={d.label}
              style={{
                flex: 1,
                height: '100%',
                display: 'flex',
                alignItems: 'flex-end',
                position: 'relative',
                // Hit target spans the full column, not just the bar.
                cursor: 'default',
              }}
              onMouseEnter={() => setHover(i)}
              onMouseLeave={() => setHover(null)}
            >
              <div
                style={{
                  width: '100%',
                  height: `${pct}%`,
                  minHeight: 3,
                  // 4px rounded data-end, anchored square to the baseline.
                  borderRadius: '4px 4px 0 0',
                  background: 'var(--chart-series-1)',
                  opacity: hover === null || isHover ? 1 : 0.55,
                  transition: 'opacity 0.16s ease',
                  transformOrigin: 'bottom',
                  animation: `growBar 0.5s ${i * 0.04}s cubic-bezier(0.22,1,0.36,1) both`,
                }}
              />
              {isHover && (
                <div
                  role="tooltip"
                  style={{
                    position: 'absolute',
                    bottom: `calc(${pct}% + 6px)`,
                    left: '50%',
                    transform: 'translateX(-50%)',
                    background: 'var(--foreground)',
                    color: 'var(--background)',
                    padding: '0.25rem 0.5rem',
                    borderRadius: 'var(--radius-sm)',
                    fontSize: '0.6875rem',
                    fontWeight: 600,
                    whiteSpace: 'nowrap',
                    zIndex: 2,
                    pointerEvents: 'none',
                    boxShadow: 'var(--shadow)',
                  }}
                >
                  {d.label}: {d.value.toLocaleString()}
                  {unit}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', gap: '2px', marginTop: '0.375rem' }}>
        {data.map((d, i) => (
          <span
            key={d.label}
            style={{
              flex: 1,
              textAlign: 'center',
              fontSize: '0.6875rem',
              color: i === peak ? 'var(--foreground-secondary)' : 'var(--muted-foreground)',
              fontWeight: i === peak ? 650 : 400,
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {d.label}
          </span>
        ))}
      </div>

      {/* Selective direct label: the peak only, never a number on every bar. */}
      <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)', marginTop: '0.5rem' }}>
        Peak <strong style={{ color: 'var(--foreground)' }}>{data[peak].label}</strong> at{' '}
        {data[peak].value.toLocaleString()}
        {unit}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Horizontal bars — ranked magnitude
// ---------------------------------------------------------------------------

export function RankedBars({
  rows,
  emptyNote,
}: {
  rows: { label: string; value: number; note?: string }[];
  emptyNote: string;
}) {
  if (!rows.length) {
    return (
      <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)', padding: '0.75rem 0' }}>
        {emptyNote}
      </p>
    );
  }
  const max = Math.max(...rows.map((r) => r.value), 1);

  return (
    <ul style={{ display: 'grid', gap: '0.75rem', listStyle: 'none' }}>
      {rows.map((r) => (
        <li key={r.label}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              gap: '0.75rem',
              marginBottom: '0.3125rem',
            }}
          >
            <span style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{r.label}</span>
            <span
              style={{
                fontSize: '0.8125rem',
                fontWeight: 700,
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {r.value}
            </span>
          </div>
          <div
            style={{
              height: 6,
              background: 'var(--chart-remainder)',
              borderRadius: 999,
              overflow: 'hidden',
            }}
            role="img"
            aria-label={`${r.label}: ${r.value}`}
          >
            <div
              style={{
                width: `${(r.value / max) * 100}%`,
                height: '100%',
                background: 'var(--chart-series-1)',
                borderRadius: 999,
              }}
            />
          </div>
          {r.note && (
            <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)', marginTop: '0.3125rem' }}>
              {r.note}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------------------
// Satisfaction dial — one value against a neutral remainder
// ---------------------------------------------------------------------------

export function SatisfactionDial({
  ratio,
  up,
  down,
  size = 116,
}: {
  ratio: number;
  up: number;
  down: number;
  size?: number;
}) {
  const gradientId = useId();
  const stroke = 9;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const filled = Math.max(0, Math.min(1, ratio)) * c;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
      <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
        <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }} aria-hidden>
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke="var(--chart-remainder)"
            strokeWidth={stroke}
          />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke={`url(#${gradientId})`}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={`${filled} ${c - filled}`}
            style={{ transition: 'stroke-dasharray 0.7s cubic-bezier(0.22,1,0.36,1)' }}
          />
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="var(--chart-series-1)" />
              <stop offset="100%" stopColor="var(--primary-hover)" />
            </linearGradient>
          </defs>
        </svg>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'grid',
            placeItems: 'center',
            textAlign: 'center',
          }}
        >
          <div>
            <p style={{ fontSize: '1.5rem', fontWeight: 700, lineHeight: 1.1 }}>
              {Math.round(ratio * 100)}%
            </p>
            <p style={{ fontSize: '0.625rem', color: 'var(--muted-foreground)' }}>helpful</p>
          </div>
        </div>
      </div>

      {/* Counts in text — the dial is reinforcement, not the only readout. */}
      <dl style={{ display: 'grid', gap: '0.4375rem', fontSize: '0.8125rem', minWidth: 120 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.75rem' }}>
          <dt style={{ color: 'var(--muted-foreground)' }}>Rated helpful</dt>
          <dd style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{up}</dd>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.75rem' }}>
          <dt style={{ color: 'var(--muted-foreground)' }}>Not helpful</dt>
          <dd style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{down}</dd>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.75rem' }}>
          <dt style={{ color: 'var(--muted-foreground)' }}>Total responses</dt>
          <dd style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{up + down}</dd>
        </div>
      </dl>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Stat tile
// ---------------------------------------------------------------------------

export function StatTile({
  label,
  value,
  delta,
  deltaGood,
  icon,
  note,
}: {
  label: string;
  value: string;
  delta?: string;
  deltaGood?: boolean;
  icon?: React.ReactNode;
  note?: string;
}) {
  return (
    <div className="card" style={{ padding: '1rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.625rem' }}>
        {icon && <span style={{ color: 'var(--primary)', display: 'flex' }}>{icon}</span>}
        <p className="label-caps">{label}</p>
      </div>
      <p style={{ fontSize: '1.75rem', fontWeight: 700, lineHeight: 1.1 }}>{value}</p>
      {delta && (
        // Direction is stated with an arrow glyph and words, not colour alone.
        <p
          style={{
            fontSize: '0.75rem',
            fontWeight: 600,
            marginTop: '0.3125rem',
            color: deltaGood ? 'var(--success-ink)' : 'var(--muted-foreground)',
          }}
        >
          {deltaGood ? '↑' : '↓'} {delta}
        </p>
      )}
      {note && (
        <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)', marginTop: '0.3125rem' }}>
          {note}
        </p>
      )}
    </div>
  );
}
