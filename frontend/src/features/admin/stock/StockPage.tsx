import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';

import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { getInventorySummary, getProduct, listProducts } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';
import { StockRowForm } from './StockRowForm';

function ProductPicker() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [submitted, setSubmitted] = useState('');
  const query = { search: submitted || undefined, size: 20, page: 0 };
  const products = useQuery({
    queryKey: queryKeys.catalog.productList(query),
    queryFn: ({ signal }) => listProducts(query, { signal }),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(search.trim());
  };
  return (
    <div className={ui.stack}>
      <form role="search" className={ui.row} onSubmit={submit}>
        <label htmlFor="stock-search">Find a product by name</label>
        <input
          id="stock-search"
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <button type="submit" className={ui.button}>
          Search
        </button>
      </form>
      {products.isPending ? (
        <Loading />
      ) : products.isError ? (
        <ProblemView error={products.error} />
      ) : (
        <ul>
          {products.data.content.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                className={ui.linkButton}
                onClick={() => void navigate(`/admin/stock/${p.id}`)}
              >
                {p.name}
              </button>{' '}
              <span className={`${ui.mono} ${ui.muted}`}>{p.sku}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ProductStock({ productId }: { productId: string }) {
  const product = useQuery({
    queryKey: queryKeys.catalog.product(productId),
    queryFn: ({ signal }) => getProduct(productId, { signal }),
  });
  const summary = useQuery({
    queryKey: queryKeys.inventory.summary(productId),
    queryFn: ({ signal }) => getInventorySummary(productId, { signal }),
  });
  return (
    <div className={ui.stack}>
      <p>
        <Link to="/admin/stock">Choose another product</Link>
      </p>
      <h2>{product.data?.name ?? 'Product'}</h2>
      {summary.isPending ? (
        <Loading />
      ) : summary.isError ? (
        <ProblemView error={summary.error} onRetry={() => void summary.refetch()} />
      ) : (
        <>
          <p>
            <strong>{summary.data.totalAvailable}</strong> available,{' '}
            <strong>{summary.data.totalReserved}</strong> reserved for orders in progress.
          </p>
          {summary.data.warehouses.length === 0 ? (
            <p>No stock records yet.</p>
          ) : (
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">Warehouse</th>
                  <th scope="col" className={ui.num}>
                    Available
                  </th>
                  <th scope="col" className={ui.num}>
                    Reserved
                  </th>
                </tr>
              </thead>
              <tbody>
                {summary.data.warehouses.map((w) => (
                  <tr key={w.id}>
                    <td>{w.warehouseName ?? w.warehouseId}</td>
                    <td className={ui.num}>{w.availableQuantity}</td>
                    <td className={ui.num}>{w.reservedQuantity}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      <StockRowForm productId={productId} />
    </div>
  );
}

export function StockPage() {
  const { productId } = useParams();
  return (
    <section className={ui.page}>
      <PageHeading title="Stock">Stock</PageHeading>
      {productId ? <ProductStock productId={productId} /> : <ProductPicker />}
    </section>
  );
}
