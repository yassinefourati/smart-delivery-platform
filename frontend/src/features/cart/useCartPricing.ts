import { useQueries } from '@tanstack/react-query';

import { getInventorySummary, getProduct } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import type { CartLine } from '../../lib/cart/cart';

export interface PricedLine {
  line: CartLine;
  currentPrice: number | undefined;
  priceChanged: boolean;
  /** Deactivated, or deleted (404). The server would answer 409 for it. */
  unavailable: boolean;
  available: number | undefined;
  insufficientStock: boolean;
  checking: boolean;
  lookupFailed: boolean;
}

/**
 * Re-prices the cart against the live catalog and stock.
 *
 * The server snapshots each price when the order is CREATED (order-service reads it from
 * product-service), so the cart's `priceAtAdd` is only ever an estimate. A changed price blocks
 * checkout until the person accepts it -- charging a different amount than the one on screen
 * is the thing this exists to prevent. Accepting rewrites the lines, which rotates the
 * idempotency key: the order body really has changed.
 */
export function useCartPricing(lines: readonly CartLine[]) {
  const products = useQueries({
    queries: lines.map((l) => ({
      queryKey: queryKeys.catalog.product(l.productId),
      queryFn: ({ signal }: { signal: AbortSignal }) => getProduct(l.productId, { signal }),
      staleTime: STALE_TIME.CATALOG,
    })),
  });
  const stock = useQueries({
    queries: lines.map((l) => ({
      queryKey: queryKeys.inventory.summary(l.productId),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        getInventorySummary(l.productId, { signal }),
      staleTime: STALE_TIME.INVENTORY,
    })),
  });

  const priced: PricedLine[] = lines.map((line, i) => {
    const product = products[i];
    const summary = stock[i];
    const notFound = product?.error && 'status' in product.error && product.error.status === 404;
    const currentPrice = product?.data?.price;
    const available = summary?.data?.totalAvailable;
    return {
      line,
      currentPrice,
      priceChanged: currentPrice !== undefined && currentPrice !== line.priceAtAdd,
      unavailable: Boolean(notFound) || product?.data?.active === false,
      available,
      insufficientStock: available !== undefined && available < line.quantity,
      checking: Boolean(product?.isPending || summary?.isPending),
      lookupFailed: Boolean((product?.isError && !notFound) || summary?.isError),
    };
  });

  const changedPrices: Record<string, number> = {};
  for (const p of priced) {
    if (p.priceChanged && p.currentPrice !== undefined)
      changedPrices[p.line.productId] = p.currentPrice;
  }

  return {
    lines: priced,
    changedPrices,
    anyPriceChanged: priced.some((p) => p.priceChanged),
    anyUnavailable: priced.some((p) => p.unavailable),
    anyInsufficient: priced.some((p) => p.insufficientStock),
    checking: priced.some((p) => p.checking),
    /** Estimate at current prices where known, else the price at add. */
    estimate: priced.reduce(
      (sum, p) => sum + (p.currentPrice ?? p.line.priceAtAdd) * p.line.quantity,
      0,
    ),
  };
}

export type CartPricing = ReturnType<typeof useCartPricing>;

/** Why checkout cannot proceed, as sentences. Empty means it can. */
export function checkoutBlockers(pricing: CartPricing): string[] {
  const reasons: string[] = [];
  if (pricing.anyPriceChanged)
    reasons.push('Some prices have changed. Review and accept them to continue.');
  if (pricing.anyUnavailable)
    reasons.push('Some items are no longer sold. Remove them to continue.');
  if (pricing.anyInsufficient)
    reasons.push('Some items do not have enough stock. Lower the quantity to continue.');
  if (pricing.checking) reasons.push('Checking current prices and stock...');
  return reasons;
}
