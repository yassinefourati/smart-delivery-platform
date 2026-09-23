import { useState } from 'react';

import { formatReport, useRecentRequests } from '../lib/support/correlationLog';
import { PageHeading } from './PageHeading';
import ui from './ui.module.css';

/**
 * The last twenty requests this tab made, each with the correlation id that finds it in every
 * service's logs. The copy button says exactly what it copies, because people are rightly wary
 * of pasting things they cannot see: method, path TEMPLATE, status, time and id. No bodies, no
 * headers, no order or user ids.
 */
export function SupportPage() {
  const entries = useRecentRequests();
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(formatReport(entries));
      setCopied(true);
    } catch {
      // Clipboard denied: the table is on screen to copy by hand.
    }
  };
  return (
    <section className={ui.page}>
      <PageHeading title="Support">Support report</PageHeading>
      <p>
        If something went wrong, send this report with your message. It contains, for each of the
        last {entries.length} requests this tab made: the time, the method, the kind of page it
        asked for, the result and a support code. It contains no passwords, no personal details and
        no order contents. It is cleared when you reload the page.
      </p>
      {entries.length === 0 ? (
        <p className={ui.muted}>No requests recorded in this tab yet.</p>
      ) : (
        <>
          <div>
            <button type="button" className={ui.button} onClick={() => void copy()}>
              {copied ? 'Copied' : 'Copy report'}
            </button>
          </div>
          <table className={ui.table}>
            <caption className={ui.visuallyHidden}>Recent requests</caption>
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Request</th>
                <th scope="col">Result</th>
                <th scope="col">Support code</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e) => (
                <tr key={`${e.correlationId}-${e.at}`}>
                  <td>{new Date(e.at).toLocaleTimeString()}</td>
                  <td className={ui.mono}>
                    {e.method} {e.pathTemplate}
                  </td>
                  <td>{e.status || 'no response'}</td>
                  <td className={ui.mono}>{e.correlationId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
