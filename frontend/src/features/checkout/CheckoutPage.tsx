import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { Link, useNavigate } from 'react-router';

import { CorrelationRef } from '../../components/CorrelationRef';
import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { listAddresses } from '../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../lib/api/problem';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import { useSession } from '../../lib/auth/AuthProvider';
import { useCart } from '../../lib/cart/CartProvider';
import { formatMoney } from '../../lib/format';
import { checkoutBlockers, useCartPricing } from '../cart/useCartPricing';
import { isAmbiguousFailure, usePlaceOrder } from './usePlaceOrder';

/**
 * THE KEY IS OLDER THAN THE CLICK. It is minted when this page mounts with a non-empty cart
 * (enterCheckout is a no-op when a key already exists), persisted with the cart, and Place
 * Order is disabled until it exists. A double-click, an automatic retry, a manual retry, a
 * page refresh and the re-login forced by a mid-checkout expiry all reuse it. Only a 201, an
 * edit to the lines or the address, or an explicit Start over rotates it (domain/idempotency.ts).
 */
export function CheckoutPage() {
  const { userId } = useSession();
  const { cart, enterCheckout, setAddress, orderCreated, startOver, acceptPrices } = useCart();
  const navigate = useNavigate();
  const hasLines = cart.lines.length > 0;

  useEffect(() => {
    if (hasLines) enterCheckout();
  }, [hasLines, enterCheckout]);

  const addresses = useQuery({
    queryKey: queryKeys.account.addresses(userId ?? ''),
    queryFn: ({ signal }) => listAddresses(userId ?? '', { signal }),
    enabled: userId !== null,
    staleTime: STALE_TIME.ACCOUNT,
  });
  const pricing = useCartPricing(cart.lines);
  const placeOrder = usePlaceOrder(cart.checkoutKey);
  // `isPending` only disables the button after React re-renders, and two clicks in the same
  // frame both arrive before that. This flag is set synchronously in the handler, so the second
  // click sees it. (The server would still answer both with one order -- this keeps it from
  // having to.)
  const submitting = useRef(false);

  if (!hasLines) {
    return (
      <section className={ui.page}>
        <PageHeading title="Checkout">Checkout</PageHeading>
        <p>
          Your cart is empty. <Link to="/">Browse the shop</Link>
        </p>
      </section>
    );
  }

  const defaultAddress = addresses.data?.find((a) => a.isDefault) ?? addresses.data?.[0];
  const selectedAddressId = cart.shippingAddressId ?? defaultAddress?.id ?? null;
  const blockers = checkoutBlockers(pricing);
  if (selectedAddressId === null && addresses.isSuccess)
    blockers.push('Add a delivery address to continue.');

  const error = placeOrder.error;
  const conflict = isApiProblem(error) && isConflict(error);
  const canPlace =
    cart.checkoutKey !== null &&
    selectedAddressId !== null &&
    blockers.length === 0 &&
    !placeOrder.isPending;

  const submit = () => {
    if (!canPlace || selectedAddressId === null || submitting.current) return;
    submitting.current = true;
    placeOrder.mutate(
      {
        shippingAddressId: selectedAddressId,
        items: cart.lines.map((l) => ({ productId: l.productId, quantity: l.quantity })),
      },
      {
        onSuccess: (order) => {
          orderCreated();
          void navigate(`/orders/${order.id}`, { state: { justPlaced: true } });
        },
        onSettled: () => {
          submitting.current = false;
        },
        onError: (e) => {
          if (isApiProblem(e) && isConflict(e)) {
            // Diagnostics for support: the lifecycle state, never the key's surroundings or a body.
            console.error('Order create conflict', e.code, e.correlationId);
          }
        },
      },
    );
  };

  return (
    <section className={ui.page}>
      <PageHeading title="Checkout">Checkout</PageHeading>

      <div className={ui.card}>
        <h2>Deliver to</h2>
        {addresses.isPending ? (
          <Loading label="Loading addresses" lines={2} />
        ) : addresses.isError ? (
          <ProblemView error={addresses.error} onRetry={() => void addresses.refetch()} />
        ) : addresses.data.length === 0 ? (
          <p>
            You have no saved addresses. <Link to="/account/addresses">Add a delivery address</Link>
            , then come back. Your cart is kept.
          </p>
        ) : (
          <fieldset className={ui.stack} style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className={ui.visuallyHidden}>Delivery address</legend>
            {addresses.data.map((a) => (
              <label key={a.id} className={ui.row}>
                <input
                  type="radio"
                  name="address"
                  value={a.id}
                  checked={selectedAddressId === a.id}
                  onChange={() => setAddress(a.id)}
                  disabled={placeOrder.isPending}
                />
                <span>
                  <strong>{a.label}</strong>: {a.street}, {a.city} {a.postalCode}, {a.country}
                </span>
              </label>
            ))}
          </fieldset>
        )}
      </div>

      <div className={ui.card}>
        <h2>Items</h2>
        <ul className={ui.stack}>
          {pricing.lines.map((p) => (
            <li key={p.line.productId} className={ui.spread}>
              <span>
                {p.line.quantity} x {p.line.nameAtAdd}
              </span>
              <span>{formatMoney((p.currentPrice ?? p.line.priceAtAdd) * p.line.quantity)}</span>
            </li>
          ))}
        </ul>
        <p>
          <strong>Estimated total: {formatMoney(pricing.estimate)}</strong>
          <br />
          <span className={`${ui.small} ${ui.muted}`}>
            The price is fixed when the order is created; your order page shows the final total.
          </span>
        </p>
        <Link to="/cart">Edit cart</Link>
      </div>

      {blockers.length > 0 ? (
        <div className={`${ui.notice} ${ui.warn} ${ui.stack}`} aria-live="polite">
          <ul>
            {blockers.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
          {pricing.anyPriceChanged ? (
            <div>
              <button
                type="button"
                className={ui.button}
                onClick={() => acceptPrices(pricing.changedPrices)}
              >
                Accept new prices
              </button>
            </div>
          ) : null}
        </div>
      ) : null}

      {conflict && isApiProblem(error) ? (
        <div role="alert" className={`${ui.notice} ${ui.error} ${ui.stack}`}>
          <p>
            We could not place this order as it stands. Nothing was ordered twice. One of the items
            may no longer be sold, or the order changed since you first tried. Please review your
            cart.
          </p>
          <CorrelationRef
            correlationId={error.correlationId}
            status={error.status}
            code={error.code}
            pathTemplate={error.pathTemplate}
          />
          <div className={ui.row}>
            <Link to="/cart" className={ui.button}>
              Review cart
            </Link>
            <button
              type="button"
              className={ui.button}
              onClick={() => {
                startOver();
                placeOrder.reset();
              }}
            >
              Start over with this cart
            </button>
          </div>
        </div>
      ) : error ? (
        <div className={ui.stack}>
          <ProblemView error={error} />
          {isAmbiguousFailure(error) ? (
            <p className={ui.small}>
              Your order may or may not have gone through. Pressing Place order again is safe: it
              can only ever create one order.
            </p>
          ) : null}
        </div>
      ) : null}

      <div>
        <button
          type="button"
          className={`${ui.button} ${ui.primary}`}
          disabled={!canPlace}
          aria-disabled={!canPlace}
          onClick={submit}
        >
          {placeOrder.isPending ? 'Placing order...' : 'Place order'}
        </button>
      </div>
    </section>
  );
}
