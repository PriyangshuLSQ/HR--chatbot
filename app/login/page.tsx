'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useChatbotAuth } from '@/lib/chatbot-auth';
import { PoweredByLeadSquared, ROBIN_NAME, RobinAvatar } from '@/components/Robin';
import {
  CalendarIcon,
  CheckIcon,
  LaptopIcon,
  ShieldIcon,
  SparkIcon,
  TeamsIcon,
  WalletIcon,
} from '@/components/Icons';

/**
 * Sign-in. Microsoft Entra ID SSO is the primary path, with email/password kept as a fallback for
 * accounts outside the tenant.
 *
 * Auth is simulated: no credential is verified or transmitted. Point `login()`
 * in lib/chatbot-auth at MSAL or your IdP to make it real.
 */

const HIGHLIGHTS = [
  { Icon: CalendarIcon, title: 'Live leave balances', body: 'Straight from Darwinbox, per employee' },
  { Icon: WalletIcon, title: 'Payslip summaries', body: 'Gross, net, TDS and year to date' },
  { Icon: LaptopIcon, title: 'Request tracking', body: 'Leave, expense and asset status' },
  {
    Icon: ShieldIcon,
    title: 'Confidential routing',
    body: 'Sensitive matters go direct to your HRBP',
  },
];

export default function LoginPage() {
  const router = useRouter();
  const {
    login,
    user,
    isLoading,
    ssoEnabled,
    signInUrl,
    localLoginEnabled,
    devSignIn,
    devSignInEmail,
    devLogin,
  } = useChatbotAuth();
  const [mode, setMode] = useState<'employee' | 'admin'>('employee');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState<null | 'sso' | 'password'>(null);
  const [error, setError] = useState('');

  // The theme is applied before paint by the bootstrap script in the root layout, so this page
  // needs no restore of its own. It has no toggle either — signing in is not the moment to offer
  // one, and whatever was chosen last is already in force.

  // A failed Entra sign-in comes back to /login?error. Without this the browser
  // lands on a pristine login page with no hint that anything went wrong, and the
  // natural read is "my click did nothing".
  useEffect(() => {
    if (!window.location.search.includes('error')) return;
    setError(
      'Microsoft sign-in did not complete. Try again — if it keeps failing, your account may not have access to this app.'
    );
    // Clear it so a later reload does not resurrect a stale error.
    window.history.replaceState({}, '', '/login');
  }, []);

  // Already signed in — coming back to /login should not strand you here. The
  // destination follows the role on the token, not the tab that happens to be
  // selected: with SSO, what someone may see is not theirs to pick.
  useEffect(() => {
    if (isLoading || !user) return;
    router.replace(user.role === 'hr_admin' ? '/admin' : '/chat');
  }, [isLoading, user, router]);

  const go = async (mail: string, pass: string) => {
    try {
      await login(mail, pass, mode === 'admin' ? 'hr_admin' : 'employee');
      // The redirect effect above sends you on once the session lands, using the
      // role the server assigned. The tab is a hint about what you came for, not a
      // grant — asking for the admin console does not make you an admin.
      router.push(mode === 'admin' ? '/admin' : '/chat');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed');
      setBusy(null);
    }
  };

  const ssoSignIn = () => {
    setError('');
    setBusy('sso');

    // TEMPORARY, while the Entra app registration is pending approval. The server has offered a
    // stand-in, so this button establishes a session directly rather than starting a flow that
    // cannot complete yet. Delete this branch — and the two properties behind it — once the real
    // registration lands; everything below is the permanent path and is untouched.
    if (devSignIn) {
      void devLogin()
        .then((signedIn) => {
          router.push(signedIn.role === 'hr_admin' ? '/admin' : '/chat');
        })
        .catch((err: unknown) => {
          setError(err instanceof Error ? err.message : 'Sign-in failed');
          setBusy(null);
        });
      return;
    }

    if (ssoEnabled && signInUrl) {
      // A full-page navigation, not fetch(): the authorization code flow is a
      // chain of redirects to Microsoft and back, which XHR cannot follow. The
      // browser returns to /login/oauth2/code/entra, the backend exchanges the
      // code, sets the session cookie, and redirects on to /chat.
      window.location.href = signInUrl;
      return;
    }

    // No Entra credentials configured — the server is open, so this is the demo
    // identity rather than a pretend token.
    const mail = mode === 'admin' ? 'hr@company.com' : 'employee@company.com';
    setTimeout(() => void go(mail, 'demo'), 400);
  };

  const passwordSignIn = (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) {
      setError('Enter both your email and password.');
      return;
    }
    setError('');
    setBusy('password');
    void go(email, password);
  };

  const demoSignIn = () => {
    const mail = mode === 'admin' ? 'hr@company.com' : 'employee@company.com';
    setEmail(mail);
    setPassword('demo');
    setError('');
    setBusy('password');
    void go(mail, 'demo');
  };

  return (
    <main style={{ minHeight: '100vh', background: 'var(--background)' }}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(330px, 1fr))',
          maxWidth: 1080,
          margin: '0 auto',
          alignItems: 'center',
          gap: '2.5rem',
          padding: '2.5rem 1.25rem',
          minHeight: '100vh',
        }}
      >
        {/* Value panel ---------------------------------------------------- */}
        <section className="anim-in">
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.625rem',
              marginBottom: '1.5rem',
            }}
          >
            <RobinAvatar size={44} rounded="badge" ring />
            <div>
              <p style={{ fontSize: '1rem', fontWeight: 700, lineHeight: 1.2 }}>{ROBIN_NAME}</p>
              <p style={{ fontSize: '0.75rem', color: 'var(--muted-foreground)' }}>LeadSquared</p>
            </div>
          </div>

          <h1
            style={{
              fontSize: 'clamp(1.5rem, 4vw, 2.125rem)',
              fontWeight: 700,
              lineHeight: 1.18,
              letterSpacing: '-0.02em',
              marginBottom: '0.75rem',
            }}
          >
            HR answers in seconds,
            <br />
            any hour of the day.
          </h1>
          <p
            style={{
              fontSize: '0.9375rem',
              color: 'var(--foreground-secondary)',
              maxWidth: 440,
              marginBottom: '1.75rem',
            }}
          >
            Ask about leave, payroll, benefits or policy in your own words — typos and
            half-sentences are fine. Anything the assistant can&apos;t answer reaches a human with
            the full context attached.
          </p>

          <ul style={{ display: 'grid', gap: '0.875rem', listStyle: 'none', maxWidth: 440 }}>
            {HIGHLIGHTS.map(({ Icon, title, body }, i) => (
              <li
                key={title}
                className="anim-in"
                style={{
                  display: 'flex',
                  gap: '0.75rem',
                  alignItems: 'flex-start',
                  animationDelay: `${0.06 * i}s`,
                }}
              >
                <span
                  style={{
                    width: 30,
                    height: 30,
                    borderRadius: 8,
                    flexShrink: 0,
                    display: 'grid',
                    placeItems: 'center',
                    background: 'var(--primary-soft)',
                    color: 'var(--primary)',
                  }}
                >
                  <Icon size={15} />
                </span>
                <div>
                  <p style={{ fontSize: '0.875rem', fontWeight: 650 }}>{title}</p>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--muted-foreground)' }}>{body}</p>
                </div>
              </li>
            ))}
          </ul>
        </section>

        {/* Sign-in card --------------------------------------------------- */}
        <section
          className="card anim-scale"
          style={{
            padding: '1.75rem',
            boxShadow: 'var(--shadow-lg)',
            maxWidth: 420,
            width: '100%',
            justifySelf: 'center',
          }}
        >
          <div
            role="tablist"
            aria-label="Sign in as"
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 4,
              padding: 4,
              background: 'var(--surface-2)',
              borderRadius: 'var(--radius)',
              marginBottom: '1.5rem',
            }}
          >
            {(['employee', 'admin'] as const).map((m) => (
              <button
                key={m}
                role="tab"
                aria-selected={mode === m}
                onClick={() => setMode(m)}
                style={{
                  padding: '0.5rem',
                  borderRadius: 'var(--radius-sm)',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  background: mode === m ? 'var(--surface)' : 'transparent',
                  color: mode === m ? 'var(--foreground)' : 'var(--muted-foreground)',
                  boxShadow: mode === m ? 'var(--shadow-xs)' : 'none',
                  transition: 'all 0.16s ease',
                }}
              >
                {m === 'employee' ? 'Employee' : 'HR Admin'}
              </button>
            ))}
          </div>

          <h2 style={{ fontSize: '1.125rem', fontWeight: 700, marginBottom: '0.25rem' }}>
            {mode === 'admin' ? 'HR admin sign in' : 'Welcome back'}
          </h2>
          <p
            style={{
              fontSize: '0.8125rem',
              color: 'var(--muted-foreground)',
              marginBottom: '1.25rem',
            }}
          >
            {mode === 'admin'
              ? 'Manage escalations, the weekly digest and the knowledge base.'
              : 'Single sign-on is the fastest way in.'}
          </p>

          <button
            className="btn btn-secondary"
            style={{ width: '100%', padding: '0.75rem' }}
            onClick={ssoSignIn}
            disabled={busy !== null || isLoading}
          >
            <TeamsIcon size={18} />
            {busy === 'sso'
              ? devSignIn
                ? 'Signing in…'
                : 'Redirecting to Microsoft…'
              : 'Continue with Microsoft SSO'}
          </button>

          {/*
            TEMPORARY — remove with the bypass. Says plainly that this button is not reaching
            Microsoft yet and which account it lands on, because a sign-in screen that silently
            does something other than what it says is how a stand-in survives past its welcome.
          */}
          {devSignIn && (
            <p
              style={{
                fontSize: '0.75rem',
                color: 'var(--error-ink)',
                textAlign: 'center',
                marginTop: '0.75rem',
                lineHeight: 1.5,
              }}
            >
              Temporary sign-in — the Entra app registration is still pending, so this signs in
              as <strong>{devSignInEmail}</strong> without Microsoft.
            </p>
          )}

          {ssoEnabled && !devSignIn && (
            <p
              style={{
                fontSize: '0.75rem',
                color: 'var(--muted-foreground)',
                textAlign: 'center',
                marginTop: '0.75rem',
                lineHeight: 1.5,
              }}
            >
              Your LeadSquared work account is the only way in. Whether you see the HR admin
              dashboard is set by your account, not chosen here.
            </p>
          )}


          {/*
            Shown whenever the server says email/password may create a session —
            which is any time Entra is absent, and alongside Entra if
            `hr.auth.local-login` is turned on. Gated on the server's answer rather
            than on `!ssoEnabled` so the page never offers a way in that the backend
            will refuse.
          */}
          {localLoginEnabled && (
          <>
          <div
            style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', margin: '1.25rem 0' }}
          >
            <hr className="divider" style={{ flex: 1 }} />
            <span style={{ fontSize: '0.75rem', color: 'var(--faint)' }}>or use your email</span>
            <hr className="divider" style={{ flex: 1 }} />
          </div>

          <form onSubmit={passwordSignIn} style={{ display: 'grid', gap: '0.875rem' }}>
            <label>
              <span className="label-caps">Work email</span>
              <input
                className="input"
                style={{ marginTop: '0.3125rem' }}
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder={mode === 'admin' ? 'hr@company.com' : 'you@company.com'}
                autoComplete="username"
              />
            </label>
            <label>
              <span className="label-caps">Password</span>
              <input
                className="input"
                style={{ marginTop: '0.3125rem' }}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                autoComplete="current-password"
              />
            </label>

            {error && (
              <p
                className="badge badge-error"
                style={{ display: 'flex', width: '100%', justifyContent: 'flex-start' }}
                role="alert"
              >
                {error}
              </p>
            )}

            <button
              type="submit"
              className="btn btn-primary"
              style={{ width: '100%', padding: '0.75rem' }}
              disabled={busy !== null}
            >
              {busy === 'password' ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          <button
            className="btn btn-ghost btn-sm"
            style={{ width: '100%', marginTop: '0.75rem' }}
            onClick={demoSignIn}
            disabled={busy !== null}
          >
            <SparkIcon size={14} />
            Use the demo {mode === 'admin' ? 'admin' : 'employee'} account
          </button>
          </>
          )}

          <div
            style={{
              marginTop: '1.25rem',
              paddingTop: '1rem',
              borderTop: '1px solid var(--border)',
              display: 'grid',
              gap: '0.375rem',
            }}
          >
            {[
              ssoEnabled
                ? 'Authentication is handled by Microsoft — no password reaches this app'
                : 'No credentials leave your device in this demo',
              'Feedback is collected anonymously',
            ].map((line) => (
              <p
                key={line}
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--muted-foreground)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.375rem',
                }}
              >
                <span style={{ color: 'var(--success)', display: 'flex' }}>
                  <CheckIcon size={13} />
                </span>
                {line}
              </p>
            ))}
          </div>

          <div style={{ marginTop: '1.25rem' }}>
            <PoweredByLeadSquared />
          </div>
        </section>
      </div>
    </main>
  );
}
