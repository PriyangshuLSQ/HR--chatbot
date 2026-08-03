import Link from 'next/link';

/**
 * The 404 page.
 *
 * <p>Two links rather than one, because there are two kinds of visitor who land
 * here and they want opposite things: an employee who mistyped a URL wants the
 * assistant, and an admin who followed a stale link wants the console. Guessing
 * between them and offering only one would send half the traffic to the wrong page.
 *
 * <p>The admin link is offered unconditionally and that is fine — it is a link, not
 * an entitlement. Anyone without access lands on the console's own "you don't have
 * access" screen, and every API behind it is gated server-side regardless. Reading
 * the session here would make this a client component and cost a loading flicker on
 * a page whose whole job is to render instantly.
 */
export default function NotFound() {
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
      <main
        className="card"
        style={{ padding: '2.5rem 2rem', maxWidth: 480, textAlign: 'center' }}
      >
        <p
          style={{
            fontSize: '2.75rem',
            fontWeight: 800,
            lineHeight: 1,
            color: 'var(--primary)',
            marginBottom: '0.75rem',
          }}
        >
          404
        </p>

        <h1 style={{ fontSize: '1.125rem', fontWeight: 700, marginBottom: '0.5rem' }}>
          We couldn&apos;t find that page
        </h1>

        <p
          style={{
            fontSize: '0.875rem',
            color: 'var(--muted-foreground)',
            lineHeight: 1.6,
            marginBottom: '1.5rem',
          }}
        >
          The link may be out of date, or the address may have a typo in it. Nothing has gone
          wrong with your account.
        </p>

        <div
          style={{
            display: 'flex',
            gap: '0.625rem',
            justifyContent: 'center',
            flexWrap: 'wrap',
          }}
        >
          <Link href="/chat" className="btn btn-primary">
            Ask the assistant
          </Link>
          <Link href="/admin" className="btn btn-secondary">
            HR admin console
          </Link>
        </div>
      </main>
    </div>
  );
}
