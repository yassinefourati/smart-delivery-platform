import { useId, useState } from 'react';

import ui from '../../components/ui.module.css';
import type { ProductResponse } from '../../lib/api/schemas/product';
import { MAX_QUANTITY, MIN_QUANTITY } from '../../lib/cart/cart';
import { useCart } from '../../lib/cart/CartProvider';

/**
 * Adds to the persisted cart. Works signed out: the catalog is public, and the cart is adopted
 * by whoever signs in next on this device (or dropped if it belonged to someone else).
 */
export function AddToCart({
  product,
  compact = false,
}: {
  product: ProductResponse;
  compact?: boolean;
}) {
  const { add } = useCart();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  const id = useId();
  return (
    <div className={ui.row}>
      {compact ? null : (
        <span className={ui.field}>
          <label htmlFor={id}>Quantity</label>
          <input
            id={id}
            type="number"
            min={MIN_QUANTITY}
            max={MAX_QUANTITY}
            value={quantity}
            onChange={(e) => {
              setAdded(false);
              setQuantity(
                Math.min(
                  MAX_QUANTITY,
                  Math.max(MIN_QUANTITY, Number(e.target.value) || MIN_QUANTITY),
                ),
              );
            }}
            style={{ width: '5rem' }}
          />
        </span>
      )}
      <button
        type="button"
        className={`${ui.button} ${ui.primary}`}
        aria-label={`Add ${product.name} to cart`}
        onClick={() => {
          add(
            {
              productId: product.id,
              sku: product.sku,
              nameAtAdd: product.name,
              priceAtAdd: product.price,
            },
            quantity,
          );
          setAdded(true);
        }}
      >
        Add to cart
      </button>
      <span role="status" className={ui.small}>
        {added ? 'Added to cart' : ''}
      </span>
    </div>
  );
}
