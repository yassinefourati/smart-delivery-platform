import { isRouteErrorResponse, Link, useRouteError } from 'react-router';

import { isApiProblem } from '../lib/api/problem';
import { PageHeading } from './PageHeading';
import { ProblemView } from './ProblemView';
import ui from './ui.module.css';

/**
 * Rendered by <RequireRole> and by any screen whose request came back FORBIDDEN.
 *
 * It never signs anyone out and never links to /login: the token is fine, this one thing is not
 * allowed. A 403-to-login redirect is a loop for a signed-in user.
 */
export function ForbiddenPage() {
  return (
    <section className={ui.page}>
      <PageHeading title="Not allowed">Not allowed</PageHeading>
      <p>Your account does not have access to this page.</p>
      <p>
        <Link to="/">Back to the shop</Link>
      </p>
    </section>
  );
}

export function NotFoundPage() {
  return (
    <section className={ui.page}>
      <PageHeading title="Page not found">Page not found</PageHeading>
      <p>There is nothing at this address. The link may be old, or mistyped.</p>
      <p>
        <Link to="/">Back to the shop</Link>
      </p>
    </section>
  );
}

/**
 * A lazy chunk that fails to load is almost always a deploy that happened while this tab was
 * open: the old index.html names chunk hashes the server no longer has. Reloading fetches the
 * new index.html and fixes it, so that is what the message says -- rather than "something went
 * wrong", which invites a retry that fails identically.
 */
export function isChunkLoadError(error: unknown): boolean {
  return (
    error instanceof Error &&
    /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
      error.message,
    )
  );
}

/** The `errorElement` of every route group: one broken screen leaves the shell and nav intact. */
export function RouteErrorPage() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />;
  if (isApiProblem(error) && error.code === 'FORBIDDEN') return <ForbiddenPage />;
  return (
    <section className={ui.page}>
      <PageHeading title="Something went wrong">Something went wrong</PageHeading>
      {isChunkLoadError(error) ? (
        <div role="alert" className={`${ui.notice} ${ui.warn}`}>
          A new version of this site was released while this page was open.{' '}
          <button type="button" className={ui.linkButton} onClick={() => window.location.reload()}>
            Reload to get the latest version
          </button>
        </div>
      ) : (
        <ProblemView error={error} />
      )}
      <p>
        <Link to="/">Back to the shop</Link>
      </p>
    </section>
  );
}
