import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';

import { ForbiddenPage } from '../../components/StatusPages';
import type { HumanRole } from '../api/schemas/user';
import { useSession } from './AuthProvider';
import { loginUrl } from './nextPath';

/**
 * THESE GUARDS ARE USER EXPERIENCE. THEY PREVENT NOTHING.
 *
 * Every rule they express is enforced by the server -- `@PreAuthorize` and each service's
 * SecurityConfig, including ownership checks of the form
 * `#userId.toString() == authentication.name or hasRole('ADMIN')`. A user who edits this code in
 * their browser gets past these guards and then gets a 403 from every request, which is the
 * system working as designed.
 *
 * The corollary is a design rule rather than a platitude: EVERY authorised screen must still
 * handle a 403 gracefully, because a token can carry a role these guards trusted while a
 * server-side ownership check still says no.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, signedOutReason, hint } = useSession();
  const location = useLocation();
  if (!isAuthenticated) {
    const reason = signedOutReason ?? (hint !== null ? 'reload' : undefined);
    return <Navigate to={loginUrl(location.pathname + location.search, reason)} replace />;
  }
  return <>{children}</>;
}

/**
 * Role sets come from the server's real rules, not from the persona list: warehouses and stock
 * accept ADMIN **or** WAREHOUSE_MANAGER (inventory-service's MANAGED_ROLES), so gating them on
 * ADMIN alone would lock a warehouse manager out of screens the server would let them use.
 */
export function RequireRole({ roles, children }: { roles: HumanRole[]; children: ReactNode }) {
  const { hasAnyRole } = useSession();
  return <RequireAuth>{hasAnyRole(...roles) ? children : <ForbiddenPage />}</RequireAuth>;
}
