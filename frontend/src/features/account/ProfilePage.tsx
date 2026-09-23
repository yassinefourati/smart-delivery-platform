import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';

import { Field } from '../../components/Field';
import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { getUser, updateUser } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import type { UserResponse } from '../../lib/api/schemas/user';
import { useSession } from '../../lib/auth/AuthProvider';

function ProfileForm({ user }: { user: UserResponse }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    firstName: user.firstName,
    lastName: user.lastName,
    phoneNumber: user.phoneNumber ?? '',
  });
  const mutation = useMutation({
    // UpdateUserRequest is exactly these three fields; no endpoint changes email, roles or password.
    mutationFn: () =>
      updateUser(user.id, {
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        ...(form.phoneNumber.trim() ? { phoneNumber: form.phoneNumber.trim() } : {}),
      }),
    onSuccess: (updated) => queryClient.setQueryData(queryKeys.account.profile(user.id), updated),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate();
  };
  return (
    <form className={ui.form} onSubmit={submit}>
      <div className={ui.grid2}>
        <Field label="First name">
          {(p) => (
            <input
              {...p}
              required
              value={form.firstName}
              onChange={(e) => setForm({ ...form, firstName: e.target.value })}
            />
          )}
        </Field>
        <Field label="Last name">
          {(p) => (
            <input
              {...p}
              required
              value={form.lastName}
              onChange={(e) => setForm({ ...form, lastName: e.target.value })}
            />
          )}
        </Field>
      </div>
      <Field label="Phone number (optional)">
        {(p) => (
          <input
            {...p}
            type="tel"
            value={form.phoneNumber}
            onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })}
          />
        )}
      </Field>
      {mutation.isError ? <ProblemView error={mutation.error} /> : null}
      {mutation.isSuccess ? (
        <p role="status" className={`${ui.notice} ${ui.success}`}>
          Saved.
        </p>
      ) : null}
      <div>
        <button
          type="submit"
          className={`${ui.button} ${ui.primary}`}
          disabled={mutation.isPending}
        >
          {mutation.isPending ? 'Saving...' : 'Save changes'}
        </button>
      </div>
    </form>
  );
}

export function ProfilePage() {
  const { userId } = useSession();
  const user = useQuery({
    queryKey: queryKeys.account.profile(userId ?? ''),
    queryFn: ({ signal }) => getUser(userId ?? '', { signal }),
    enabled: userId !== null,
    staleTime: STALE_TIME.ACCOUNT,
  });
  return (
    <section className={ui.page}>
      <PageHeading title="Your account">Your account</PageHeading>
      <p>
        <Link to="/account/addresses">Delivery addresses</Link> -{' '}
        <Link to="/orders">Your orders</Link>
      </p>
      {user.isPending ? (
        <Loading label="Loading your account" />
      ) : user.isError ? (
        <ProblemView error={user.error} onRetry={() => void user.refetch()} />
      ) : (
        <div className={ui.card}>
          <dl className={ui.grid2}>
            <div>
              <dt className={ui.muted}>Email</dt>
              <dd>{user.data.email}</dd>
            </div>
            <div>
              <dt className={ui.muted}>Account type</dt>
              <dd>{user.data.roles.join(', ')}</dd>
            </div>
          </dl>
          <p className={`${ui.small} ${ui.muted}`}>
            Email and account type cannot be changed here, and there is no password change yet: the
            platform has no endpoint for either.
          </p>
          {/* key: a refetched profile re-seeds the form instead of fighting it. */}
          <ProfileForm key={user.data.id} user={user.data} />
        </div>
      )}
    </section>
  );
}
