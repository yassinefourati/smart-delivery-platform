import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';

import { formatReport, useRecentRequests } from '../lib/support/correlationLog';
import { describeRequest, outcomeOf, type Outcome } from '../lib/support/describeRequest';
import { PageHeading } from './PageHeading';
import styles from './SupportPage.module.css';
import ui from './ui.module.css';

const OUTCOME_LABEL: Record<Outcome, string> = {
  ok: 'Worked',
  problem: 'Failed',
  unreachable: 'No response',
};

/** Answers taken from how the platform actually behaves -- see docs/order-flow.md. */
const QUESTIONS: readonly { q: string; a: ReactNode }[] = [
  {
    q: 'How do I track my order?',
    a: (
      <>
        Open <Link to="/orders">your orders</Link> and choose one. Its page shows each step as it
        happens: Placed, Reserving stock, Taking payment, Preparing shipment, On its way and
        Delivered. It updates by itself while the order is being processed.
      </>
    ),
  },
  {
    q: 'Can I cancel an order?',
    a: 'Yes, until we start preparing your shipment. Open the order and choose Cancel: we release your items and, if you had already paid, refund you automatically. From "Preparing shipment" onwards the order can no longer be cancelled.',
  },
  {
    q: 'My order says it is waiting for a courier. What does that mean?',
    a: 'Your order is paid and its shipment is ready. It moves to "On its way" as soon as a courier is assigned to it, and to "Delivered" when the courier hands it over.',
  },
  {
    q: 'When am I charged?',
    a: 'Right after we reserve your items, a few seconds after you place the order. If the items are not in stock, you are not charged. The final price is the one shown when you place the order.',
  },
  {
    q: 'Why does a product say "Only 2 left" or "Out of stock"?',
    a: 'Stock is live: it is the total available across our warehouses right now. If an item runs out while it is in your cart, the cart tells you before you check out.',
  },
  {
    q: 'My order could not be completed. Was I charged?',
    a: 'If an order fails after payment, the payment is refunded automatically. If you are unsure, send us the order number from the order page along with a problem report from below.',
  },
];

/**
 * Help: common questions first, then "Report a problem" -- the last requests this tab made,
 * each with the support code that finds it in every service's logs. The copy button says
 * exactly what it copies, because people are rightly wary of pasting things they cannot see:
 * time, method, path TEMPLATE, status and code. No bodies, no headers, no order or user ids.
 */
export function SupportPage() {
  const entries = useRecentRequests();
  const [copied, setCopied] = useState(false);
  const failures = entries.filter((e) => outcomeOf(e.status) !== 'ok').length;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(formatReport(entries));
      setCopied(true);
    } catch {
      // Clipboard denied: the list is on screen to copy by hand.
    }
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Help">Help</PageHeading>

      <div className={styles.layout}>
        <div className={styles.faq}>
          <h2>Common questions</h2>
          {QUESTIONS.map(({ q, a }) => (
            <details key={q} className={styles.question}>
              <summary>{q}</summary>
              <p>{a}</p>
            </details>
          ))}
        </div>

        <div className={`${ui.card} ${styles.report}`}>
          <h2>Report a problem</h2>
          <p>
            If something did not work, copy this report and send it with a short description of what
            you were doing. It lets our team find exactly what went wrong.
          </p>
          <p className={`${ui.small} ${ui.muted}`}>
            It lists what this browser tab asked our servers to do, the result, and a support code
            for each. It contains no passwords, personal details or order contents, and it is
            cleared when you reload the page.
          </p>

          {entries.length === 0 ? (
            <p className={ui.muted}>Nothing recorded in this tab yet.</p>
          ) : (
            <>
              <p
                className={`${ui.notice} ${failures > 0 ? ui.warn : ui.success}`}
                aria-live="polite"
              >
                {failures === 0
                  ? 'Everything you did in this tab worked.'
                  : `${failures} ${failures === 1 ? 'thing' : 'things'} did not work. Copy the report and send it to us.`}
              </p>
              <div>
                <button
                  type="button"
                  className={`${ui.button} ${failures > 0 ? ui.primary : ''}`}
                  onClick={() => void copy()}
                >
                  {copied ? 'Copied' : 'Copy report'}
                </button>
              </div>
              <ol className={styles.activity} aria-label="Recent activity">
                {entries.map((e) => {
                  const outcome = outcomeOf(e.status);
                  return (
                    <li key={`${e.correlationId}-${e.at}`} className={styles.entry}>
                      <span className={styles.when}>{new Date(e.at).toLocaleTimeString()}</span>
                      <span className={styles.what}>
                        {describeRequest(e.method, e.pathTemplate)}
                        <span className={styles.code}>Support code {e.correlationId}</span>
                      </span>
                      <span className={styles.outcome} data-outcome={outcome}>
                        {OUTCOME_LABEL[outcome]}
                        {outcome === 'problem' ? ` (${e.status})` : ''}
                      </span>
                    </li>
                  );
                })}
              </ol>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
