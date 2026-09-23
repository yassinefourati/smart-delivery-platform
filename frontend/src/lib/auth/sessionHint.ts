import { z } from 'zod';

/**
 * A NON-SECRET hint about who was signed in, kept in sessionStorage so that after a reload the
 * login page can say "refreshing signs you out" rather than presenting a blank form as though
 * nothing had happened.
 *
 * It holds a user id, role names and an expiry -- nothing that authenticates anything. It is
 * what makes the memory-only token affordable: the argument "we must persist the token or we
 * lose the user id after a reload" is answered without putting a credential in storage.
 */
const KEY = 'sdp.session-hint.v1';

const hintSchema = z.object({
  userId: z.string(),
  roles: z.array(z.string()),
  expiresAt: z.number(),
});

export type SessionHint = z.infer<typeof hintSchema>;

function storage(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.sessionStorage;
  } catch {
    return undefined;
  }
}

export function readHint(): SessionHint | null {
  try {
    const raw = storage()?.getItem(KEY);
    if (!raw) return null;
    const parsed = hintSchema.safeParse(JSON.parse(raw));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

export function writeHint(hint: SessionHint): void {
  try {
    storage()?.setItem(KEY, JSON.stringify(hint));
  } catch {
    // Storage unavailable: the hint is a courtesy, not a requirement.
  }
}

export function clearHint(): void {
  try {
    storage()?.removeItem(KEY);
  } catch {
    // As above.
  }
}
