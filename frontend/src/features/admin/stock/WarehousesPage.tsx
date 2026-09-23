import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { Field } from '../../../components/Field';
import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { createWarehouse, listWarehouses } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';

export function WarehousesPage() {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [location, setLocation] = useState('');
  const warehouses = useQuery({
    queryKey: queryKeys.inventory.warehouses(),
    queryFn: ({ signal }) => listWarehouses({ signal }),
  });
  const create = useMutation({
    mutationFn: () => createWarehouse({ name: name.trim(), location: location.trim() }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.inventory.warehouses() }),
    onSuccess: () => {
      setName('');
      setLocation('');
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Warehouses">Warehouses</PageHeading>
      {warehouses.isPending ? (
        <Loading />
      ) : warehouses.isError ? (
        <ProblemView error={warehouses.error} onRetry={() => void warehouses.refetch()} />
      ) : warehouses.data.length === 0 ? (
        <p>No warehouses yet.</p>
      ) : (
        <table className={ui.table}>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Location</th>
              <th scope="col">Active</th>
            </tr>
          </thead>
          <tbody>
            {warehouses.data.map((w) => (
              <tr key={w.id}>
                <td>{w.name}</td>
                <td>{w.location}</td>
                <td>{w.active ? 'Yes' : 'No'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className={`${ui.small} ${ui.muted}`}>
        Warehouses cannot be deactivated or renamed from here: the inventory API has no endpoint for
        it.
      </p>
      <form className={`${ui.form} ${ui.card}`} onSubmit={submit} aria-label="New warehouse">
        <h2>New warehouse</h2>
        <Field label="Name">
          {(p) => <input {...p} required value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Location">
          {(p) => (
            <input {...p} required value={location} onChange={(e) => setLocation(e.target.value)} />
          )}
        </Field>
        {create.isError ? <ProblemView error={create.error} /> : null}
        <div>
          <button
            type="submit"
            className={`${ui.button} ${ui.primary}`}
            disabled={create.isPending}
          >
            Create warehouse
          </button>
        </div>
      </form>
    </section>
  );
}
