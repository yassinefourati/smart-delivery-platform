import { Link, useLocation } from 'react-router';

import { useSession } from '../lib/auth/AuthProvider';
import { loginUrl } from '../lib/auth/nextPath';
import { useNow } from '../lib/time/useNow';
import ui from './ui.module.css';

/** Two minutes: long enough to finish a checkout, short enough not to nag. */
const WARN_BEFORE_MS = 2 * 60_000;

/**
 * There are no refresh tokens, so a session cannot be extended -- only replaced by signing in
 * again. Saying so two minutes ahead turns a surprise sign-out into a choice. Polite, not
 * assertive: it must not interrupt someone typing an address.
 */
export function SessionExpiryBanner() {
  const { expiresAt, isAuthenticated } = useSession();
  const location = useLocation();
  const now = useNow(15_000);
  if (!isAuthenticated || expiresAt === null) return null;
  const remaining = expiresAt - now;
  if (remaining > WARN_BEFORE_MS || remaining <= 0) return null;
  return (
    <div role="status" aria-live="polite" className={`${ui.notice} ${ui.warn}`}>
      Your session ends in about {Math.max(1, Math.round(remaining / 60_000))} minute(s) and cannot
      be extended. Your cart is kept.{' '}
      <Link to={loginUrl(location.pathname + location.search)}>Sign in again now</Link>
    </div>
  );
}
