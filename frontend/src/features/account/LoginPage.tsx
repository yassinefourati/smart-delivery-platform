import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { Field } from '../../components/Field';
import { PageHeading } from '../../components/PageHeading';
import { ProblemView } from '../../components/ProblemView';
import ui from '../../components/ui.module.css';
import { isApiProblem } from '../../lib/api/problem';
import { useSession } from '../../lib/auth/AuthProvider';
import { safeNextPath } from '../../lib/auth/nextPath';

const REASONS: Record<string, string> = {
  expired:
    'Your session ended. Sessions last one hour. Sign in again to carry on; your cart is kept.',
  unauthorized: 'Your session is no longer valid. Please sign in again; your cart is kept.',
  reload:
    'For your security, reloading the page signs you out (your session is held in memory only). Your cart is kept.',
};

export function LoginPage() {
  const { login } = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  // Never navigate to a raw query value: `?next=//evil.example` would be an open redirect.
  const next = safeNextPath(params.get('next'));
  const reason = REASONS[params.get('reason') ?? ''];
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const mutation = useMutation({
    mutationFn: () => login(email.trim(), password),
    onSuccess: () => void navigate(next, { replace: true }),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate();
  };

  const badCredentials = isApiProblem(mutation.error) && mutation.error.code === 'UNAUTHORIZED';

  return (
    <section className={ui.page}>
      <PageHeading title="Sign in">Sign in</PageHeading>
      {reason ? <p className={`${ui.notice} ${ui.info}`}>{reason}</p> : null}
      <form className={ui.form} onSubmit={submit} noValidate={false}>
        <Field label="Email">
          {(p) => (
            <input
              {...p}
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          )}
        </Field>
        <Field label="Password">
          {(p) => (
            <input
              {...p}
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          )}
        </Field>
        {badCredentials ? (
          // The platform deliberately does not say which half was wrong, and neither do we.
          <p role="alert" className={`${ui.notice} ${ui.error}`}>
            That email and password do not match an account.
          </p>
        ) : mutation.isError ? (
          <ProblemView error={mutation.error} />
        ) : null}
        <div className={ui.row}>
          <button
            type="submit"
            className={`${ui.button} ${ui.primary}`}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? 'Signing in...' : 'Sign in'}
          </button>
          <Link to={`/register${params.get('next') ? `?next=${encodeURIComponent(next)}` : ''}`}>
            Create an account
          </Link>
        </div>
        <p className={`${ui.small} ${ui.muted}`}>
          Sessions last one hour and cannot be extended; you will be asked to sign in again after
          that.
        </p>
        {import.meta.env.DEV ? (
          <p className={`${ui.small} ${ui.muted}`}>
            Development note: user-service generates a throwaway signing key at startup when none is
            configured, so restarting it signs everyone out.
          </p>
        ) : null}
      </form>
    </section>
  );
}
