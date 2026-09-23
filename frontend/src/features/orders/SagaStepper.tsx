import ui from '../../components/ui.module.css';
import type { OrderStatusView } from '../../lib/api/schemas/order';
import { MILESTONES, STATUS_META, stepState } from './statusMeta';
import styles from './orders.module.css';

const MARK = { done: '✓', active: '', upcoming: '○' } as const;
const SPOKEN = { done: 'done', active: 'in progress', upcoming: 'not started' } as const;

/**
 * An ordered list with aria-current on the active step. Every state has a mark AND text, never
 * colour alone. Under prefers-reduced-motion the spinner is static (CSS), and the text still
 * says "in progress".
 *
 * `live` is off for read-only uses (admin lookup), where announcing would be noise.
 */
export function SagaStepper({ status, live = true }: { status: OrderStatusView; live?: boolean }) {
  const meta = STATUS_META[status];
  return (
    <div>
      <p role={live ? 'status' : undefined} aria-live={live ? 'polite' : undefined}>
        <strong>{meta.headline}</strong>
      </p>
      <ol className={styles.stepper} aria-label="Order progress">
        {MILESTONES.map((name, index) => {
          const state = stepState(status, index);
          return (
            <li
              key={name}
              className={styles.step}
              data-state={state}
              aria-current={state === 'active' ? 'step' : undefined}
            >
              <span className={styles.mark} aria-hidden="true">
                {state === 'active' ? <span className={styles.spinner} /> : MARK[state]}
              </span>
              <span>
                {name}
                <span className={ui.visuallyHidden}> ({SPOKEN[state]})</span>
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
