import { useState } from 'react';

import ui from './ui.module.css';

/**
 * The support code: the request's correlation id, which appears in the log line for that
 * request on every service it touched (the platform went to real trouble to make that true --
 * docs/observability.md). Labelled "Support code" rather than "Correlation ID" because the
 * person reading it is a customer, not an operator.
 *
 * Shows eight characters and copies all of them, plus the status, the stable error code and
 * the path TEMPLATE -- never the populated path, so no order or user ids reach a clipboard.
 */
export function CorrelationRef({
  correlationId,
  status,
  code,
  pathTemplate,
}: {
  correlationId: string;
  status?: number;
  code?: string;
  pathTemplate?: string;
}) {
  const [copied, setCopied] = useState(false);
  const report = [
    `support code: ${correlationId}`,
    status !== undefined ? `status: ${status || 'no response'}` : null,
    code ? `error: ${code}` : null,
    pathTemplate ? `request: ${pathTemplate}` : null,
  ]
    .filter(Boolean)
    .join('\n');

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(report);
      setCopied(true);
    } catch {
      // Clipboard access denied (insecure context, permissions). The code is on screen to be
      // copied by hand, which is the fallback.
    }
  };

  return (
    <span className={ui.row}>
      <span>
        Support code:{' '}
        <code className={ui.mono} title={correlationId}>
          {correlationId.slice(0, 8)}
        </code>
      </span>
      <button type="button" className={ui.linkButton} onClick={() => void copy()}>
        {copied ? 'Copied' : 'Copy details'}
      </button>
    </span>
  );
}
