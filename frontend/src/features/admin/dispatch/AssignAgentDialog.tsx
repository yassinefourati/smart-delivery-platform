import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { Dialog } from '../../../components/Dialog';
import { Field } from '../../../components/Field';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { assignDelivery, listAgents } from '../../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../../lib/api/problem';
import { queryKeys } from '../../../lib/api/queryKeys';
import type { ShipmentResponse } from '../../../lib/api/schemas/delivery';

/**
 * Assigning is what emits DeliveryAssigned, which is what moves the order from
 * SHIPMENT_CREATED to OUT_FOR_DELIVERY. The admin IS the mechanism, so the dialog says so.
 */
export function AssignAgentDialog({
  shipment,
  onClose,
}: {
  shipment: ShipmentResponse;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [agentId, setAgentId] = useState('');
  const agents = useQuery({
    queryKey: queryKeys.dispatch.agents(),
    queryFn: ({ signal }) => listAgents({ signal }),
  });
  const assign = useMutation({
    mutationFn: () => assignDelivery(shipment.id, { agentId }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.dispatch.shipments() }),
    onSuccess: onClose,
  });
  const conflict = isApiProblem(assign.error) && isConflict(assign.error);
  return (
    <Dialog open title="Assign a delivery agent" onClose={onClose}>
      <p>
        Assigning sends this order out for delivery: the customer&apos;s order page moves to
        &quot;On its way&quot;.
      </p>
      <Field label="Agent">
        {(p) => (
          <select {...p} value={agentId} onChange={(e) => setAgentId(e.target.value)}>
            <option value="">Choose an agent</option>
            {agents.data?.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
                {a.phone ? ` (${a.phone})` : ''}
              </option>
            ))}
          </select>
        )}
      </Field>
      {agents.data?.length === 0 ? (
        <p className={ui.muted}>No agents yet. Create one on the Agents page.</p>
      ) : null}
      {conflict ? (
        <p role="alert" className={`${ui.notice} ${ui.error}`}>
          This shipment has already been assigned. The list has been refreshed.
        </p>
      ) : assign.isError ? (
        <ProblemView error={assign.error} />
      ) : null}
      <div className={ui.row}>
        <button
          type="button"
          className={`${ui.button} ${ui.primary}`}
          disabled={!agentId || assign.isPending}
          onClick={() => assign.mutate()}
        >
          Assign
        </button>
        <button type="button" className={ui.button} onClick={onClose}>
          Cancel
        </button>
      </div>
    </Dialog>
  );
}
