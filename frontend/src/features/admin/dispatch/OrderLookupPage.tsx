import { useQuery } from '@tanstack/react-query';
import { type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';

import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import {
  getOrder,
  getPaymentByOrder,
  getShipmentByOrder,
  listUserOrders,
} from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';
import { formatDateTime, formatMoney, formText, shortId } from '../../../lib/format';
import { SagaStepper } from '../../orders/SagaStepper';
import { STATUS_META } from '../../orders/statusMeta';

function PaymentPanel({ orderId }: { orderId: string }) {
  const payment = useQuery({
    queryKey: queryKeys.dispatch.paymentByOrder(orderId),
    queryFn: ({ signal }) => getPaymentByOrder(orderId, { signal }),
  });
  if (payment.isPending) return <Loading lines={1} />;
  if (payment.isError) {
    return 'status' in payment.error && payment.error.status === 404 ? (
      <p className={ui.muted}>No payment recorded for this order.</p>
    ) : (
      <ProblemView error={payment.error} />
    );
  }
  return (
    <p>
      Payment <span className={ui.mono}>{shortId(payment.data.id)}</span>:{' '}
      <strong>{payment.data.status}</strong>, {formatMoney(payment.data.amount)}{' '}
      {payment.data.currency}, updated {formatDateTime(payment.data.updatedAt)}
    </p>
  );
}

function ShipmentPanel({ orderId }: { orderId: string }) {
  const shipment = useQuery({
    queryKey: queryKeys.dispatch.shipmentByOrder(orderId),
    queryFn: ({ signal }) => getShipmentByOrder(orderId, { signal }),
  });
  if (shipment.isPending) return <Loading lines={1} />;
  if (shipment.isError) {
    return 'status' in shipment.error && shipment.error.status === 404 ? (
      <p className={ui.muted}>No shipment yet.</p>
    ) : (
      <ProblemView error={shipment.error} />
    );
  }
  return (
    <p>
      Shipment <span className={ui.mono}>{shortId(shipment.data.id)}</span>:{' '}
      <strong>{shipment.data.status}</strong>
    </p>
  );
}

function OrderResult({ orderId }: { orderId: string }) {
  const order = useQuery({
    queryKey: queryKeys.orders.detail(orderId),
    queryFn: ({ signal }) => getOrder(orderId, { signal }),
  });
  if (order.isPending) return <Loading lines={4} />;
  if (order.isError)
    return <ProblemView error={order.error} onRetry={() => void order.refetch()} />;
  const o = order.data;
  return (
    <div className={`${ui.card} ${ui.stack}`}>
      <h2>Order {shortId(o.id)}</h2>
      <p>
        Status <strong>{o.status}</strong> - customer <span className={ui.mono}>{o.userId}</span> -
        placed {formatDateTime(o.createdAt)} - total {formatMoney(o.totalAmount)}
      </p>
      {STATUS_META[o.status].active >= 0 ? <SagaStepper status={o.status} live={false} /> : null}
      {/* The one place in the app that can show payment state; the customer's FAILED screen deliberately cannot. */}
      <PaymentPanel orderId={o.id} />
      <ShipmentPanel orderId={o.id} />
    </div>
  );
}

function UserOrders({ userId }: { userId: string }) {
  const query = { page: 0, size: 20, sort: 'createdAt,desc' };
  const orders = useQuery({
    queryKey: queryKeys.orders.byUserPage(userId, query),
    queryFn: ({ signal }) => listUserOrders(userId, query, { signal }),
  });
  if (orders.isPending) return <Loading lines={4} />;
  if (orders.isError) return <ProblemView error={orders.error} />;
  if (orders.data.content.length === 0) return <p>This user has no orders.</p>;
  return (
    <ul>
      {orders.data.content.map((o) => (
        <li key={o.id}>
          <Link to={`/admin/orders?orderId=${o.id}`}>{shortId(o.id)}</Link> - {o.status} -{' '}
          {formatDateTime(o.createdAt)}
        </li>
      ))}
    </ul>
  );
}

/**
 * A LOOKUP, DELIBERATELY NOT AN ORDER CONSOLE. There is no all-orders endpoint and no user list.
 * The only way to enumerate orders would be through shipments, which by construction excludes
 * every order that never reached SHIPMENT_CREATED -- exactly the failed and stuck ones an admin
 * most needs to see. A half-console built on that would hide the problem it looked like it solved.
 */
export function OrderLookupPage() {
  const [params, setParams] = useSearchParams();
  const orderId = params.get('orderId')?.trim() ?? '';
  const userId = params.get('userId')?.trim() ?? '';
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const next: Record<string, string> = {};
    const o = formText(data, 'orderId');
    const u = formText(data, 'userId');
    if (o) next.orderId = o;
    if (u) next.userId = u;
    setParams(next);
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Order lookup">Order lookup</PageHeading>
      <p className={`${ui.notice} ${ui.info}`}>
        This page looks orders up by id; it cannot list all orders. The API has no endpoint for
        that, and building a list from shipments would silently leave out every failed or stuck
        order. An admin-only <code>GET /api/v1/orders?status=&amp;page=</code> would change this.
      </p>
      <form
        className={ui.grid2}
        onSubmit={submit}
        key={params.toString()}
        aria-label="Look up orders"
      >
        <div className={ui.field}>
          <label htmlFor="lookup-order">Order id</label>
          <input id="lookup-order" name="orderId" className={ui.mono} defaultValue={orderId} />
        </div>
        <div className={ui.field}>
          <label htmlFor="lookup-user">or customer user id</label>
          <input id="lookup-user" name="userId" className={ui.mono} defaultValue={userId} />
        </div>
        <div>
          <button type="submit" className={`${ui.button} ${ui.primary}`}>
            Look up
          </button>
        </div>
      </form>
      {orderId ? <OrderResult orderId={orderId} /> : null}
      {userId ? <UserOrders userId={userId} /> : null}
    </section>
  );
}
