import { setAuthorizationAttacher } from '../api/http';

/**
 * The access token, and the ONLY place it lives. Not localStorage, not sessionStorage, not a
 * cookie, not React state -- a module-scoped variable with no getter. See ADR 012.
 *
 * Why this matters on this platform specifically: docs/security.md records that there is NO
 * token revocation. A token lifted from web storage is a valid credential for up to an hour,
 * replayable from any machine, and nothing anywhere can kill it -- logout is a client-side
 * fiction. Memory-only does not stop XSS (an injected script can wrap fetch and read the
 * header), but it removes exfiltration that OUTLIVES THE TAB, which is the only impact
 * reduction a client has when revocation does not exist.
 *
 * "No getter" is an architectural control rather than a convention: nothing in this app can
 * read the token back, so nothing can log it, put it in a URL, or copy it into state. The one
 * consumer is src/lib/api/http.ts, which receives an attacher rather than the value.
 *
 * The cost is stated rather than hidden: a reload or a new tab means signing in again. The
 * catalog, stock levels and the cart all work anonymously, so after a reload the user is
 * anonymous, not broken. The reversal -- move this to sessionStorage -- is a one-file change
 * here, which is why it is one file.
 */
let token: string | null = null;
let expiresAt = 0;

setAuthorizationAttacher((headers) => {
  // Checked on every request rather than trusted from a timer, so a token that has expired
  // is never sent even if the expiry timer has not fired yet (a sleeping laptop, a throttled
  // background tab).
  if (token !== null && Date.now() < expiresAt) {
    headers.set('Authorization', `Bearer ${token}`);
  }
});

export function storeToken(value: string, expiresAtMs: number): void {
  token = value;
  expiresAt = expiresAtMs;
}

export function dropToken(): void {
  token = null;
  expiresAt = 0;
}
