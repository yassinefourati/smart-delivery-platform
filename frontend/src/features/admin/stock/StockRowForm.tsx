import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { Field } from '../../../components/Field';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { createInventory, listWarehouses } from '../../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../../lib/api/problem';
import { queryKeys } from '../../../lib/api/queryKeys';

/**
 * Creates the FIRST stock row for a (product, warehouse) pair.
 *
 * `POST /api/v1/inventory` is create-only -- a second row for the same pair is a 409 -- and the
 * API has no endpoint that adjusts or restocks a quantity. The form says so in words, because a
 * manager who expects to type a new number here next week needs to know now that they cannot.
 * The saga's reserve/release/deduct endpoints are deliberately not exposed: order-service owns
 * reservations, and a hand-released one makes an order's state a lie.
 */
export function StockRowForm({ productId }: { productId: string }) {
  const queryClient = useQueryClient();
  const [warehouseId, setWarehouseId] = useState('');
  const [quantity, setQuantity] = useState('0');
  const warehouses = useQuery({
    queryKey: queryKeys.inventory.warehouses(),
    queryFn: ({ signal }) => listWarehouses({ signal }),
  });
  const create = useMutation({
    mutationFn: () =>
      createInventory({ productId, warehouseId, availableQuantity: Number(quantity) }),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.inventory.summary(productId) });
      await queryClient.invalidateQueries({ queryKey: queryKeys.inventory.warehouses() });
    },
    onSuccess: () => setQuantity('0'),
  });
  const exists = isApiProblem(create.error) && isConflict(create.error);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };
  return (
    <form className={`${ui.form} ${ui.card}`} onSubmit={submit} aria-label="Add stock">
      <h2>Add stock to a warehouse</h2>
      <p className={`${ui.small} ${ui.muted}`}>
        This creates the stock record for this product in one warehouse. Quantities cannot be
        changed afterwards through this API: there is no adjust or restock endpoint yet.
      </p>
      <Field label="Warehouse">
        {(p) => (
          <select
            {...p}
            required
            value={warehouseId}
            onChange={(e) => setWarehouseId(e.target.value)}
          >
            <option value="">Choose a warehouse</option>
            {warehouses.data?.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Available quantity">
        {(p) => (
          <input
            {...p}
            type="number"
            min="0"
            step="1"
            required
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
          />
        )}
      </Field>
      {exists ? (
        <p role="alert" className={`${ui.notice} ${ui.error}`}>
          This product already has a stock row in this warehouse. There is no endpoint to change its
          quantity.
        </p>
      ) : create.isError ? (
        <ProblemView error={create.error} />
      ) : create.isSuccess ? (
        <p role="status" className={`${ui.notice} ${ui.success}`}>
          Stock added.
        </p>
      ) : null}
      <div>
        <button type="submit" className={`${ui.button} ${ui.primary}`} disabled={create.isPending}>
          Add stock
        </button>
      </div>
    </form>
  );
}
