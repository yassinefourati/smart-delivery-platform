import { isApiProblem, type ApiProblem } from '../lib/api/problem';
import { CorrelationRef } from './CorrelationRef';
import ui from './ui.module.css';

/**
 * How any failure is shown to a person. Branches on the stable `code` -- never on `detail`,
 * which docs/security.md says may be reworded at any time.
 *
 * Each code gets a DIFFERENT answer, because collapsing them into one "something went wrong"
 * throws away the work the platform did to distinguish them. Two are load-bearing:
 * SERVICE_UNAVAILABLE reads as transient (amber, "your data is safe") because the difference
 * between a user waiting and a user placing a second order is the wording; and FORBIDDEN never
 * signs anyone out.
 *
 * Screens with a context-specific answer (a 409 on cancel, EMAIL_ALREADY_EXISTS on register)
 * handle those codes themselves and fall back to this for everything else.
 */
export function describeProblem(problem: ApiProblem): {
  tone: 'warn' | 'error';
  title: string;
  body: string;
} {
  switch (problem.code) {
    case 'SERVICE_UNAVAILABLE':
    case 'GATEWAY_ERROR':
      return {
        tone: 'warn',
        title: 'Part of the platform is temporarily unavailable',
        body: 'Your data is safe. Please try again in a moment.',
      };
    case 'NETWORK_ERROR':
      return {
        tone: 'warn',
        title: 'Could not reach the server',
        body: 'Check your connection and try again.',
      };
    case 'UNAUTHORIZED':
      return { tone: 'warn', title: 'Please sign in again', body: 'Your session has ended.' };
    case 'FORBIDDEN':
      return {
        tone: 'error',
        title: 'Not allowed',
        body: 'Your account does not have access to this.',
      };
    case 'NOT_FOUND':
      return {
        tone: 'error',
        title: 'Not found',
        body: 'It may have been removed, or the link is wrong.',
      };
    case 'CONCURRENT_MODIFICATION':
      return {
        tone: 'warn',
        title: 'Someone else changed this',
        body: 'We reloaded the latest version. Please check it and try again.',
      };
    case 'VALIDATION_ERROR':
    case 'MALFORMED_REQUEST':
      return { tone: 'error', title: 'Please check the form', body: problem.detail };
    case 'CONTRACT_VIOLATION':
      return {
        tone: 'error',
        title: 'The server sent something this page does not understand',
        body: 'This is a bug on our side, not something you did.',
      };
    default:
      return {
        tone: 'error',
        title: 'Something went wrong',
        body: 'This was not your fault. If it keeps happening, quote the support code below.',
      };
  }
}

export function ProblemView({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: (() => void) | undefined;
}) {
  if (!isApiProblem(error)) {
    return (
      <div role="alert" className={`${ui.notice} ${ui.error}`}>
        <strong>Something went wrong.</strong> <span>Please reload the page.</span>
      </div>
    );
  }
  const { tone, title, body } = describeProblem(error);
  // METHOD_NOT_ALLOWED and CONTRACT_VIOLATION are our bugs: retrying will not help.
  const retryable =
    onRetry && error.code !== 'METHOD_NOT_ALLOWED' && error.code !== 'CONTRACT_VIOLATION';
  return (
    <div
      role="alert"
      className={`${ui.notice} ${tone === 'warn' ? ui.warn : ui.error} ${ui.stack}`}
    >
      <div>
        <strong>{title}.</strong> <span>{body}</span>
      </div>
      <div className={ui.spread}>
        <CorrelationRef
          correlationId={error.correlationId}
          status={error.status}
          code={error.code}
          pathTemplate={error.pathTemplate}
        />
        {retryable ? (
          <button type="button" className={ui.button} onClick={onRetry}>
            Try again
          </button>
        ) : null}
      </div>
    </div>
  );
}
