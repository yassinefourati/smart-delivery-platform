import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useId } from 'react';
import { Link, useSearchParams } from 'react-router';

import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { Pagination } from '../../components/Pagination';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { listCategories, listProducts } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import type { ProductListQuery } from '../../lib/api/schemas/product';
import { formatMoney, formText } from '../../lib/format';
import { AddToCart } from '../cart/AddToCart';
import { ProductArt } from './ProductArt';
import styles from './catalog.module.css';

const PAGE_SIZE = 12;
const SORTS = [
  { value: '', label: 'Newest' },
  { value: 'price,asc', label: 'Price: low to high' },
  { value: 'price,desc', label: 'Price: high to low' },
  { value: 'name,asc', label: 'Name' },
] as const;

function numberParam(raw: string | null): number | undefined {
  if (raw === null || raw.trim() === '') return undefined;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : undefined;
}

/** Reads the filters from the URL: the URL IS the state, so a filtered list is linkable and Back works. */
export function queryFromParams(params: URLSearchParams): ProductListQuery {
  return {
    search: params.get('search')?.trim() || undefined,
    categoryId: params.get('categoryId') || undefined,
    minPrice: numberParam(params.get('minPrice')),
    maxPrice: numberParam(params.get('maxPrice')),
    page: numberParam(params.get('page')) ?? 0,
    size: PAGE_SIZE,
    sort: params.get('sort') || undefined,
  };
}

/**
 * The current filters with one key set (or removed), back on page one: keeping page 4 of a
 * different result set is a blank page.
 */
function withParam(params: URLSearchParams, key: string, value: string | null): URLSearchParams {
  const next = new URLSearchParams(params);
  if (value) next.set(key, value);
  else next.delete(key);
  next.delete('page');
  return next;
}

function hrefFor(params: URLSearchParams): string {
  const qs = params.toString();
  return qs ? `/?${qs}` : '/';
}

export function CatalogPage() {
  const [params, setParams] = useSearchParams();
  const query = queryFromParams(params);
  const formId = useId();

  const products = useQuery({
    queryKey: queryKeys.catalog.productList(query),
    queryFn: ({ signal }) => listProducts(query, { signal }),
    staleTime: STALE_TIME.CATALOG,
    // A filter change keeps the previous grid on screen instead of flashing a skeleton.
    placeholderData: keepPreviousData,
  });
  const categories = useQuery({
    queryKey: queryKeys.catalog.categories(),
    queryFn: ({ signal }) => listCategories({ signal }),
    staleTime: STALE_TIME.CATEGORIES,
  });

  /** The price form keeps every other filter (search, department, sort). */
  const applyPrice = (form: HTMLFormElement) => {
    const data = new FormData(form);
    let next = params;
    for (const key of ['minPrice', 'maxPrice']) next = withParam(next, key, formText(data, key));
    setParams(next);
  };

  const goToPage = (page: number) => {
    const next = new URLSearchParams(params);
    if (page === 0) next.delete('page');
    else next.set('page', String(page));
    setParams(next);
  };

  const categoryName = categories.data?.find((c) => c.id === query.categoryId)?.name;
  const heading = query.search
    ? `Results for "${query.search}"`
    : (categoryName ?? (query.categoryId ? 'Products' : 'All products'));
  const filtered = params.toString() !== '';

  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar} aria-label="Filters">
        <h2 className={styles.sideHeading}>Department</h2>
        <ul className={styles.deptList}>
          <li>
            <Link
              to={hrefFor(withParam(params, 'categoryId', null))}
              aria-current={query.categoryId ? undefined : 'true'}
            >
              All products
            </Link>
          </li>
          {categories.data?.map((c) => (
            <li key={c.id}>
              <Link
                to={hrefFor(withParam(params, 'categoryId', c.id))}
                aria-current={query.categoryId === c.id ? 'true' : undefined}
              >
                {c.name}
              </Link>
            </li>
          ))}
        </ul>

        <h2 className={styles.sideHeading}>Price</h2>
        <form
          aria-label="Filter by price"
          className={styles.priceForm}
          // `key` resets the uncontrolled inputs when the URL changes underneath them (Back).
          key={params.toString()}
          onSubmit={(e) => {
            e.preventDefault();
            applyPrice(e.currentTarget);
          }}
        >
          <div className={styles.priceInputs}>
            <div className={ui.field}>
              <label htmlFor={`${formId}-min`}>Min price</label>
              <input
                id={`${formId}-min`}
                name="minPrice"
                type="number"
                min="0"
                step="0.01"
                defaultValue={query.minPrice ?? ''}
              />
            </div>
            <div className={ui.field}>
              <label htmlFor={`${formId}-max`}>Max price</label>
              <input
                id={`${formId}-max`}
                name="maxPrice"
                type="number"
                min="0"
                step="0.01"
                defaultValue={query.maxPrice ?? ''}
              />
            </div>
          </div>
          <button type="submit" className={ui.button}>
            Apply
          </button>
        </form>
        {filtered ? (
          <button
            type="button"
            className={ui.linkButton}
            onClick={() => setParams(new URLSearchParams())}
          >
            Clear all filters
          </button>
        ) : null}
      </aside>

      <section className={styles.results}>
        {filtered ? null : (
          <div className={styles.hero}>
            <p className={styles.heroEyebrow}>Smart Delivery</p>
            <p className={styles.heroTitle}>Everyday essentials, delivered from our warehouses</p>
            <p className={styles.heroText}>
              Live stock on every product, and every order tracked from payment to your door.
            </p>
          </div>
        )}
        <div className={styles.resultsBar}>
          <div>
            <PageHeading title="Shop">{heading}</PageHeading>
            {products.data ? (
              <p className={`${ui.muted} ${ui.small}`} aria-live="polite">
                {products.data.totalElements} product
                {products.data.totalElements === 1 ? '' : 's'}
                {products.isPlaceholderData ? ' (updating...)' : ''}
              </p>
            ) : null}
          </div>
          <div className={styles.sort}>
            <label htmlFor={`${formId}-sort`}>Sort by</label>
            <select
              id={`${formId}-sort`}
              value={query.sort ?? ''}
              onChange={(e) => setParams(withParam(params, 'sort', e.target.value))}
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {products.isPending ? (
          <Loading label="Loading products" lines={4} />
        ) : products.isError ? (
          <ProblemView error={products.error} onRetry={() => void products.refetch()} />
        ) : products.data.content.length === 0 ? (
          <div className={styles.empty}>
            <p>No products match these filters.</p>
            <Link to="/">See all products</Link>
          </div>
        ) : (
          <>
            <ul className={styles.grid} aria-busy={products.isPlaceholderData}>
              {products.data.content.map((p) => (
                <li key={p.id} className={styles.tile}>
                  <ProductArt sku={p.sku} name={p.name} />
                  <div className={styles.tileBody}>
                    {/* Stretched over the whole card by .tileLink::after, so the card is the target. */}
                    <Link to={`/products/${p.id}`} className={styles.tileLink}>
                      {p.name}
                    </Link>
                    <span className={`${ui.muted} ${ui.small}`}>{p.categoryName ?? ''}</span>
                    <span className={styles.price}>{formatMoney(p.price)}</span>
                  </div>
                  <div className={styles.tileAction}>
                    {p.active ? (
                      <AddToCart product={p} compact />
                    ) : (
                      <span className={`${ui.muted} ${ui.small}`}>Not currently sold</span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <Pagination
              page={products.data.page}
              totalPages={products.data.totalPages}
              onPage={goToPage}
            />
          </>
        )}
      </section>
    </div>
  );
}
