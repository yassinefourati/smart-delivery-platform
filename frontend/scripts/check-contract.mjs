#!/usr/bin/env node
/**
 * Does the gateway still serve the API documents this client was written against?
 *
 * Fetches all six `/v3/api-docs/<service>` documents from a reachable gateway, normalises them,
 * and diffs them against the snapshots committed under `src/api-snapshots/`. Exits non-zero with
 * a readable per-file diff when anything moved.
 *
 *   node scripts/check-contract.mjs                        # against http://localhost:8080
 *   node scripts/check-contract.mjs --base http://host:8080
 *   node scripts/check-contract.mjs --update               # rewrite the snapshots
 *
 * WHY THIS EXISTS AT ALL, GIVEN THAT THE SCHEMAS ARE PARSED AT THE FETCH BOUNDARY. Boundary
 * parsing catches drift when a user hits the affected screen, which is late and is one user's
 * problem. This catches it on a laptop, before a commit, for the whole surface at once.
 * docs/api-documentation.md already names this ground as sand: "no CI check that the API has not
 * changed incompatibly". This is that check, for the client's half of it.
 *
 * WHAT IT DOES NOT DO, STATED SO NOBODY EXPECTS IT: it tells you THAT something changed, not what
 * to edit. Pair it with `npm run verify:live`, which asserts BEHAVIOUR the documents do not
 * describe -- and the behaviours that matter most here are all in that category: the working but
 * undocumented `page`/`size`/`sort`, the 201-with-the-same-id on an idempotent replay, the 409 on
 * a changed body, and the charset-suffixed `application/problem+json` on a 401.
 *
 * PLAIN NODE 22 `fetch`, NO DEPENDENCIES, NO DOCKER. That is the point: this is a pre-commit
 * habit, not a job that needs a compose stack.
 */

import { readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

/** The six documents. `/v3/api-docs/swagger-config` lists exactly these names. */
const SERVICES = [
  'user-service',
  'product-service',
  'inventory-service',
  'order-service',
  'payment-service',
  'delivery-service',
];

const DEFAULT_BASE = 'http://localhost:8080';

const SNAPSHOT_DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  'src',
  'api-snapshots',
);

const FAILURE_MESSAGE =
  "the gateway's API documents changed -- review frontend/src/lib/api/schemas/";

/* -------------------------------------------------------------------------------------- */

function parseArgs(argv) {
  const args = { base: DEFAULT_BASE, update: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--update') {
      args.update = true;
    } else if (arg === '--base') {
      const next = argv[i + 1];
      if (next === undefined) {
        throw new Error('--base needs a value, e.g. --base http://localhost:8080');
      }
      args.base = next.replace(/\/+$/, '');
      i += 1;
    } else if (arg.startsWith('--base=')) {
      args.base = arg.slice('--base='.length).replace(/\/+$/, '');
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

/**
 * Sort every object's keys, recursively, and drop the one block that is not part of the contract.
 *
 * SORTING: springdoc assembles parts of the document from unordered maps, so key order is not
 * guaranteed stable between restarts of an unchanged service. Diffing raw JSON would report a
 * change every time somebody bounced a container, and a check that cries wolf is a check somebody
 * disables.
 *
 * DROPPING `servers`: that block is `[{ "url": "<the base you fetched from>" }]`, so it describes
 * the REQUEST, not the API. Keeping it would make `--base` against any other gateway fail on every
 * file for the one reason that is not a contract change -- and it would put an absolute
 * `http://localhost:8080` literal inside `src/`, which is precisely what this project's ESLint
 * rule bans in shipped code.
 *
 * ARRAYS ARE NOT SORTED. `enum` member order and `required` member order are meaningful to a
 * reader, and reordering them to make a diff quieter would hide a real edit.
 */
function normalise(value) {
  if (Array.isArray(value)) {
    return value.map(normalise);
  }
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      if (key === 'servers') {
        continue;
      }
      out[key] = normalise(value[key]);
    }
    return out;
  }
  return value;
}

