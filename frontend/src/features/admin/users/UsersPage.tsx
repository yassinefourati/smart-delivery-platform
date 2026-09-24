import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';

import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import { useSession } from '../../../lib/auth/AuthProvider';
import { grantRole, lookupUserByEmail, revokeRole } from '../../../lib/api/endpoints';
import { queryKeys } from '../../../lib/api/queryKeys';
import { HUMAN_ROLES, type HumanRole, type UserResponse } from '../../../lib/api/schemas/user';
import { formText } from '../../../lib/format';

const ROLE_COPY: Record<HumanRole, string> = {
  CUSTOMER: 'Can shop and place orders.',
  ADMIN: 'Full access to every staff screen, including this one.',
  WAREHOUSE_MANAGER: 'Manages warehouses and stock.',
  DELIVERY_AGENT: 'Sees and completes their own deliveries. Also needs an agent profile.',
};

function RoleRow({ user, role, email }: { user: UserResponse; role: HumanRole; email: string }) {
  const queryClient = useQueryClient();
  const { userId: me } = useSession();
  const held = user.roles.includes(role);
  const change = useMutation({
    mutationFn: () => (held ? revokeRole(user.id, role) : grantRole(user.id, role)),
    // The response is the updated user; write it straight into the lookup's cache entry.
    onSuccess: (updated) => queryClient.setQueryData(queryKeys.users.byEmail(email), updated),
  });
  // The server refuses this too (409); hiding the button saves the round trip.
  const ownAdmin = held && role === 'ADMIN' && user.id === me;
  return (
    <tr>
      <th scope="row">{role}</th>
      <td>
        {ROLE_COPY[role]}
        {change.isError ? <ProblemView error={change.error} /> : null}
      </td>
      <td>{held ? <strong>Granted</strong> : <span className={ui.muted}>No</span>}</td>
      <td>
        {ownAdmin ? (
          <span className={`${ui.small} ${ui.muted}`}>You cannot revoke your own ADMIN.</span>
        ) : (
          <button
            type="button"
            className={held ? `${ui.button} ${ui.danger}` : `${ui.button} ${ui.primary}`}
            disabled={change.isPending}
            aria-label={`${held ? 'Revoke' : 'Grant'} ${role}`}
            onClick={() => {
              if (!change.isPending) change.mutate();
            }}
          >
            {change.isPending ? 'Saving...' : held ? 'Revoke' : 'Grant'}
          </button>
        )}
      </td>
    </tr>
  );
}

function UserResult({ email }: { email: string }) {
  const user = useQuery({
    queryKey: queryKeys.users.byEmail(email),
    queryFn: ({ signal }) => lookupUserByEmail(email, { signal }),
  });
  if (user.isPending) return <Loading lines={4} />;
  if (user.isError) {
    return 'status' in user.error && user.error.status === 404 ? (
      <p>No user has the email {email}.</p>
    ) : (
      <ProblemView error={user.error} onRetry={() => void user.refetch()} />
    );
  }
  const u = user.data;
  return (
    <div className={`${ui.card} ${ui.stack}`}>
      <h2>
        {u.firstName} {u.lastName}
      </h2>
      <p>
        {u.email} - user id <span className={ui.mono}>{u.id}</span>
      </p>
      <table className={ui.table}>
        <thead>
          <tr>
            <th scope="col">Role</th>
            <th scope="col">What it allows</th>
            <th scope="col">Held</th>
            <th scope="col">
              <span className={ui.visuallyHidden}>Change</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {HUMAN_ROLES.map((role) => (
            <RoleRow key={role} user={u} role={role} email={email} />
          ))}
        </tbody>
      </table>
      <p className={`${ui.notice} ${ui.info}`}>
        Role changes reach this user the next time they sign in. A revoked role keeps working until
        their current session expires (at most an hour).
      </p>
      {u.roles.includes('DELIVERY_AGENT') ? (
        <p>
          To assign deliveries to this user,{' '}
          <Link to={`/admin/agents?userId=${u.id}`}>create their agent profile</Link> if they do not
          have one yet.
        </p>
      ) : null}
    </div>
  );
}

/**
 * Look a user up by exact email and grant or revoke roles. A lookup, not a user list: the API
 * has no endpoint that lists users.
 */
export function UsersPage() {
  const [params, setParams] = useSearchParams();
  const email = params.get('email')?.trim() ?? '';
  const [draft, setDraft] = useState(email);
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const value = formText(new FormData(e.currentTarget), 'email');
    setParams(value ? { email: value } : {});
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Users and roles">Users and roles</PageHeading>
      <form className={ui.grid2} onSubmit={submit} aria-label="Find a user">
        <div className={ui.field}>
          <label htmlFor="user-email">Email</label>
          <input
            id="user-email"
            name="email"
            type="email"
            required
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
        <div>
          <button type="submit" className={`${ui.button} ${ui.primary}`}>
            Find user
          </button>
        </div>
      </form>
      {email ? <UserResult key={email} email={email} /> : null}
    </section>
  );
}
