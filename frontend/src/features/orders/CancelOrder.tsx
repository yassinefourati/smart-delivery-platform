import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { Dialog } from '../../components/Dialog';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { canCancel, cancelInvolvesRefund } from '../../domain/orderStatus';
import { cancelOrder, getOrderStatus } from '../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../lib/api/problem';
import { mutationKeys, queryKeys } from '../../lib/api/queryKeys';
import type { OrderStatusView } from '../../lib/api/schemas/order';

/** A 409 on cancel is a lost race, not an error: say what actually happened, from the FRESH status. */
export function raceMessage(fresh: OrderStatusView): string {
  switch (fresh) {
    case 'SHIPMENT_CREATED':
    case 'OUT_FOR_DELIVERY':
    case 'DELIVERED':
      return "This order shipped just before your cancellation went through, so it wasn't cancelled.";
    case 'CANCELLED':
      return 'This order has already been cancelled.';
    case 'FAILED':
      return 'This order could not be completed and was already cancelled automatically.';
    default:
      return 'The order changed while you were cancelling. Please check its status and try again.';
  }
}

/**
 * Cancel, with no optimism anywhere.
 *
 * The status on screen can be one poll interval stale, so opening the dialog fetches it FRESH
 * and swaps to an explanation if the order can no longer be cancelled. There is no idempotency
 * key on this endpoint, so it is never retried automatically. Compensation (stock release, any
 * refund) runs asynchronously after the 200 (ADR 008), so the success copy says it is
 * happening, not that it has happened.
 */
export function CancelOrder({ orderId, status }: { orderId: string; status: OrderStatusView }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [fresh, setFresh] = useState<OrderStatusView | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  // Set synchronously: two clicks in one frame both precede the re-render that disables the
  // button, and a second cancel would come back 409 and be misreported as a lost race.
  const inFlight = useRef(false);

  const refreshStatus = async () => {
    const latest = await queryClient.fetchQuery({
      queryKey: queryKeys.orders.status(orderId),
      queryFn: ({ signal }) => getOrderStatus(orderId, { signal }),
      staleTime: 0,
    });
    setFresh(latest.status);
    return latest.status;
  };

  const mutation = useMutation({
    mutationKey: mutationKeys.cancelOrder(orderId),
    mutationFn: () => cancelOrder(orderId),
    retry: false,
    onSettled: () => {
      inFlight.current = false;
    },
    onSuccess: (order) => {
      queryClient.setQueryData(queryKeys.orders.detail(orderId), order);
      queryClient.setQueryData(queryKeys.orders.status(orderId), { orderId, status: order.status });
      setOpen(false);
      setOutcome(
        'Cancelling: releasing your items and issuing any refund. This can take a minute.',
      );
    },
    onError: async (error) => {
      if (isApiProblem(error) && isConflict(error)) {
        setOpen(false);
        const latest = await refreshStatus().catch(() => null);
        await queryClient.invalidateQueries({ queryKey: queryKeys.orders.detail(orderId) });
        setOutcome(latest ? raceMessage(latest) : raceMessage('UNKNOWN'));
      }
    },
  });

  const openDialog = () => {
    setFresh(null);
    mutation.reset();
    setOpen(true);
    void refreshStatus().catch(() => setFresh(status));
  };

  // The dialog's own fresh read updates the shared status cache, which can make `status`
  // non-cancellable WHILE the dialog is open; the dialog must stay to explain why.
  if (!canCancel(status) && !open && outcome === null) return null;

  const shownStatus = fresh ?? status;
  const stillCancellable = fresh === null || canCancel(fresh);
  const refund = cancelInvolvesRefund(shownStatus);

  return (
    <div className={ui.stack}>
      {outcome ? (
        <p role="status" className={`${ui.notice} ${ui.info}`}>
          {outcome}
        </p>
      ) : null}
      {canCancel(status) ? (
        <div>
          <button type="button" className={`${ui.button} ${ui.danger}`} onClick={openDialog}>
            {cancelInvolvesRefund(status) ? 'Cancel and request refund' : 'Cancel order'}
          </button>
        </div>
      ) : null}
      <Dialog open={open} title="Cancel this order?" onClose={() => setOpen(false)}>
        {fresh === null ? (
          <p role="status">Checking the latest status...</p>
        ) : stillCancellable ? (
          <>
            <p>
              {refund
                ? 'Your payment has been taken. Cancelling releases your items and issues a refund.'
                : 'Cancelling releases your items. If a payment was taken in the meantime, it is refunded.'}
            </p>
            {mutation.isError && !(isApiProblem(mutation.error) && isConflict(mutation.error)) ? (
              <ProblemView error={mutation.error} />
            ) : null}
            <div className={ui.row}>
              <button
                type="button"
                className={`${ui.button} ${ui.danger}`}
                disabled={mutation.isPending}
                onClick={() => {
                  if (inFlight.current) return;
                  inFlight.current = true;
                  mutation.mutate();
                }}
              >
                {mutation.isPending
                  ? 'Cancelling...'
                  : refund
                    ? 'Cancel and refund'
                    : 'Yes, cancel'}
              </button>
              <button type="button" className={ui.button} onClick={() => setOpen(false)}>
                Keep order
              </button>
            </div>
          </>
        ) : (
          <>
            <p>{raceMessage(fresh)}</p>
            <button type="button" className={ui.button} onClick={() => setOpen(false)}>
              Close
            </button>
          </>
        )}
      </Dialog>
    </div>
  );
}
