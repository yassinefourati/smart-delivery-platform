import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';

import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { getProduct } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import { formatMoney } from '../../lib/format';
import { AddToCart } from '../cart/AddToCart';
import { ProductArt } from './ProductArt';
import { StockLine } from './StockLine';
import styles from './catalog.module.css';

export function ProductPage() {
  const { productId = '' } = useParams();
  const product = useQuery({
    queryKey: queryKeys.catalog.product(productId),
    queryFn: ({ signal }) => getProduct(productId, { signal }),
    staleTime: STALE_TIME.CATALOG,
  });

  if (product.isPending) return <Loading label="Loading product" lines={5} />;
  if (product.isError)
    return <ProblemView error={product.error} onRetry={() => void product.refetch()} />;
  const p = product.data;
  return (
    <section className={ui.page}>
      <nav aria-label="Breadcrumb" className={styles.crumbs}>
        <Link to="/">Back to the shop</Link>
        {p.categoryId && p.categoryName ? (
          <>
            <span aria-hidden="true">›</span>
            <Link to={`/?categoryId=${p.categoryId}`}>{p.categoryName}</Link>
          </>
        ) : null}
      </nav>
      <div className={styles.detail}>
        <div className={styles.detailArt}>
          <ProductArt sku={p.sku} name={p.name} />
        </div>
        <div className={styles.detailInfo}>
          <PageHeading title={p.name}>{p.name}</PageHeading>
          <span className={`${ui.muted} ${ui.small}`}>
            {p.categoryName ?? 'Uncategorised'} - SKU <span className={ui.mono}>{p.sku}</span>
          </span>
          <hr />
          {p.description ? (
            <>
              <h2 className={styles.aboutHeading}>About this item</h2>
              <p>{p.description}</p>
            </>
          ) : (
            <p className={ui.muted}>No description yet.</p>
          )}
        </div>
        <aside className={styles.buyBox} aria-label="Buy this product">
          <span className={styles.buyPrice}>{formatMoney(p.price)}</span>
          {p.active ? (
            <>
              <StockLine productId={p.id} />
              <AddToCart product={p} />
              <dl className={styles.buyMeta}>
                <dt>Ships from</dt>
                <dd>Smart Delivery warehouses</dd>
                <dt>Tracking</dt>
                <dd>Every step, from payment to delivery</dd>
              </dl>
            </>
          ) : (
            <p className={`${ui.notice} ${ui.info}`}>This product is not currently sold.</p>
          )}
        </aside>
      </div>
    </section>
  );
}