function render(document) {
  return `${JSON.stringify(normalise(document), null, 2)}\n`;
}

async function fetchDocument(base, service) {
  const url = `${base}/v3/api-docs/${service}`;
  let res;
  try {
    res = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (cause) {
    throw new Error(
      `Could not reach ${url}. Is the platform running? See docs/local-development.md.\n  ${String(cause)}`,
    );
  }
  if (!res.ok) {
    throw new Error(`${url} answered ${res.status}. Expected 200 with the OpenAPI document.`);
  }
  return res.json();
}

/**
 * A line diff, so a failure names the lines rather than saying "the file differs".
 *
 * A plain longest-common-subsequence table. These documents are a few hundred lines, so the
 * O(n*m) table is a few hundred thousand cells and runs instantly -- not worth a dependency, and
 * not worth shelling out to `diff` and inheriting its exit codes.
 */
function diffLines(expected, actual) {
  const a = expected.split('\n');
  const b = actual.split('\n');
  const lcs = Array.from({ length: a.length + 1 }, () => new Uint32Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      i += 1;
      j += 1;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push(`  - ${a[i]}`);
      i += 1;
    } else {
      out.push(`  + ${b[j]}`);
      j += 1;
    }
  }
  for (; i < a.length; i += 1) {
    out.push(`  - ${a[i]}`);
  }
  for (; j < b.length; j += 1) {
    out.push(`  + ${b[j]}`);
  }
  return out;
}

/** `-` is the snapshot, `+` is the live gateway. Capped, because a rename moves every line. */
const MAX_DIFF_LINES = 40;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const results = [];

  for (const service of SERVICES) {
    const snapshotPath = path.join(SNAPSHOT_DIR, `${service}.json`);
    const live = render(await fetchDocument(args.base, service));

    if (args.update) {
      await writeFile(snapshotPath, live, 'utf8');
      results.push({ service, state: 'updated', detail: [] });
      continue;
    }

    if (!existsSync(snapshotPath)) {
      results.push({
        service,
        state: 'missing',
        detail: [`  snapshot not found: ${path.relative(process.cwd(), snapshotPath)}`],
      });
      continue;
    }

    const snapshot = await readFile(snapshotPath, 'utf8');
    if (snapshot === live) {
      results.push({ service, state: 'match', detail: [] });
    } else {
      const diff = diffLines(snapshot, live);
      const shown = diff.slice(0, MAX_DIFF_LINES);
      if (diff.length > MAX_DIFF_LINES) {
        shown.push(`  ... ${diff.length - MAX_DIFF_LINES} more differing lines`);
      }
      results.push({ service, state: 'changed', detail: shown });
    }
  }

  const width = Math.max(...SERVICES.map((service) => service.length));
  console.log(`\ncheck:contract  base=${args.base}\n`);
  for (const result of results) {
    console.log(`  ${result.service.padEnd(width)}  ${result.state}`);
  }

  if (args.update) {
    console.log(
      `\nRewrote ${String(results.length)} snapshots from ${args.base}. Review the diff before` +
        ' committing: this records a contract change, so the schema edit belongs in the same commit.\n',
    );
    return;
  }

  const failures = results.filter(
    (result) => result.state === 'changed' || result.state === 'missing',
  );
  if (failures.length === 0) {
    console.log(`\nAll ${String(results.length)} documents match.\n`);
    return;
  }

  for (const failure of failures) {
    console.log(`\n--- ${failure.service} (- snapshot, + live) ---`);
    for (const line of failure.detail) {
      console.log(line);
    }
  }
  console.log(`\n${FAILURE_MESSAGE}`);
  console.log(
    'If the change is intended, run `node scripts/check-contract.mjs --update` and commit the new\n' +
      'snapshots together with the schema edits -- a snapshot updated on its own records a change\n' +
      'nobody read.\n',
  );
  process.exitCode = 1;
}

try {
  await main();
} catch (error) {
  console.error(
    `\ncheck:contract failed: ${error instanceof Error ? error.message : String(error)}\n`,
  );
  process.exitCode = 1;
}
