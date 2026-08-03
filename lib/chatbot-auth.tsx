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
  /** Full-page navigation target that starts the Entra flow, when SSO is on. */
  signInUrl: string | null;
  /** Whether the email/password form may be shown. Decided by the server. */
  localLoginEnabled: boolean;
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
  user: User | null;
}

const DEMO_KEY = 'chatbot_user';

export function ChatbotAuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [ssoEnabled, setSsoEnabled] = useState(false);
  const [signInUrl, setSignInUrl] = useState<string | null>(null);
  const [localLoginEnabled, setLocalLoginEnabled] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/auth/me', { cache: 'no-store' });
      if (!res.ok) throw new Error(String(res.status));
      const me = (await res.json()) as Me;

      setSsoEnabled(me.ssoEnabled);
      setSignInUrl(me.signInUrl);
      setLocalLoginEnabled(me.localLoginEnabled);

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
      value={{ user, isLoading, ssoEnabled, signInUrl, localLoginEnabled, refresh, login, logout }}
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
