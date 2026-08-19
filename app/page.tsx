'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { RobinAvatar } from '@/components/Robin';

/**
 * The redirect stop-off on the way to /chat. Brief, but it is the first frame of the product
 * anyone sees, so it shows Robin's own mark rather than a generic chat bubble — the same
 * `RobinAvatar` used on the sign-in page and on every bot message, so there is one mark to change.
 *
 * Three layers, all decorative: a halo breathing out behind the logo, a ring tracing around it,
 * and the logo itself rising and settling. The global `prefers-reduced-motion` rule in
 * globals.css stops all three; the "Loading Robin…" line is what actually carries the state.
 */
const LOGO_PX = 64;
// Clearance, not decoration: the mark is a rounded square, so its corners reach ~0.71×LOGO_PX
// from the centre. A ring any tighter than that is crossed by the artwork instead of framing it.
const RING_PX = LOGO_PX + 30;

export default function Page() {
  const router = useRouter();

  useEffect(() => {
    router.push('/chat');
  }, [router]);

  return (
    <main style={{
      display: 'flex',
      minHeight: '100vh',
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: 'var(--background)',
      flexDirection: 'column',
      gap: '1rem',
    }}>
      <div style={{ textAlign: 'center' }}>
        <div
          style={{
            position: 'relative',
            width: RING_PX,
            height: RING_PX,
            margin: '0 auto 1.25rem',
            display: 'grid',
            placeItems: 'center',
          }}
        >
          {/* Breathing halo, behind everything. */}
          <span className="robin-loader-halo" aria-hidden />
          {/* One quarter-lit ring tracing the logo — the part that reads as "working". */}
          <span className="robin-loader-ring" aria-hidden />
          <span className="robin-loader-mark">
            <RobinAvatar size={LOGO_PX} rounded="badge" />
          </span>
        </div>
        <p style={{ color: 'var(--muted-foreground)', fontSize: '0.875rem' }}>Loading Robin…</p>
      </div>
      <style>{`
        .robin-loader-halo,
        .robin-loader-ring {
          position: absolute;
          inset: 0;
          border-radius: 50%;
          pointer-events: none;
        }

        .robin-loader-halo {
          background: var(--primary-soft);
          animation: robinLoaderHalo 2.4s cubic-bezier(0.4, 0, 0.6, 1) infinite;
        }

        .robin-loader-ring {
          /* Transparent on three sides, so the rotation is visible as a travelling arc
             rather than a ring that appears to stand still. */
          border: 2px solid transparent;
          border-top-color: var(--primary);
          border-right-color: var(--primary);
          animation: robinLoaderSpin 1.15s linear infinite;
        }

        .robin-loader-mark {
          display: block;
          line-height: 0;
          animation: robinLoaderBreathe 2.4s cubic-bezier(0.4, 0, 0.6, 1) infinite;
        }

        @keyframes robinLoaderSpin {
          to { transform: rotate(360deg); }
        }

        @keyframes robinLoaderHalo {
          0%, 100% { transform: scale(0.9); opacity: 0.55; }
          50% { transform: scale(1.12); opacity: 0.15; }
        }

        @keyframes robinLoaderBreathe {
          0%, 100% { transform: scale(1); }
          50% { transform: scale(0.94); }
        }
      `}</style>
    </main>
  );
}
