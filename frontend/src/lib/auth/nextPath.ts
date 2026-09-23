/**
 * Validate the `?next=` parameter the login page redirects to afterwards.
 *
 * Without this, `/login?next=https://evil.example` is an open redirect: a phishing link that
 * lands on the real login page and sends the user somewhere else once they have typed their
 * password. So the target must be a path on THIS origin -- starting with a single `/`. A
 * leading `//` or `/\` is a protocol-relative URL to another host, and browsers treat the
 * backslash form as a forward slash.
 */
export function safeNextPath(raw: string | null | undefined, fallback = '/'): string {
  if (!raw) return fallback;
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return fallback;
  // Anything the URL parser would resolve off-origin.
  try {
    const resolved = new URL(raw, 'http://same-origin.invalid');
    if (resolved.origin !== 'http://same-origin.invalid') return fallback;
  } catch {
    return fallback;
  }
  // Sending someone back to the login page after logging in is a loop, not a destination.
  if (raw === '/login' || raw.startsWith('/login?') || raw.startsWith('/login/')) return fallback;
  return raw;
}

/** Build a login URL that returns to `path`, optionally saying why the user is there. */
export function loginUrl(path: string, reason?: 'expired' | 'unauthorized' | 'reload'): string {
  const params = new URLSearchParams({ next: path });
  if (reason) params.set('reason', reason);
  return `/login?${params.toString()}`;
}
