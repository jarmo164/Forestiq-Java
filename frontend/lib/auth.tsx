"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

type AuthContextValue = {
  initialized: boolean;
  authenticated: boolean;
  authError?: string;
  username?: string;
  subject?: string;
  roles: string[];
  /** @deprecated Tokens are held only by the BFF session. */
  accessToken?: string;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  retrySession: () => Promise<void>;
  accountManagement: () => Promise<void>;
  hasRole: (...roles: string[]) => boolean;
};

type SessionView = {
  authenticated: boolean;
  username?: string;
  subject?: string;
  roles?: string[];
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);
const sessionTimeoutMs = 10_000;

const keycloakConfig = {
  url: process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8180",
  realm: process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "forestiq",
};

function realmUrl(path: string) {
  return `${keycloakConfig.url.replace(/\/$/, "")}/realms/${keycloakConfig.realm}${path}`;
}

async function fetchSession(): Promise<SessionView> {
  const response = await fetch("/auth/session", {
    cache: "no-store",
    credentials: "same-origin",
    signal: AbortSignal.timeout(sessionTimeoutMs),
  });
  if (!response.ok) return { authenticated: false, roles: [] };
  return response.json() as Promise<SessionView>;
}

function sessionErrorMessage(error: unknown) {
  if (error instanceof DOMException && error.name === "TimeoutError") return "Tööruumi seansi kontrollimine võttis liiga kaua aega.";
  return "Tööruumi seansi kontrollimine ebaõnnestus. Kontrolli ühendust ja proovi uuesti.";
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [initialized, setInitialized] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [authError, setAuthError] = useState<string>();
  const [roles, setRoles] = useState<string[]>([]);
  const [username, setUsername] = useState<string>();
  const [subject, setSubject] = useState<string>();

  const applySession = useCallback((session: SessionView) => {
    setAuthenticated(session.authenticated);
    setUsername(session.authenticated ? session.username : undefined);
    setSubject(session.authenticated ? session.subject : undefined);
    setRoles(session.authenticated ? session.roles ?? [] : []);
  }, []);

  const refreshSession = useCallback(async () => {
    setAuthError(undefined);
    try {
      const session = await fetchSession();
      applySession(session);
    } catch (error) {
      setAuthError(sessionErrorMessage(error));
    } finally {
      setInitialized(true);
    }
  }, [applySession]);

  useEffect(() => {
    void refreshSession();
  }, [refreshSession]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void refreshSession();
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [refreshSession]);

  const value = useMemo<AuthContextValue>(
    () => ({
      initialized,
      authenticated,
      authError,
      username,
      subject,
      roles,
      accessToken: undefined,
      login: async () => {
        const returnTo = `${window.location.pathname}${window.location.search}`;
        window.location.assign(`/auth/login?returnTo=${encodeURIComponent(returnTo)}`);
      },
      logout: async () => {
        applySession({ authenticated: false, roles: [] });
        window.location.assign("/auth/logout");
      },
      retrySession: refreshSession,
      accountManagement: async () => {
        window.location.assign(realmUrl("/account"));
      },
      hasRole: (...requiredRoles) => requiredRoles.some((role) => roles.includes(role)),
    }),
    [applySession, authError, authenticated, initialized, refreshSession, roles, subject, username],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
