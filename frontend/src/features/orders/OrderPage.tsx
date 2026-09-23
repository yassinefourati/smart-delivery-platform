import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';

import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { getOrder } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import type { OrderResponse, OrderStatusView } from '../../lib/api/schemas/order';
import { formatDateTime, formatMoney, shortId } from '../../lib/format';
import { CancelOrder } from './CancelOrder';
import { SagaStepper } from './SagaStepper';
import { useOrderStatus } from './useOrderStatus';

/**
 * THE FAILED COPY IS EXACT, AND WHAT IT LEAVES OUT IS THE POINT. A customer token cannot read
 * payments (payment-service is ADMIN-or-SERVICE) and OrderResponse has no failure reason, so
 * this screen cannot know whether a charge happened. It must not say "you have not been
 * charged" or "your payment was refunded"; it gives a reference and a promise that a person
 * can keep. The admin order lookup is where payment state is visible.
 */
function TerminalPanel({ status, order }: { status: OrderStatusView; order: OrderResponse }) {
  if (status === 'FAILED') {
    return (
      <div role="alert" className={`${ui.notice} ${ui.error} ${ui.stack}`}>
        <p>
          We couldn&apos;t complete this order and it has been cancelled. Any stock held for it has
          been released. We can&apos;t show payment details on this screen. If you were charged,
          quote this reference and we&apos;ll sort it out.
        </p>
        <span>
          Order reference: <code className={ui.mono}>{order.id}</code>
        </span>
      </div>
    );
  }
  if (status === 'CANCELLED') {
    return (
      <div className={`${ui.notice} ${ui.info}`}>
        This order was cancelled. Reserved items are released, and any payment taken is refunded.
      </div>
    );
  }
  return (
    <div className={`${ui.notice} ${ui.warn}`}>
      This order is in a state this page does not recognise. Reload the page to get the latest
      version.
    </div>
  );
}

function OrderView({ order, justPlaced }: { order: OrderResponse; justPlaced: boolean }) {
  // The polling clock starts at the order's creation; "Check again" restarts it.
  const [restartedAt, setRestartedAt] = useState<number | null>(null);
  const since = restartedAt ?? Date.parse(order.createdAt);
  const statusQuery = useOrderStatus(order.id, since);
  const status = statusQuery.data?.status ?? order.status;
  const terminal = status === 'CANCELLED' || status === 'FAILED' || status === 'UNKNOWN';

  return (
    <section className={ui.page}>
      <p>
        <Link to="/orders">All orders</Link>
      </p>
      <PageHeading title="Order">Order {shortId(order.id)}</PageHeading>
      <p className={ui.muted}>Placed {formatDateTime(order.createdAt)}</p>

      {justPlaced && !terminal ? (
        <p className={`${ui.notice} ${ui.success}`}>
          Thanks, your order is in. Stock, payment and shipping are arranged in the next few
          seconds, and this page follows along.
        </p>
      ) : null}

      <div className={ui.card}>
        {terminal ? (
          <TerminalPanel status={status} order={order} />
        ) : (
          <SagaStepper status={status} />
        )}
      </div>

      {statusQuery.gaveUp ? (
        <div className={`${ui.notice} ${ui.warn} ${ui.stack}`}>
          <p>
            This is taking longer than usual. It hasn&apos;t failed: our recovery process has it and
            will either complete it or release everything. Reference{' '}
            <code className={ui.mono}>{order.id}</code>.
          </p>
          <div>
            <button type="button" className={ui.button} onClick={() => setRestartedAt(Date.now())}>
              Check again
            </button>
          </div>
        </div>
      ) : null}
      {status === 'SHIPMENT_CREATED' ? (
        <p className={ui.muted}>
          We&apos;ll update this page when a courier picks it up. Check back later.
        </p>
      ) : null}
      {statusQuery.isError ? (
        <ProblemView error={statusQuery.error} onRetry={() => void statusQuery.refetch()} />
      ) : null}

      <CancelOrder orderId={order.id} status={status} />

      <table className={ui.table}>
        <caption>Items</caption>
        <thead>
          <tr>
            <th scope="col">Item</th>
            <th scope="col" className={ui.num}>
              Unit price
            </th>
            <th scope="col" className={ui.num}>
              Quantity
            </th>
            <th scope="col" className={ui.num}>
              Total
            </th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.productId}>
              <td>{item.productName ?? item.productId}</td>
              <td className={ui.num}>{formatMoney(item.unitPrice)}</td>
              <td className={ui.num}>{item.quantity}</td>
              <td className={ui.num}>{formatMoney(item.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={3}>
              Order total
            </th>
            <td className={ui.num}>
              <strong>{formatMoney(order.totalAmount)}</strong>
            </td>
          </tr>
        </tfoot>
      </table>
    </section>
  );
}

export function OrderPage() {
  const { orderId = '' } = useParams();
  const location = useLocation();
  const justPlaced = (location.state as { justPlaced?: boolean } | null)?.justPlaced === true;
  const order = useQuery({
    queryKey: queryKeys.orders.detail(orderId),
    queryFn: ({ signal }) => getOrder(orderId, { signal }),
    staleTime: STALE_TIME.ORDER_DETAIL,
  });
  if (order.isPending) return <Loading label="Loading order" lines={6} />;
  if (order.isError) {
    return (
      <section className={ui.page}>
        <PageHeading title="Order">Order</PageHeading>
        <ProblemView error={order.error} onRetry={() => void order.refetch()} />
      </section>
    );
  }
  return <OrderView order={order.data} justPlaced={justPlaced} />;
}
