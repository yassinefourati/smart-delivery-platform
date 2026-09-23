import ui from './ui.module.css';

/**
 * Skeleton lines in the shape of what is coming, so the layout does not jump when data lands.
 * The visible bars are decorative; the status text is what assistive technology announces.
 */
export function Loading({ label = 'Loading', lines = 3 }: { label?: string; lines?: number }) {
  return (
    <div role="status" className={ui.stack}>
      <span className={ui.visuallyHidden}>{label}...</span>
      {Array.from({ length: lines }, (_, i) => (
        <div
          key={i}
          aria-hidden="true"
          className={ui.skeleton}
          style={{ width: `${90 - i * 15}%` }}
        />
      ))}
    </div>
  );
}
