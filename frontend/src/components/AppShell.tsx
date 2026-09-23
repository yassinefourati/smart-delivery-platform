import { Suspense } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';

import { useSession } from '../lib/auth/AuthProvider';
import { useCart } from '../lib/cart/CartProvider';
import styles from './AppShell.module.css';
import { Loading } from './Loading';
import { SessionExpiryBanner } from './SessionExpiryBanner';
import ui from './ui.module.css';

/**
 * Header, navigation and the content outlet. One app for four personas: an ADMIN is also a
 * customer, so staff links are ADDED to the storefront rather than replacing it. Which links
 * appear is convenience only -- the server enforces every rule (see lib/auth/guards.tsx).
 */
export function AppShell() {
  const { isAuthenticated, hasAnyRole, logout } = useSession();
  const { itemCount } = useCart();
  const navigate = useNavigate();

  const signOut = () => {
    logout();
    void navigate('/');
  };

  const isAdmin = hasAnyRole('ADMIN');
  const isStock = hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER');
  const isAgent = hasAnyRole('DELIVERY_AGENT', 'ADMIN');

  return (
    <>
      <a href="#main" className="sdp-skip-link">
        Skip to content
      </a>
      <header className={styles.header}>
        <div className={styles.bar}>
          <Link to="/" className={styles.brand}>
            Smart Delivery
          </Link>
          <nav aria-label="Main" className={styles.nav}>
            <NavLink to="/" end>
              Shop
            </NavLink>
            <NavLink to="/cart">
              Cart{itemCount > 0 ? ` (${itemCount})` : ''}
              <span className={ui.visuallyHidden}> items</span>
            </NavLink>
            {isAuthenticated ? <NavLink to="/orders">Orders</NavLink> : null}
          </nav>
          <div className={styles.account}>
            {isAuthenticated ? (
              <>
                <NavLink to="/account">Account</NavLink>
                <button type="button" className={ui.linkButton} onClick={signOut}>
                  Sign out
                </button>
              </>
            ) : (
              <>
                <NavLink to="/login">Sign in</NavLink>
                <NavLink to="/register">Register</NavLink>
              </>
            )}
          </div>
        </div>
        {isAdmin || isStock || isAgent ? (
          <div className={styles.bar}>
            <nav aria-label="Staff" className={styles.staff}>
              {isAdmin ? <NavLink to="/admin/products">Products</NavLink> : null}
              {isAdmin ? <NavLink to="/admin/categories">Categories</NavLink> : null}
              {isStock ? <NavLink to="/admin/warehouses">Warehouses</NavLink> : null}
              {isStock ? <NavLink to="/admin/stock">Stock</NavLink> : null}
              {isAdmin ? <NavLink to="/admin/shipments">Shipments</NavLink> : null}
              {isAdmin ? <NavLink to="/admin/agents">Agents</NavLink> : null}
              {isAdmin ? <NavLink to="/admin/orders">Order lookup</NavLink> : null}
              {isAgent ? <NavLink to="/agent/deliveries">My deliveries</NavLink> : null}
            </nav>
          </div>
        ) : null}
      </header>
      <main id="main" className={styles.main}>
        <SessionExpiryBanner />
        <Suspense fallback={<Loading label="Loading page" />}>
          <Outlet />
        </Suspense>
      </main>
      <footer className={styles.footer}>
        <Link to="/support">Having trouble? Get a support report</Link>
      </footer>
    </>
  );
}
