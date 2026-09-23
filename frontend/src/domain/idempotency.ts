/**
 * The lifecycle of the `Idempotency-Key` sent with `POST /api/v1/orders`.
 *
 * THE KEY MUST BE OLDER THAN THE CLICK. That one invariant is what makes the header a
 * guarantee rather than a hope, and it rules out the three ways this usually goes wrong:
 *
 *   - Minting in the click handler IS the duplicate-order bug: two clicks, two keys, two orders.
 *   - Minting when the cart is created means a legitimate second order reuses a spent key.
 *   - Minting in component state means a page refresh -- a user's instinctive response to
 *     "did that work?" -- mints a second key at exactly the moment it must not.
 *
 * So the key is minted when checkout is entered with a non-empty cart, persisted inside the
 * cart blob (which survives a reload), and the Place Order button is disabled while no key
 * exists. That last part turns the invariant into structure: there is no moment at which a
 * click can happen without a key that was already there.
 *
 * The server side of the contract (order-service): the same key with the same body returns
 * 201 with the SAME order id, so a replay cannot be told apart from a create and the client
 * must not try -- it navigates to the returned id, which is right either way. The same key
 * with a DIFFERENT body returns 409 IDEMPOTENCY_KEY_CONFLICT, which is why any edit to what is
 * being ordered has to rotate the key.
 *
 * Pure, with the id generator injected, so the whole matrix is one table test.
 */
export type CheckoutKeyEvent =
  /** Checkout was entered, or the cart page moved to it. Mint only if there is no key. */
  | 'checkoutEntered'
  /** Lines or the shipping address changed. The old key now describes a different body. */
  | 'orderChanged'
  /** 201. The key is spent; the cart is cleared with it. */
  | 'orderCreated'
  /** The user explicitly abandoned this attempt. */
  | 'startOver'
  /**
   * 401, 500, 502, 503, 504, a timeout, or a network failure. The order may or may not exist,
   * and the ONLY safe retry is one carrying the same key -- it is the entire reason the
   * header exists. Never rotate on any of these.
   */
  | 'ambiguousFailure'
  /**
   * 409 on create. Either the body changed under a stored key, or something is wrong with the
   * lifecycle itself. Silently re-minting and re-POSTing is the one path to a genuine duplicate,
   * so the key is KEPT and the user is sent back to the cart; editing it rotates the key.
   */
  | 'conflict';

export function nextCheckoutKey(
  current: string | null,
  event: CheckoutKeyEvent,
  hasLines: boolean,
  mint: () => string,
): string | null {
  switch (event) {
    case 'checkoutEntered':
      return current ?? (hasLines ? mint() : null);
    case 'orderChanged':
      // Only rotate a key that exists: editing the cart before ever reaching checkout has
      // nothing to invalidate, and minting early would be the "minted at cart creation" bug.
      return current === null ? null : hasLines ? mint() : null;
    case 'orderCreated':
      return null;
    case 'startOver':
      return hasLines ? mint() : null;
    case 'ambiguousFailure':
    case 'conflict':
      return current;
  }
}

/** `crypto.randomUUID()` -- available in every browser this app targets and in Node 22. */
export function mintKey(): string {
  return crypto.randomUUID();
}
