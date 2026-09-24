import { Suspense, type FormEvent } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';

import { useSession } from '../lib/auth/AuthProvider';
import { useCart } from '../lib/cart/CartProvider';
import { formText } from '../lib/format';
import styles from './AppShell.module.css';
import { Loading } from './Loading';
import { SessionExpiryBanner } from './SessionExpiryBanner';
import ui from './ui.module.css';

/**
 * The storefront's search box, in the header on every page. It navigates to the catalog with
 * `?search=`, so a search is a URL like any other filter. On the catalog it shows the current
 * search; `key` resets it when the URL changes underneath it (Back).
 */
function HeaderSearch() {
  const location = useLocation();
  const navigate = useNavigate();
  const current =
    location.pathname === '/' ? (new URLSearchParams(location.search).get('search') ?? '') : '';
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const value = formText(new FormData(e.currentTarget), 'search');
    void navigate(value ? `/?${new URLSearchParams({ search: value }).toString()}` : '/');
  };
  return (
    <form
      role="search"
      aria-label="Site search"
      className={styles.search}
      key={location.pathname + location.search}
      onSubmit={submit}
    >
      <label htmlFor="site-search" className={ui.visuallyHidden}>
        Search
      </label>
      <input
        id="site-search"
        name="search"
        type="search"
        placeholder="Search products"
        defaultValue={current}
        className={styles.searchInput}
      />
      <button type="submit" className={styles.searchButton} aria-label="Submit search">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2.2" />
          <path d="m16.5 16.5 4 4" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
        </svg>
      </button>
    </form>
  );
}

function CartIcon() {
  return (
    <svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true">
      <path
        d="M3 4h2.2l2.1 10.2a1.5 1.5 0 0 0 1.5 1.2h8.6a1.5 1.5 0 0 0 1.4-1.1L20.5 8H6.3"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="9.5" cy="19.5" r="1.4" fill="currentColor" />
      <circle cx="17" cy="19.5" r="1.4" fill="currentColor" />
    </svg>
  );
}

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
        <div className={styles.top}>
          <div className={styles.topInner}>
            <Link to="/" className={styles.brand}>
              <span className={styles.logo} aria-hidden="true">
                <svg viewBox="0 0 24 24" width="22" height="22">
                  <path
                    d="M3 7.5 12 3l9 4.5v9L12 21l-9-4.5z M3 7.5l9 4.5 9-4.5 M12 12v9"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinejoin="round"
                  />
                </svg>
              </span>
              Smart Delivery
            </Link>
            <HeaderSearch />
            <div className={styles.account}>
              {isAuthenticated ? (
                <>
                  <NavLink to="/account" className={`${styles.topLink}`}>
                    <span className={styles.topSmall} aria-hidden="true">
                      Hello
                    </span>
                    Account
                  </NavLink>
                  <NavLink to="/orders" className={`${styles.topLink}`}>
                    <span className={styles.topSmall} aria-hidden="true">
                      Track your
                    </span>
                    Orders
                  </NavLink>
                  <button type="button" className={styles.topButton} onClick={signOut}>
                    Sign out
                  </button>
                </>
              ) : (
                <>
                  <NavLink to="/login" className={`${styles.topLink}`}>
                    <span className={styles.topSmall} aria-hidden="true">
                      Hello, welcome
                    </span>
                    Sign in
                  </NavLink>
                  <NavLink to="/register" className={`${styles.topLink} ${styles.register}`}>
                    <span className={styles.topSmall} aria-hidden="true">
                      New here?
                    </span>
                    Register
                  </NavLink>
                </>
              )}
              <NavLink to="/cart" className={`${styles.cart}`}>
                <span className={styles.cartIcon}>
                  <CartIcon />
                  <span className={styles.cartCount} aria-hidden="true">
                    {itemCount}
                  </span>
                </span>
                Cart
                <span className={ui.visuallyHidden}>
                  {itemCount > 0 ? ` (${itemCount})` : ''} items
                </span>
              </NavLink>
            </div>
          </div>
        </div>
        <div className={styles.sub}>
          <div className={styles.subInner}>
            <nav aria-label="Main" className={styles.subNav}>
              <NavLink to="/" end>
                Shop
              </NavLink>
              <NavLink to="/support">Help</NavLink>
            </nav>
            {isAdmin || isStock || isAgent ? (
              <nav aria-label="Staff" className={styles.subNav}>
                <span className={styles.staffLabel} aria-hidden="true">
                  Staff
                </span>
                {isAdmin ? <NavLink to="/admin/products">Products</NavLink> : null}
                {isAdmin ? <NavLink to="/admin/categories">Categories</NavLink> : null}
                {isStock ? <NavLink to="/admin/warehouses">Warehouses</NavLink> : null}
                {isStock ? <NavLink to="/admin/stock">Stock</NavLink> : null}
                {isAdmin ? <NavLink to="/admin/shipments">Shipments</NavLink> : null}
                {isAdmin ? <NavLink to="/admin/agents">Agents</NavLink> : null}
                {isAdmin ? <NavLink to="/admin/orders">Order lookup</NavLink> : null}
                {isAdmin ? <NavLink to="/admin/users">Users</NavLink> : null}
                {isAgent ? <NavLink to="/agent/deliveries">My deliveries</NavLink> : null}
              </nav>
            ) : null}
          </div>
        </div>
      </header>
      <main id="main" className={styles.main}>
        <SessionExpiryBanner />
        <Suspense fallback={<Loading label="Loading page" />}>
          <Outlet />
        </Suspense>
      </main>
      <footer className={styles.footer}>
        <div className={styles.footerInner}>
          <span className={styles.footerBrand}>Smart Delivery</span>
          <Link to="/support">Having trouble? Get a support report</Link>
        </div>
      </footer>
    </>
  );
}
