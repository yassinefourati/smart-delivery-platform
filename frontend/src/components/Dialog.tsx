import { useEffect, useRef, type ReactNode } from 'react';

import ui from './ui.module.css';

/**
 * A confirmation dialog on the native <dialog> element: focus trapping, Escape to close, the
 * inert backdrop and returning focus to the opener all come from the browser, which gets them
 * right more reliably than a hand-rolled modal does.
 *
 * Controlled by `open`. jsdom has no showModal(), so tests fall back to the `open` attribute;
 * the behaviour under test (what the dialog says and what its buttons do) is the same.
 */
export function Dialog({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
    }
  }, [open]);

  return (
    <dialog ref={ref} className={ui.dialog} aria-label={title} onClose={onClose} onCancel={onClose}>
      {open ? (
        <div className={ui.stack}>
          <h2>{title}</h2>
          {children}
        </div>
      ) : null}
    </dialog>
  );
}
