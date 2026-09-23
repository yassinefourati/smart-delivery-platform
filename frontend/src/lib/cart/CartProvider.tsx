import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  type ReactNode,
} from 'react';

import { mintKey } from '../../domain/idempotency';
import { useSession } from '../auth/AuthProvider';
import {
  type CartLine,
  type CartState,
  cartReducer,
  estimatedTotal,
  itemCount,
  loadCart,
  saveCart,
} from './cart';

/**
 * One of the app's two contexts (the other is the session). The reducer is pure and lives in
 * cart.ts where it is tested without React; this file only wires it to storage and to the
 * session.
 */
interface CartContextValue {
  readonly cart: CartState;
  readonly itemCount: number;
  readonly estimatedTotal: number;
  readonly add: (line: Omit<CartLine, 'quantity'>, quantity: number) => void;
  readonly setQuantity: (productId: string, quantity: number) => void;
  readonly remove: (productId: string) => void;
  readonly setAddress: (addressId: string | null) => void;
  readonly acceptPrices: (prices: Readonly<Record<string, number>>) => void;
  readonly enterCheckout: () => void;
  readonly startOver: () => void;
  readonly orderCreated: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

function browserStorage(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [cart, dispatch] = useReducer(cartReducer, undefined, () => loadCart(browserStorage()));
  const { userId, logoutGeneration } = useSession();

  // Persist every change. The reducer is the only writer, so this is the only place the
  // stored copy can come from.
  useEffect(() => {
    saveCart(browserStorage(), cart);
  }, [cart]);

  // Bind the cart to whoever signs in: adopt an anonymous cart, drop one left behind by a
  // different user on a shared machine.
  useEffect(() => {
    if (userId !== null) dispatch({ type: 'session', userId });
  }, [userId]);

  // Clear on an EXPLICIT logout, and only then. Not when userId becomes null: a reload leaves
  // the user anonymous (the token is memory-only) and the cart must survive that, and a
  // mid-checkout token expiry must keep both the cart and its idempotency key so the re-login
  // can retry safely. A counter rather than a flag, so every logout is its own event.
  useEffect(() => {
    if (logoutGeneration > 0) dispatch({ type: 'clear' });
  }, [logoutGeneration]);

  const add = useCallback(
    (line: Omit<CartLine, 'quantity'>, quantity: number) =>
      dispatch({ type: 'add', line, quantity, mint: mintKey }),
    [],
  );
  const setQuantity = useCallback(
    (productId: string, quantity: number) =>
      dispatch({ type: 'setQuantity', productId, quantity, mint: mintKey }),
    [],
  );
  const remove = useCallback(
    (productId: string) => dispatch({ type: 'remove', productId, mint: mintKey }),
    [],
  );
  const setAddress = useCallback(
    (addressId: string | null) => dispatch({ type: 'setAddress', addressId, mint: mintKey }),
    [],
  );
  const acceptPrices = useCallback(
    (prices: Readonly<Record<string, number>>) =>
      dispatch({ type: 'acceptPrices', prices, mint: mintKey }),
    [],
  );
  const enterCheckout = useCallback(() => dispatch({ type: 'enterCheckout', mint: mintKey }), []);
  const startOver = useCallback(() => dispatch({ type: 'startOver', mint: mintKey }), []);
  const orderCreated = useCallback(() => dispatch({ type: 'orderCreated' }), []);

  const value = useMemo<CartContextValue>(
    () => ({
      cart,
      itemCount: itemCount(cart.lines),
      estimatedTotal: estimatedTotal(cart.lines),
      add,
      setQuantity,
      remove,
      setAddress,
      acceptPrices,
      enterCheckout,
      startOver,
      orderCreated,
    }),
    [
      cart,
      add,
      setQuantity,
      remove,
      setAddress,
      acceptPrices,
      enterCheckout,
      startOver,
      orderCreated,
    ],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used inside <CartProvider>');
  return ctx;
}
