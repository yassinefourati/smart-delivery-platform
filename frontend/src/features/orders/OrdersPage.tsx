import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';

import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { Pagination } from '../../components/Pagination';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { isSagaInFlight } from '../../domain/orderStatus';
import { listUserOrders } from '../../lib/api/endpoints';
import { queryKeys } from '../../lib/api/queryKeys';
import type { OrderResponse } from '../../lib/api/schemas/order';
import { useSession } from '../../lib/auth/AuthProvider';
import { formatDateTime, formatMoney, shortId } from '../../lib/format';
import { STATUS_META } from './statusMeta';
import { useOrderStatus } from './useOrderStatus';

const PAGE_SIZE = 10;
/** At most this many rows poll individually; beyond it the whole list refreshes every 30s. */
const MAX_ROW_POLLERS = 5;

function LiveStatus({ order }: { order: OrderResponse }) {
  const { data } = useOrderStatus(order.id, Date.parse(order.createdAt));
  return <>{STATUS_META[data?.status ?? order.status].label}</>;
}

export function OrdersPage() {
  const { userId } = useSession();
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const query = { page, size: PAGE_SIZE, sort: 'createdAt,desc' };

  const orders = useQuery({
    queryKey: queryKeys.orders.byUserPage(userId ?? '', query),
    queryFn: ({ signal }) => listUserOrders(userId ?? '', query, { signal }),
    enabled: userId !== null,
    placeholderData: keepPreviousData,
    refetchInterval: (q) => {
      const inFlight = q.state.data?.content.filter((o) => isSagaInFlight(o.status)).length ?? 0;
      return inFlight > MAX_ROW_POLLERS ? 30_000 : false;
    },
  });

  // The first MAX_ROW_POLLERS in-flight rows poll individually; computed up front, not during the map.
  const polled = new Set(
    (orders.data?.content ?? [])
      .filter((o) => isSagaInFlight(o.status))
      .slice(0, MAX_ROW_POLLERS)
      .map((o) => o.id),
  );
  return (
    <section className={ui.page}>
      <PageHeading title="Your orders">Your orders</PageHeading>
      {orders.isPending ? (
        <Loading label="Loading orders" lines={4} />
      ) : orders.isError ? (
        <ProblemView error={orders.error} onRetry={() => void orders.refetch()} />
      ) : orders.data.content.length === 0 ? (
        <p>
          You have no orders yet. <Link to="/">Start shopping</Link>
        </p>
      ) : (
        <>
          <table className={ui.table}>
            <caption className={ui.visuallyHidden}>Your orders, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Order</th>
                <th scope="col">Placed</th>
                <th scope="col">Status</th>
                <th scope="col" className={ui.num}>
                  Total
                </th>
              </tr>
            </thead>
            <tbody>
              {orders.data.content.map((o) => {
                const poll = polled.has(o.id);
                return (
                  <tr key={o.id}>
                    <td>
                      <Link to={`/orders/${o.id}`}>Order {shortId(o.id)}</Link>
                    </td>
                    <td>{formatDateTime(o.createdAt)}</td>
                    <td>{poll ? <LiveStatus order={o} /> : STATUS_META[o.status].label}</td>
                    <td className={ui.num}>{formatMoney(o.totalAmount)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <Pagination
            page={orders.data.page}
            totalPages={orders.data.totalPages}
            onPage={(p) => setParams(p === 0 ? {} : { page: String(p) })}
          />
        </>
      )}
    </section>
  );
}
