import { Link, useNavigate } from 'react-router';

import { PageHeading } from '../../components/PageHeading';
import ui from '../../components/ui.module.css';
import { ProductArt } from '../catalog/ProductArt';
import styles from './cart.module.css';
import { MAX_QUANTITY, MIN_QUANTITY } from '../../lib/cart/cart';
import { useCart } from '../../lib/cart/CartProvider';
import { formatMoney } from '../../lib/format';
import { checkoutBlockers, useCartPricing, type PricedLine } from './useCartPricing';

function LineStatus({ p }: { p: PricedLine }) {
  if (p.unavailable) return <span className={ui.fieldError}>No longer sold</span>;
  if (p.insufficientStock)
    return (
      <span className={ui.fieldError}>
        {p.available === 0 ? 'Out of stock' : `Only ${p.available} left`}
      </span>
    );
  if (p.priceChanged && p.currentPrice !== undefined)
    return (
      <span className={ui.fieldError}>
        Price changed: was {formatMoney(p.line.priceAtAdd)}, now {formatMoney(p.currentPrice)}
      </span>
    );
  if (p.lookupFailed) return <span className={ui.muted}>Could not check price or stock</span>;
  return null;
}

export function CartPage() {
  const { cart, setQuantity, remove, acceptPrices } = useCart();
  const pricing = useCartPricing(cart.lines);
  const navigate = useNavigate();
  const blockers = checkoutBlockers(pricing);

  if (cart.lines.length === 0) {
    return (
      <section className={ui.page}>
        <div className={`${ui.card} ${styles.empty}`}>
          <PageHeading title="Cart">Your cart</PageHeading>
          <p>Your cart is empty.</p>
          <Link to="/" className={`${ui.button} ${ui.primary}`}>
            Browse the shop
          </Link>
        </div>
      </section>
    );
  }

  return (
    <section className={styles.layout}>
      <div className={`${ui.card} ${styles.items}`}>
        <PageHeading title="Cart">Your cart</PageHeading>
        <table className={`${ui.table} ${styles.table}`}>
          <caption className={ui.visuallyHidden}>Items in your cart</caption>
          <thead>
            <tr>
              <th scope="col">Item</th>
              <th scope="col" className={ui.num}>
                Price
              </th>
              <th scope="col">Quantity</th>
              <th scope="col" className={ui.num}>
                Subtotal
              </th>
              <th scope="col">
                <span className={ui.visuallyHidden}>Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {pricing.lines.map((p) => {
              const price = p.currentPrice ?? p.line.priceAtAdd;
              return (
                <tr key={p.line.productId}>
                  <td>
                    <div className={styles.item}>
                      <div className={styles.thumb}>
                        <ProductArt sku={p.line.sku} name={p.line.nameAtAdd} />
                      </div>
                      <div className={ui.stack}>
                        <Link to={`/products/${p.line.productId}`}>{p.line.nameAtAdd}</Link>
                        <LineStatus p={p} />
                      </div>
                    </div>
                  </td>
                  <td className={ui.num}>{formatMoney(price)}</td>
                  <td>
                    <input
                      type="number"
                      aria-label={`Quantity of ${p.line.nameAtAdd}`}
                      min={MIN_QUANTITY}
                      max={MAX_QUANTITY}
                      value={p.line.quantity}
                      onChange={(e) => {
                        const q = Number(e.target.value);
                        if (Number.isFinite(q) && q >= MIN_QUANTITY)
                          setQuantity(p.line.productId, q);
                      }}
                      style={{ width: '5rem' }}
                    />
                  </td>
                  <td className={ui.num}>{formatMoney(price * p.line.quantity)}</td>
                  <td>
                    <button
                      type="button"
                      className={ui.linkButton}
                      onClick={() => remove(p.line.productId)}
                    >
                      Remove <span className={ui.visuallyHidden}>{p.line.nameAtAdd}</span>
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <aside className={`${ui.card} ${styles.summary}`} aria-label="Order summary">
        <div>
          <strong className={styles.total}>Estimated total: {formatMoney(pricing.estimate)}</strong>
          <div className={`${ui.small} ${ui.muted}`}>
            The final price is fixed when your order is placed.
          </div>
        </div>
        <div className={ui.stack}>
          {pricing.anyPriceChanged ? (
            <button
              type="button"
              className={ui.button}
              onClick={() => acceptPrices(pricing.changedPrices)}
            >
              Accept new prices
            </button>
          ) : null}
          <button
            type="button"
            className={`${ui.button} ${ui.primary} ${ui.block}`}
            disabled={blockers.length > 0}
            aria-describedby={blockers.length > 0 ? 'cart-blockers' : undefined}
            onClick={() => void navigate('/checkout')}
          >
            Go to checkout
          </button>
        </div>
        {blockers.length > 0 ? (
          <ul
            id="cart-blockers"
            className={`${ui.notice} ${ui.warn} ${styles.blockers}`}
            aria-live="polite"
          >
            {blockers.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        ) : null}
      </aside>
    </section>
  );
}
