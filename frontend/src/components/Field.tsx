import { useId, type ReactNode } from 'react';

import ui from './ui.module.css';

export interface FieldControlProps {
  id: string;
  'aria-invalid': boolean;
  'aria-describedby': string | undefined;
}

/**
 * Label, hint and error wired to the control by id, so a screen reader reads all three when the
 * control takes focus. A render prop rather than cloneElement: the wiring is visible at the call
 * site and typed.
 *
 * Client-side rules on these fields are UX, labelled as such in each form. The server's
 * VALIDATION_ERROR is authoritative and renders as a form-level banner, because its `detail`
 * is display text and must never be parsed into per-field errors.
 */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: ReactNode;
  error?: string | null | undefined;
  children: (props: FieldControlProps) => ReactNode;
}) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={ui.field}>
      <label htmlFor={id}>{label}</label>
      {children({ id, 'aria-invalid': Boolean(error), 'aria-describedby': describedBy })}
      {hint ? (
        <span id={hintId} className={ui.fieldHint}>
          {hint}
        </span>
      ) : null}
      {error ? (
        <span id={errorId} className={ui.fieldError}>
          {error}
        </span>
      ) : null}
    </div>
  );
}
