import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';

import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { Pagination } from '../../../components/Pagination';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { listProducts } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';
import { formatMoney } from '../../../lib/format';

export function ProductsAdminPage() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const query = { page, size: 20, sort: 'name,asc' };
  const products = useQuery({
    queryKey: queryKeys.catalog.productList(query),
    queryFn: ({ signal }) => listProducts(query, { signal }),
    placeholderData: keepPreviousData,
  });
  return (
    <section className={ui.page}>
      <div className={ui.spread}>
        <PageHeading title="Products">Products</PageHeading>
        <Link to="/admin/products/new" className={`${ui.button} ${ui.primary}`}>
          New product
        </Link>
      </div>
      {products.isPending ? (
        <Loading lines={6} />
      ) : products.isError ? (
        <ProblemView error={products.error} onRetry={() => void products.refetch()} />
      ) : (
        <>
          <table className={ui.table}>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">SKU</th>
                <th scope="col">Category</th>
                <th scope="col" className={ui.num}>
                  Price
                </th>
                <th scope="col">Sold</th>
              </tr>
            </thead>
            <tbody>
              {products.data.content.map((p) => (
                <tr key={p.id}>
                  <td>
                    <Link to={`/admin/products/${p.id}`}>{p.name}</Link>
                  </td>
                  <td className={ui.mono}>{p.sku}</td>
                  <td>{p.categoryName ?? '--'}</td>
                  <td className={ui.num}>{formatMoney(p.price)}</td>
                  <td>{p.active ? 'Yes' : 'No (inactive)'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination
            page={products.data.page}
            totalPages={products.data.totalPages}
            onPage={(p) => setParams(p === 0 ? {} : { page: String(p) })}
          />
        </>
      )}
    </section>
  );
}
