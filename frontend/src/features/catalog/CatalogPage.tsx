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

  const applyFilters = (form: HTMLFormElement) => {
    const data = new FormData(form);
    const next = new URLSearchParams();
    for (const key of ['search', 'categoryId', 'minPrice', 'maxPrice', 'sort']) {
      const value = formText(data, key);
      if (value) next.set(key, value);
    }
    // A new filter starts at page one; keeping page 4 of a different result set is a blank page.
    setParams(next);
  };

  const goToPage = (page: number) => {
    const next = new URLSearchParams(params);
    if (page === 0) next.delete('page');
    else next.set('page', String(page));
    setParams(next);
  };

  return (
    <section className={ui.page}>
      <PageHeading title="Shop">Shop</PageHeading>
      <form
        id={formId}
        role="search"
        aria-label="Filter products"
        className={styles.filters}
        // `key` resets the uncontrolled inputs when the URL changes underneath them (Back).
        key={params.toString()}
        onSubmit={(e) => {
          e.preventDefault();
          applyFilters(e.currentTarget);
        }}
      >
        <div className={ui.field}>
          <label htmlFor={`${formId}-search`}>Search</label>
          <input
            id={`${formId}-search`}
            name="search"
            type="search"
            defaultValue={query.search ?? ''}
          />
        </div>
        <div className={ui.field}>
          <label htmlFor={`${formId}-category`}>Category</label>
          <select id={`${formId}-category`} name="categoryId" defaultValue={query.categoryId ?? ''}>
            <option value="">All categories</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
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
        <div className={ui.field}>
          <label htmlFor={`${formId}-sort`}>Sort</label>
          <select id={`${formId}-sort`} name="sort" defaultValue={query.sort ?? ''}>
            {SORTS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <div className={ui.row}>
          <button type="submit" className={`${ui.button} ${ui.primary}`}>
            Apply
          </button>
          {params.toString() ? (
            <button
              type="button"
              className={ui.button}
              onClick={() => setParams(new URLSearchParams())}
            >
              Clear
            </button>
          ) : null}
        </div>
      </form>

      {products.isPending ? (
        <Loading label="Loading products" lines={4} />
      ) : products.isError ? (
        <ProblemView error={products.error} onRetry={() => void products.refetch()} />
      ) : products.data.content.length === 0 ? (
        <p>No products match these filters.</p>
      ) : (
        <>
          <p className={`${ui.muted} ${ui.small}`} aria-live="polite">
            {products.data.totalElements} product{products.data.totalElements === 1 ? '' : 's'}
            {products.isPlaceholderData ? ' (updating...)' : ''}
          </p>
          <ul className={styles.grid} aria-busy={products.isPlaceholderData}>
            {products.data.content.map((p) => (
              <li key={p.id} className={styles.tile}>
                <ProductArt sku={p.sku} name={p.name} />
                <Link to={`/products/${p.id}`}>{p.name}</Link>
                <span className={ui.muted}>{p.categoryName ?? ''}</span>
                <span className={styles.price}>{formatMoney(p.price)}</span>
                {p.active ? (
                  <AddToCart product={p} compact />
                ) : (
                  <span className={ui.muted}>Not currently sold</span>
                )}
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
  );
}
