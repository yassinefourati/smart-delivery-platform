import { useSyncExternalStore } from 'react';

import { registerRequestObserver, type RequestRecord } from '../api/http';

/**
 * The last twenty requests this tab made, for the /support page.
 *
 * In memory only, so it is empty after a reload -- correct for a within-session diagnostic.
 * Each record is `{method, pathTemplate, status, correlationId, at}`: the path TEMPLATE, never
 * the populated path, because this is a user-copyable artifact and `/api/v1/orders/:id` tells
 * support what they need without putting order and user ids on somebody's clipboard. No
 * bodies, no headers -- src/lib/api/http.ts attaches Authorization and sends login bodies, so
 * either would be a credential in a support ticket.
 */
const CAPACITY = 20;
let records: readonly RequestRecord[] = [];
const listeners = new Set<() => void>();

registerRequestObserver((record) => {
  records = [record, ...records].slice(0, CAPACITY);
  for (const listener of listeners) listener();
});

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useRecentRequests(): readonly RequestRecord[] {
  return useSyncExternalStore(
    subscribe,
    () => records,
    () => records,
  );
}

/** The exact text the Copy report button puts on the clipboard, and nothing more. */
export function formatReport(entries: readonly RequestRecord[]): string {
  return entries
    .map(
      (r) =>
        `${new Date(r.at).toISOString()}  ${r.method} ${r.pathTemplate}  ${r.status || 'no response'}  ${r.correlationId}`,
    )
    .join('\n');
}
