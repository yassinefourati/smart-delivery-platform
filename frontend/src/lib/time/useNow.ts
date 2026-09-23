import { useSyncExternalStore } from 'react';

interface Clock {
  now: number;
  readonly subscribe: (onChange: () => void) => () => void;
}

const clocks = new Map<number, Clock>();

/**
 * One clock per granularity, with ONE subscribe function for its lifetime. The identity matters:
 * useSyncExternalStore resubscribes whenever `subscribe` changes, and a fresh function per render
 * would tear the interval down and restart it on every render -- which re-reads the time, changes
 * the snapshot and renders again, forever.
 */
function clockFor(granularityMs: number): Clock {
  const existing = clocks.get(granularityMs);
  if (existing) return existing;
  const listeners = new Set<() => void>();
  let timer: ReturnType<typeof setInterval> | undefined;
  const clock: Clock = {
    now: Date.now(),
    subscribe: (onChange) => {
      listeners.add(onChange);
      if (timer === undefined) {
        // Re-read once when the clock wakes from idle; useSyncExternalStore sees the new
        // snapshot after subscribing and renders once more, then the interval takes over.
        clock.now = Date.now();
        timer = setInterval(() => {
          clock.now = Date.now();
          for (const listener of listeners) listener();
        }, granularityMs);
      }
      return () => {
        listeners.delete(onChange);
        if (listeners.size === 0 && timer !== undefined) {
          clearInterval(timer);
          timer = undefined;
        }
      };
    },
  };
  clocks.set(granularityMs, clock);
  return clock;
}

/**
 * The current time, re-rendering at most once per `granularityMs`.
 *
 * An external store rather than `useState(Date.now())`, because reading the clock during render
 * is impure (the react-hooks `purity` rule rejects it, rightly: a render must be repeatable).
 * The snapshot is cached and only changes when the interval ticks, which is what
 * useSyncExternalStore requires of getSnapshot.
 */
export function useNow(granularityMs = 1_000): number {
  const clock = clockFor(granularityMs);
  return useSyncExternalStore(
    clock.subscribe,
    () => clock.now,
    () => clock.now,
  );
}
