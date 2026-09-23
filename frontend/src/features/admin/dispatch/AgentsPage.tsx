import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { Field } from '../../../components/Field';
import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { createAgent, listAgents } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function AgentsPage() {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ userId: '', name: '', phone: '' });
  const agents = useQuery({
    queryKey: queryKeys.dispatch.agents(),
    queryFn: ({ signal }) => listAgents({ signal }),
  });
  const create = useMutation({
    mutationFn: () =>
      createAgent({ userId: form.userId.trim(), name: form.name.trim(), phone: form.phone.trim() }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.dispatch.agents() }),
    onSuccess: () => setForm({ userId: '', name: '', phone: '' }),
  });
  const badId = form.userId.trim() !== '' && !UUID.test(form.userId.trim());
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!badId) create.mutate();
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Delivery agents">Delivery agents</PageHeading>
      {agents.isPending ? (
        <Loading />
      ) : agents.isError ? (
        <ProblemView error={agents.error} onRetry={() => void agents.refetch()} />
      ) : agents.data.length === 0 ? (
        <p>No agents yet.</p>
      ) : (
        <table className={ui.table}>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Phone</th>
              <th scope="col">User id</th>
            </tr>
          </thead>
          <tbody>
            {agents.data.map((a) => (
              <tr key={a.id}>
                <td>{a.name}</td>
                <td>{a.phone ?? '--'}</td>
                <td className={ui.mono}>{a.userId}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <form className={`${ui.form} ${ui.card}`} onSubmit={submit} aria-label="New agent">
        <h2>New agent</h2>
        <p className={`${ui.small} ${ui.muted}`}>
          Paste the user&apos;s id: the platform has no user search. The user must already hold the
          DELIVERY_AGENT role, and no endpoint can grant it yet.
        </p>
        <Field label="User id" error={badId ? 'This does not look like a user id (a UUID).' : null}>
          {(p) => (
            <input
              {...p}
              required
              className={ui.mono}
              value={form.userId}
              onChange={(e) => setForm({ ...form, userId: e.target.value })}
            />
          )}
        </Field>
        <Field label="Name">
          {(p) => (
            <input
              {...p}
              required
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          )}
        </Field>
        <Field label="Phone">
          {(p) => (
            <input
              {...p}
              required
              type="tel"
              value={form.phone}
              onChange={(e) => setForm({ ...form, phone: e.target.value })}
            />
          )}
        </Field>
        {create.isError ? <ProblemView error={create.error} /> : null}
        <div>
          <button
            type="submit"
            className={`${ui.button} ${ui.primary}`}
            disabled={create.isPending}
          >
            Create agent
          </button>
        </div>
      </form>
    </section>
  );
}
