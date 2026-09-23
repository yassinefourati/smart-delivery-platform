import { useEffect, useRef, type ReactNode } from 'react';

/**
 * The page's h1, which takes focus when the page mounts.
 *
 * A single-page app does not reload on navigation, so a screen-reader user is otherwise left
 * wherever focus was on the previous page -- often the link they just activated, now gone.
 * Moving focus to the new heading announces the new page and puts the reading position at its
 * top, which is what a full page load would have done for free.
 */
export function PageHeading({ children, title }: { children: ReactNode; title: string }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = `${title} -- Smart Delivery`;
    ref.current?.focus();
  }, [title]);
  return (
    <h1 ref={ref} tabIndex={-1}>
      {children}
    </h1>
  );
}
