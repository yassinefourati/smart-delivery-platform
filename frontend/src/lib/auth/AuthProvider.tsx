import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import { login as loginRequest } from '../api/endpoints';
import { registerRequestObserver } from '../api/http';
import { isHumanRole, type HumanRole, type LoginResponse } from '../api/schemas/user';
import { clearHint, readHint, writeHint, type SessionHint } from './sessionHint';
import { dropToken, storeToken } from './tokenStore';

/**
 * The session: who is signed in, with which roles, until when. Never the token itself -- see
 * tokenStore.ts, which deliberately has no getter.
 *
 * THERE ARE NO REFRESH TOKENS on this platform (docs/security.md). So:
 *
 *   - The token is dropped PROACTIVELY 30 seconds before it expires. That is the platform's own
 *     skew, borrowed from order-service's ServiceTokenProvider rather than invented. It matters
 *     most mid-checkout: a 401 arriving after a POST was sent is ambiguous about whether the
 *     order exists, whereas a token dropped before the click never sends one.
 *   - Any 401 ends the session. Single-flight -- ten concurrent queries failing produce one
 *     sign-out -- and never retried, because there is nothing to refresh with and a retry
 *     queue would be a loop.
 *   - A 403 NEVER ends the session. FORBIDDEN means the token is fine and this request was not
 *     allowed -- with ownership checks on orders, addresses, profiles and deliveries, a 403 is a
 *     routine consequence of a stale link. Signing someone out over it is infuriating, and a
 *     403-to-login redirect is a loop.
 *
 * An EXPLICIT logout is the only thing that clears the cart (via `logoutGeneration`). An expiry
 * or a 401 deliberately does not: the cart and its idempotency key must survive a re-login so
 * the retry is safe.
 */
export const EXPIRY_SKEW_MS = 30_000;

export type SignedOutReason = 'expired' | 'unauthorized';

interface Session {
  readonly userId: string;
  readonly roles: readonly string[];
  readonly expiresAt: number;
}

export interface SessionContextValue {
  readonly userId: string | null;
  readonly roles: readonly string[];
  readonly expiresAt: number | null;
  readonly isAuthenticated: boolean;
  /** Why the last session ended on its own, so the login page can say so. null after a logout. */
  readonly signedOutReason: SignedOutReason | null;
  /** From sessionStorage: who was signed in before a reload. Non-secret; used for copy only. */
  readonly hint: SessionHint | null;
  /** Increments on every explicit logout; the cart clears itself when it changes. */
  readonly logoutGeneration: number;
  readonly hasAnyRole: (...roles: HumanRole[]) => boolean;
  readonly login: (email: string, password: string) => Promise<LoginResponse>;
  readonly logout: () => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/** The login call itself answers bad credentials with a 401; that is not a session ending. */
const LOGIN_PATH = '/api/v1/auth/login';

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [signedOutReason, setSignedOutReason] = useState<SignedOutReason | null>(null);
  const [logoutGeneration, setLogoutGeneration] = useState(0);
  const [hint] = useState<SessionHint | null>(readHint);

  const endSession = useCallback(
    (reason: SignedOutReason) => {
      dropToken();
      // Stop every poller and in-flight read now, rather than letting each one discover the
      // 401 for itself.
      void queryClient.cancelQueries();
      setSession((current) => {
        if (current !== null) setSignedOutReason(reason);
        return null;
      });
    },
    [queryClient],
  );

  // Any 401 outside the login call ends the session. The observer sees every request the app
  // makes, which is exactly the "single-flight" property: however many requests fail, the
  // state transition to signed-out happens once and the redirect is rendered once.
  useEffect(
    () =>
      registerRequestObserver((record) => {
        if (record.status === 401 && record.pathTemplate !== LOGIN_PATH) endSession('unauthorized');
      }),
    [endSession],
  );

  // Drop the token before it expires, not after the first request fails with it.
  useEffect(() => {
    if (session === null) return;
    const remaining = session.expiresAt - Date.now();
    const timer = setTimeout(() => endSession('expired'), Math.max(0, remaining));
    return () => clearTimeout(timer);
  }, [session, endSession]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await loginRequest({ email, password });
    const expiresAt = Date.now() + response.expiresInSeconds * 1_000 - EXPIRY_SKEW_MS;
    storeToken(response.accessToken, expiresAt);
    writeHint({ userId: response.userId, roles: response.roles, expiresAt });
    setSignedOutReason(null);
    setSession({ userId: response.userId, roles: response.roles, expiresAt });
    return response;
  }, []);

  const logout = useCallback(() => {
    dropToken();
    clearHint();
    // Another user may sign in next on this machine; nothing of this one's may be served to them.
    queryClient.clear();
    setSignedOutReason(null);
    setSession(null);
    setLogoutGeneration((n) => n + 1);
  }, [queryClient]);

  const value = useMemo<SessionContextValue>(() => {
    const roles = session?.roles ?? [];
    return {
      userId: session?.userId ?? null,
      roles,
      expiresAt: session?.expiresAt ?? null,
      isAuthenticated: session !== null,
      signedOutReason,
      hint,
      logoutGeneration,
      hasAnyRole: (...wanted: HumanRole[]) =>
        roles.some((r) => isHumanRole(r) && wanted.includes(r)),
      login,
      logout,
    };
  }, [session, signedOutReason, hint, logoutGeneration, login, logout]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside <AuthProvider>');
  return ctx;
}
