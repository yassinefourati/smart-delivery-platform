import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';

import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { listShipments } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';
import type { ShipmentResponse, ShipmentStatus } from '../../../lib/api/schemas/delivery';
import { formatDateTime, shortId } from '../../../lib/format';
import { AssignAgentDialog } from './AssignAgentDialog';

/** Unassigned first: an unassigned shipment is a stalled order. */
const GROUPS: { status: ShipmentStatus; title: string }[] = [
  { status: 'CREATED', title: 'Waiting for an agent' },
  { status: 'ASSIGNED', title: 'Out for delivery' },
  { status: 'DELIVERED', title: 'Delivered' },
];

export function ShipmentsPage() {
  const [assigning, setAssigning] = useState<ShipmentResponse | null>(null);
  const shipments = useQuery({
    queryKey: queryKeys.dispatch.shipments(),
    queryFn: ({ signal }) => listShipments({ signal }),
    refetchInterval: 30_000,
  });
  return (
    <section className={ui.page}>
      <PageHeading title="Shipments">Shipments</PageHeading>
      {shipments.isPending ? (
        <Loading lines={6} />
      ) : shipments.isError ? (
        <ProblemView error={shipments.error} onRetry={() => void shipments.refetch()} />
      ) : (
        GROUPS.map((g) => {
          const rows = shipments.data.filter((s) => s.status === g.status);
          return (
            <div key={g.status} className={ui.stack}>
              <h2>
                {g.title} ({rows.length})
              </h2>
              {rows.length === 0 ? (
                <p className={ui.muted}>None.</p>
              ) : (
                <table className={ui.table}>
                  <thead>
                    <tr>
                      <th scope="col">Shipment</th>
                      <th scope="col">Order</th>
                      <th scope="col">Created</th>
                      <th scope="col">Updated</th>
                      {g.status === 'CREATED' ? (
                        <th scope="col">
                          <span className={ui.visuallyHidden}>Actions</span>
                        </th>
                      ) : null}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((s) => (
                      <tr key={s.id}>
                        <td className={ui.mono}>{shortId(s.id)}</td>
                        <td>
                          <Link to={`/admin/orders?orderId=${s.orderId}`}>
                            {shortId(s.orderId)}
                          </Link>
                        </td>
                        <td>{formatDateTime(s.createdAt)}</td>
                        <td>{formatDateTime(s.updatedAt)}</td>
                        {g.status === 'CREATED' ? (
                          <td>
                            <button
                              type="button"
                              className={ui.button}
                              onClick={() => setAssigning(s)}
                            >
                              Assign agent
                            </button>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          );
        })
      )}
      {assigning ? (
        <AssignAgentDialog shipment={assigning} onClose={() => setAssigning(null)} />
      ) : null}
    </section>
  );
}
