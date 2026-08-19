'use client';

import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';

export type UserRole = 'employee' | 'hr_admin';

export interface User {
  id: string;
  email: string;
  name: string;
  role: UserRole;
}

interface AuthContextType {
  user: User | null;
  isLoading: boolean;
  /**
   * True when Microsoft sign-in is configured and required. The login page shows
   * the Microsoft button; whether it *also* offers email/password is a separate
   * server decision (`localLoginEnabled`).
   */
  ssoEnabled: boolean;
  /**
   * What this session may do, as permission keys from the server's `Permissions` catalogue.
   *
   * Used to decide which console sections to render. Not a control: every endpoint behind them
   * is gated server-side and re-decided per request, so a client that lies to itself about this
   * list gets a 403 rather than data.
   */
  permissions: string[];
  /** Full-page navigation target that starts the Entra flow, when SSO is on. */
  signInUrl: string | null;
  /** Whether the email/password form may be shown. Decided by the server. */
  localLoginEnabled: boolean;
  /**
   * TEMPORARY — the server is standing in for Microsoft while the Entra app registration is
   * pending, so the Microsoft button should call {@link devLogin} instead of navigating to
   * Microsoft. Server-decided, like every other flag here: the page cannot offer a door the
   * backend has not opened.
   */
  devSignIn: boolean;
  /** The single address that bypass signs in as, for the button to name. */
  devSignInEmail: string | null;
  /**
   * TEMPORARY — signs in as the server's configured dev account.
   *
   * No arguments on purpose: the identity lives in the backend's gitignored local config, and a
   * caller that could name its own address would be the vulnerability this whole layer avoids.
   */
  devLogin: () => Promise<User>;
  /** Re-reads the session from the server. */
  refresh: () => Promise<void>;
  /**
   * Email/password sign-in.
   *
   * <p>Goes to the server, which creates a real session — the role comes back from
   * the same allowlist Entra uses, not from the caller. It used to fabricate a user
   * in the browser and store it in localStorage, which meant the UI could look
   * signed in as `employee@company.com` while every API call was rejected.
   */
  login: (email: string, password: string, role: UserRole) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/** Shape of `GET /api/auth/me`. */
interface Me {
  authenticated: boolean;
  ssoEnabled: boolean;
  signInUrl: string | null;
  localLoginEnabled: boolean;
  /** TEMPORARY — see AuthProperties.devSignInEmail on the backend. */
  devSignIn?: boolean;
  devSignInEmail?: string | null;
  user: User | null;
  permissions?: string[];
}

/**
 * Mirrors the keys in `Permissions.java`, one per area of the admin console.
 *
 * These hide a tab the session cannot open. They are not the control — every endpoint behind a
 * tab is gated server-side and re-decided per request — so a client that ignores them collects
 * 403s rather than data.
 */
export const ADMIN_TICKETS = 'admin.tickets';
export const ADMIN_TICKETS_SENSITIVE = 'admin.tickets.sensitive';
export const ADMIN_DIGEST = 'admin.digest';
export const ADMIN_KNOWLEDGE = 'admin.knowledge';
export const ADMIN_ACCESS = 'admin.access';
export const ADMIN_PAYROLL = 'admin.payroll';
/** Gates the audit trail and the sign-in list. */
export const ADMIN_AUDIT = 'admin.audit';

const DEMO_KEY = 'chatbot_user';

export function ChatbotAuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [ssoEnabled, setSsoEnabled] = useState(false);
  const [signInUrl, setSignInUrl] = useState<string | null>(null);
  const [localLoginEnabled, setLocalLoginEnabled] = useState(false);
  const [devSignIn, setDevSignIn] = useState(false);
  const [devSignInEmail, setDevSignInEmail] = useState<string | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/auth/me', { cache: 'no-store' });
      if (!res.ok) throw new Error(String(res.status));
      const me = (await res.json()) as Me;

      setSsoEnabled(me.ssoEnabled);
      setSignInUrl(me.signInUrl);
      setLocalLoginEnabled(me.localLoginEnabled);
      setDevSignIn(me.devSignIn === true);
      setDevSignInEmail(me.devSignInEmail ?? null);
      setPermissions(me.permissions ?? []);

      // The session is the only source of identity. Nothing is read from
      // localStorage any more: a stored user was how the app came to show
      // `employee@company.com` as signed in while the backend knew no such session.
      setUser(me.authenticated ? me.user : null);
      localStorage.removeItem(DEMO_KEY);
    } catch {
      // The backend is unreachable. Claim nothing — an identity invented here
      // cannot be backed by a session, and showing one would be a lie the rest of
      // the app then acts on.
      setSsoEnabled(false);
      setSignInUrl(null);
      setLocalLoginEnabled(false);
      setDevSignIn(false);
      setDevSignInEmail(null);
      setPermissions([]);
      setUser(null);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const login = async (email: string, password: string, _role: UserRole) => {
    if (!email || !password) {
      throw new Error('Email and password required');
    }

    const res = await fetch('/api/auth/local', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!res.ok) {
      const body = (await res.json().catch(() => null)) as { error?: string } | null;
      throw new Error(body?.error ?? 'Sign-in failed');
    }

    // Whether this account is an admin is the server's answer, not the caller's —
    // which is why `role` is ignored here.
    setUser((await res.json()) as User);
  };

  /**
   * TEMPORARY — the Entra stand-in. Deleted along with the backend endpoint once the app
   * registration is approved; `ssoSignIn` on the login page goes back to being one branch.
   */
  const devLogin = async () => {
    const res = await fetch('/api/auth/dev', { method: 'POST' });

    if (!res.ok) {
      // 404 means the server has the property unset — the bypass is off, which is the state
      // this code should eventually always find.
      throw new Error(
        res.status === 404
          ? 'Temporary sign-in is switched off on the server.'
          : 'Sign-in failed'
      );
    }

    const signedIn = (await res.json()) as User;
    setUser(signedIn);
    // The permission list is computed server-side per session, and the one held here was read
    // before this session existed. Without this the admin console renders nothing on first
    // load and only fills in after a manual reload.
    await refresh();
    return signedIn;
  };

  const logout = async () => {
    localStorage.removeItem(DEMO_KEY);
    setUser(null);

    {
      // Clears the server session and the cookie. The Microsoft session itself is
      // untouched, so signing back in is one click and does not re-prompt for a
      // password — which is the expected behaviour for a corporate SSO app, not a
      // bug. A full sign-out would need a redirect to Entra's logout endpoint.
      try {
        await fetch('/api/auth/logout', { method: 'POST' });
      } catch {
        // Best effort — the local state is already cleared.
      }
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        ssoEnabled,
        permissions,
        signInUrl,
        localLoginEnabled,
        devSignIn,
        devSignInEmail,
        devLogin,
        refresh,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useChatbotAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useChatbotAuth must be used within ChatbotAuthProvider');
  }
  return context;
}
