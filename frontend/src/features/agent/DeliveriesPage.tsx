import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';

import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { listDeliveriesForAgentUser } from '../../lib/api/endpoints';
import { queryKeys } from '../../lib/api/queryKeys';
import { useSession } from '../../lib/auth/AuthProvider';
import { formatDateTime, shortId } from '../../lib/format';

/**
 * The honest minimum. DeliveryResponse carries no order, no address and no items, and an agent
 * cannot read shipments -- so this screen literally cannot say what to deliver or where. It says
 * THAT instead of inventing a map or a manifest the API cannot fill.
 */
export function DeliveriesPage() {
  const { userId } = useSession();
  const deliveries = useQuery({
    queryKey: queryKeys.deliveries.byAgentUser(userId ?? ''),
    queryFn: ({ signal }) => listDeliveriesForAgentUser(userId ?? '', { signal }),
    enabled: userId !== null,
  });
  return (
    <section className={ui.page}>
      <PageHeading title="My deliveries">My deliveries</PageHeading>
      <p className={`${ui.notice} ${ui.info}`}>
        Addresses and order contents are not available here yet. Ask dispatch for the delivery
        details, quoting the assignment code.
      </p>
      {deliveries.isPending ? (
        <Loading />
      ) : deliveries.isError ? (
        <ProblemView error={deliveries.error} onRetry={() => void deliveries.refetch()} />
      ) : (
        <>
          <h2>To deliver</h2>
          <ul className={ui.stack} style={{ listStyle: 'none', padding: 0 }}>
            {deliveries.data.filter((d) => d.status === 'ASSIGNED').length === 0 ? (
              <li>Nothing assigned right now.</li>
            ) : null}
            {deliveries.data
              .filter((d) => d.status === 'ASSIGNED')
              .map((d) => (
                <li key={d.id} className={ui.card}>
                  <Link to={`/agent/deliveries/${d.id}`} className={`${ui.button} ${ui.primary}`}>
                    Assignment {shortId(d.id)}
                  </Link>{' '}
                  <span className={ui.muted}>assigned {formatDateTime(d.assignedAt)}</span>
                </li>
              ))}
          </ul>
          <details>
            <summary>
              Completed ({deliveries.data.filter((d) => d.status === 'COMPLETED').length})
            </summary>
            <ul>
              {deliveries.data
                .filter((d) => d.status === 'COMPLETED')
                .map((d) => (
                  <li key={d.id}>
                    {shortId(d.id)} - delivered {formatDateTime(d.deliveredAt)}
                  </li>
                ))}
            </ul>
          </details>
        </>
      )}
    </section>
  );
}
