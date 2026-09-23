import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';

import { Field } from '../../components/Field';
import { Loading } from '../../components/Loading';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import {
  createAddress,
  deleteAddress,
  listAddresses,
  updateAddress,
} from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import type { AddressRequest, AddressResponse } from '../../lib/api/schemas/user';
import { useSession } from '../../lib/auth/AuthProvider';

const EMPTY = {
  label: '',
  street: '',
  city: '',
  state: '',
  postalCode: '',
  country: '',
  isDefault: false,
};

/** `state` is optional and OMITTED when blank -- never sent as undefined or "" (it would store as null/empty). */
export function toAddressRequest(form: typeof EMPTY): AddressRequest {
  return {
    label: form.label.trim(),
    street: form.street.trim(),
    city: form.city.trim(),
    postalCode: form.postalCode.trim(),
    country: form.country.trim(),
    isDefault: form.isDefault,
    ...(form.state.trim() ? { state: form.state.trim() } : {}),
  };
}

function AddressForm({
  userId,
  existing,
  onDone,
}: {
  userId: string;
  existing: AddressResponse | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState(
    existing
      ? {
          label: existing.label,
          street: existing.street,
          city: existing.city,
          state: existing.state ?? '',
          postalCode: existing.postalCode,
          country: existing.country,
          isDefault: existing.isDefault,
        }
      : EMPTY,
  );
  const errorRef = useRef<HTMLDivElement>(null);
  const mutation = useMutation({
    mutationFn: () =>
      existing
        ? updateAddress(userId, existing.id, toAddressRequest(form))
        : createAddress(userId, toAddressRequest(form)),
    // Checkout reads the address list, so every write invalidates it.
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.account.addresses(userId) }),
    onSuccess: onDone,
  });
  // Focus the error summary so a keyboard or screen-reader user lands on what went wrong.
  useEffect(() => {
    if (mutation.isError) errorRef.current?.focus();
  }, [mutation.isError]);

  const text = (key: Exclude<keyof typeof EMPTY, 'isDefault'>) => ({
    value: form[key],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value }),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate();
  };
  return (
    <form
      className={`${ui.form} ${ui.card}`}
      onSubmit={submit}
      aria-label={existing ? 'Edit address' : 'New address'}
    >
      <h2>{existing ? 'Edit address' : 'New address'}</h2>
      {mutation.isError ? (
        <div ref={errorRef} tabIndex={-1}>
          <ProblemView error={mutation.error} />
        </div>
      ) : null}
      <Field label="Label" hint="For example Home or Work.">
        {(p) => <input {...p} required {...text('label')} />}
      </Field>
      <Field label="Street">
        {(p) => <input {...p} required autoComplete="street-address" {...text('street')} />}
      </Field>
      <div className={ui.grid2}>
        <Field label="City">
          {(p) => <input {...p} required autoComplete="address-level2" {...text('city')} />}
        </Field>
        <Field label="State or region (optional)">
          {(p) => <input {...p} autoComplete="address-level1" {...text('state')} />}
        </Field>
        <Field label="Postal code">
          {(p) => <input {...p} required autoComplete="postal-code" {...text('postalCode')} />}
        </Field>
        <Field label="Country">
          {(p) => <input {...p} required autoComplete="country-name" {...text('country')} />}
        </Field>
      </div>
      <label className={ui.row}>
        <input
          type="checkbox"
          checked={form.isDefault}
          onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
        />
        Use as my default delivery address
      </label>
      <div className={ui.row}>
        <button
          type="submit"
          className={`${ui.button} ${ui.primary}`}
          disabled={mutation.isPending}
        >
          {mutation.isPending ? 'Saving...' : 'Save address'}
        </button>
        <button type="button" className={ui.button} onClick={onDone}>
          Cancel
        </button>
      </div>
    </form>
  );
}

export function AddressesPage() {
  const { userId } = useSession();
  const uid = userId ?? '';
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<AddressResponse | 'new' | null>(null);
  const addresses = useQuery({
    queryKey: queryKeys.account.addresses(uid),
    queryFn: ({ signal }) => listAddresses(uid, { signal }),
    enabled: userId !== null,
    staleTime: STALE_TIME.ACCOUNT,
  });
  const remove = useMutation({
    mutationFn: (addressId: string) => deleteAddress(uid, addressId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.account.addresses(uid) }),
  });

  return (
    <section className={ui.page}>
      <PageHeading title="Delivery addresses">Delivery addresses</PageHeading>
      {remove.isError ? <ProblemView error={remove.error} /> : null}
      {editing !== null ? (
        <AddressForm
          key={editing === 'new' ? 'new' : editing.id}
          userId={uid}
          existing={editing === 'new' ? null : editing}
          onDone={() => setEditing(null)}
        />
      ) : (
        <div>
          <button
            type="button"
            className={`${ui.button} ${ui.primary}`}
            onClick={() => setEditing('new')}
          >
            Add an address
          </button>
        </div>
      )}
      {addresses.isPending ? (
        <Loading label="Loading addresses" />
      ) : addresses.isError ? (
        <ProblemView error={addresses.error} onRetry={() => void addresses.refetch()} />
      ) : addresses.data.length === 0 ? (
        <p>No saved addresses yet.</p>
      ) : (
        <ul className={ui.stack} style={{ listStyle: 'none', padding: 0 }}>
          {addresses.data.map((a) => (
            <li key={a.id} className={`${ui.card} ${ui.spread}`}>
              <div>
                <strong>{a.label}</strong>
                {a.isDefault ? <span className={ui.muted}> (default)</span> : null}
                <div>
                  {a.street}, {a.city}
                  {a.state ? `, ${a.state}` : ''} {a.postalCode}, {a.country}
                </div>
              </div>
              <div className={ui.row}>
                <button type="button" className={ui.button} onClick={() => setEditing(a)}>
                  Edit <span className={ui.visuallyHidden}>{a.label}</span>
                </button>
                <button
                  type="button"
                  className={`${ui.button} ${ui.danger}`}
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(a.id)}
                >
                  Delete <span className={ui.visuallyHidden}>{a.label}</span>
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
