import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { Field } from '../../components/Field';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { registerUser } from '../../lib/api/endpoints';
import { isApiProblem } from '../../lib/api/problem';
import type { RegisterUserRequest } from '../../lib/api/schemas/user';
import { useSession } from '../../lib/auth/AuthProvider';
import { safeNextPath } from '../../lib/auth/nextPath';

/** Mirrors user-service's @Size(min = 8). UX only; the server is authoritative. */
export const MIN_PASSWORD_LENGTH = 8;

export function RegisterPage() {
  const { login } = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNextPath(params.get('next'));
  const [form, setForm] = useState({
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    phoneNumber: '',
  });
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm({ ...form, [key]: e.target.value });

  const mutation = useMutation({
    mutationFn: async () => {
      const body: RegisterUserRequest = {
        email: form.email.trim(),
        password: form.password,
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        // Omitted rather than sent empty: exactOptionalPropertyTypes keeps `undefined` off the wire.
        ...(form.phoneNumber.trim() ? { phoneNumber: form.phoneNumber.trim() } : {}),
      };
      await registerUser(body);
      // Straight in: asking someone to type the password they just chose is friction for nothing.
      await login(body.email, body.password);
    },
    onSuccess: () => void navigate(next, { replace: true }),
  });

  const emailTaken = isApiProblem(mutation.error) && mutation.error.code === 'EMAIL_ALREADY_EXISTS';
  const tooShort = form.password.length > 0 && form.password.length < MIN_PASSWORD_LENGTH;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (tooShort) return;
    mutation.mutate();
  };

  return (
    <section className={ui.page}>
      <PageHeading title="Create an account">Create an account</PageHeading>
      <p className={ui.muted}>
        This creates a customer account. Staff accounts are set up by an administrator.
      </p>
      <form className={ui.form} onSubmit={submit}>
        <div className={ui.grid2}>
          <Field label="First name">
            {(p) => (
              <input
                {...p}
                required
                autoComplete="given-name"
                value={form.firstName}
                onChange={set('firstName')}
              />
            )}
          </Field>
          <Field label="Last name">
            {(p) => (
              <input
                {...p}
                required
                autoComplete="family-name"
                value={form.lastName}
                onChange={set('lastName')}
              />
            )}
          </Field>
        </div>
        <Field
          label="Email"
          error={emailTaken ? 'An account with this email already exists.' : null}
          hint={
            emailTaken ? (
              <Link to={`/login?next=${encodeURIComponent(next)}`}>Sign in instead</Link>
            ) : undefined
          }
        >
          {(p) => (
            <input
              {...p}
              type="email"
              required
              autoComplete="email"
              value={form.email}
              onChange={set('email')}
            />
          )}
        </Field>
        <Field
          label="Password"
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          error={tooShort ? `Use at least ${MIN_PASSWORD_LENGTH} characters.` : null}
        >
          {(p) => (
            <input
              {...p}
              type="password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              autoComplete="new-password"
              value={form.password}
              onChange={set('password')}
            />
          )}
        </Field>
        <Field label="Phone number (optional)">
          {(p) => (
            <input
              {...p}
              type="tel"
              autoComplete="tel"
              value={form.phoneNumber}
              onChange={set('phoneNumber')}
            />
          )}
        </Field>
        {mutation.isError && !emailTaken ? <ProblemView error={mutation.error} /> : null}
        <div className={ui.row}>
          <button
            type="submit"
            className={`${ui.button} ${ui.primary}`}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? 'Creating account...' : 'Create account'}
          </button>
          <Link to="/login">I already have an account</Link>
        </div>
      </form>
    </section>
  );
}
