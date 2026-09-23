import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router';

import { Dialog } from '../../components/Dialog';
import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { completeDelivery, getDelivery } from '../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../lib/api/problem';
import { queryKeys } from '../../lib/api/queryKeys';
import type { DeliveryResponse } from '../../lib/api/schemas/delivery';
import { useSession } from '../../lib/auth/AuthProvider';
import { formatDateTime, shortId } from '../../lib/format';

/**
 * Completing emits DeliveryCompleted, which marks the customer's order DELIVERED. Irreversible,
 * so it is confirmed; not idempotent, so it is never retried automatically -- after a network
 * failure the delivery is refetched and the agent decides. No optimistic update.
 */
export function CompleteButton({ delivery }: { delivery: DeliveryResponse }) {
  const queryClient = useQueryClient();
  const { userId } = useSession();
  const [open, setOpen] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  // Synchronous guard: the endpoint has no idempotency key, and a same-frame double tap would
  // otherwise send two completions and report the second one's 409.
  const inFlight = useRef(false);
  const complete = useMutation({
    mutationFn: () => completeDelivery(delivery.id),
    retry: false,
    onSettled: async () => {
      inFlight.current = false;
      await queryClient.invalidateQueries({ queryKey: queryKeys.deliveries.detail(delivery.id) });
      await queryClient.invalidateQueries({
        queryKey: queryKeys.deliveries.byAgentUser(userId ?? ''),
      });
    },
    onSuccess: () => {
      setOpen(false);
      setAnnouncement('Delivery marked complete. The customer can now see it as delivered.');
    },
  });
  const already = isApiProblem(complete.error) && isConflict(complete.error);
  return (
    <div className={ui.stack}>
      <p role="status" aria-live="polite">
        {announcement}
      </p>
      {already ? (
        <p role="alert" className={`${ui.notice} ${ui.info}`}>
          This delivery was already marked complete.
        </p>
      ) : complete.isError ? (
        <ProblemView error={complete.error} />
      ) : null}
      {delivery.status === 'ASSIGNED' ? (
        <button
          type="button"
          className={`${ui.button} ${ui.primary}`}
          style={{ minHeight: '3.5rem', width: '100%' }}
          onClick={() => setOpen(true)}
        >
          Mark as delivered
        </button>
      ) : null}
      <Dialog open={open} title="Mark as delivered?" onClose={() => setOpen(false)}>
        <p>This tells the customer their order has arrived. It cannot be undone.</p>
        <div className={ui.row}>
          <button
            type="button"
            className={`${ui.button} ${ui.primary}`}
            style={{ minHeight: '3rem' }}
            disabled={complete.isPending}
            onClick={() => {
              if (inFlight.current) return;
              inFlight.current = true;
              complete.mutate();
            }}
          >
            {complete.isPending ? 'Saving...' : 'Yes, delivered'}
          </button>
          <button
            type="button"
            className={ui.button}
            style={{ minHeight: '3rem' }}
            onClick={() => setOpen(false)}
          >
            Not yet
          </button>
        </div>
      </Dialog>
    </div>
  );
}

export function DeliveryPage() {
  const { deliveryId = '' } = useParams();
  const delivery = useQuery({
    queryKey: queryKeys.deliveries.detail(deliveryId),
    queryFn: ({ signal }) => getDelivery(deliveryId, { signal }),
  });
  return (
    <section className={ui.page}>
      <p>
        <Link to="/agent/deliveries">All my deliveries</Link>
      </p>
      <PageHeading title="Delivery">Assignment {shortId(deliveryId)}</PageHeading>
      {delivery.isPending ? (
        <Loading />
      ) : delivery.isError ? (
        <ProblemView error={delivery.error} onRetry={() => void delivery.refetch()} />
      ) : (
        <>
          {/* The action first, so it is reachable one-handed without scrolling. */}
          <CompleteButton delivery={delivery.data} />
          <dl className={ui.grid2}>
            <div>
              <dt className={ui.muted}>Status</dt>
              <dd>{delivery.data.status === 'ASSIGNED' ? 'To deliver' : 'Delivered'}</dd>
            </div>
            <div>
              <dt className={ui.muted}>Assigned</dt>
              <dd>{formatDateTime(delivery.data.assignedAt)}</dd>
            </div>
            <div>
              <dt className={ui.muted}>Delivered</dt>
              <dd>{formatDateTime(delivery.data.deliveredAt)}</dd>
            </div>
            <div>
              <dt className={ui.muted}>Assignment code</dt>
              <dd className={ui.mono}>{delivery.data.id}</dd>
            </div>
          </dl>
        </>
      )}
    </section>
  );
}
