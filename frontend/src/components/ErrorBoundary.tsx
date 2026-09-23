import { Component, type ErrorInfo, type ReactNode } from 'react';

import { isChunkLoadError } from './StatusPages';

/**
 * The root boundary, ABOVE the router, so a crash in routing itself still renders something a
 * user can act on. Route groups have their own `errorElement`s below it; this one catches what
 * those cannot. A class because React still has no hook for componentDidCatch.
 *
 * Deliberately plain markup and no router links: if we are here, the router may be the thing
 * that broke.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: unknown }> {
  override state: { error: unknown } = { error: null };

  static getDerivedStateFromError(error: unknown) {
    return { error };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo) {
    // The message and component stack only: never props or state, which can hold form input.
    console.error(
      'Unhandled render error',
      error instanceof Error ? error.message : error,
      info.componentStack,
    );
  }

  override render() {
    if (this.state.error === null) return this.props.children;
    const chunk = isChunkLoadError(this.state.error);
    return (
      <main style={{ maxWidth: '40rem', margin: '0 auto', padding: 'var(--sdp-space-6)' }}>
        <h1>{chunk ? 'This page is out of date' : 'Something went wrong'}</h1>
        <p>
          {chunk
            ? 'A new version of this site was released while this page was open.'
            : 'The page hit an unexpected error. This was not your fault.'}
        </p>
        <button type="button" onClick={() => window.location.reload()}>
          {chunk ? 'Reload to get the latest version' : 'Reload the page'}
        </button>
      </main>
    );
  }
}
