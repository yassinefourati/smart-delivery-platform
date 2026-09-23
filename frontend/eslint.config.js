// @ts-check
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';

/**
 * Flat config. Beyond the three recommended rule sets, FIVE rules here are load-bearing --
 * each one exists because it makes a specific, named bug a lint failure instead of an
 * archaeology exercise. They are marked LOAD-BEARING below. Please do not relax one
 * without reading its comment; every one of them is cheaper than the bug it catches.
 */

/** Everything the app ships lives here. The restrictions below are scoped to it. */
const APP_SOURCE = ['src/**/*.ts', 'src/**/*.tsx'];

/** The one directory allowed to perform network I/O. */
const HTTP_LAYER = ['src/lib/api/**/*.ts'];

/** Trees that must never be imported from outside themselves. */
const SPLIT_TREES = ['src/features/admin/**/*.{ts,tsx}', 'src/features/agent/**/*.{ts,tsx}'];

export default tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'api-contract/**'],
  },

  js.configs.recommended,

  // ---------------------------------------------------------------------------------
  // Application source: type-aware linting. `no-floating-promises` -- one of the rules
  // that actually matters here, because an un-awaited mutation is how a "place order"
  // click reports success it never observed -- only works with type information.
  // ---------------------------------------------------------------------------------
  {
    files: APP_SOURCE,
    extends: [tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // TypeScript already resolves every identifier against `lib` and the imported types,
      // and it does it better than a hand-maintained globals list. Keeping `no-undef` on
      // for TS files means duplicating `lib` in eslint config and being wrong about it.
      'no-undef': 'off',

      // `noUnusedLocals`/`noUnusedParameters` in tsconfig are the real gate; this keeps the
      // message consistent when eslint runs alone, with the standard underscore escape.
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // An un-awaited promise in this app is a request whose failure nobody sees.
      '@typescript-eslint/no-floating-promises': 'error',

      // `recommendedTypeChecked` already errors on `any`. Stated explicitly because the
      // brief requires it specifically inside src/lib/api/** and src/domain/**, and an
      // app-wide error is strictly stronger than the requirement.
      '@typescript-eslint/no-explicit-any': 'error',

      // LOAD-BEARING (1 of 5). No absolute API origin may appear in shipped code.
      // LOAD-BEARING (2 of 5). No request/response object may reach the console.
      // LOAD-BEARING (3 of 5). No `dangerouslySetInnerHTML`, anywhere.
      // All three are one rule because `no-restricted-syntax` is a single array and a
      // second config object would silently replace the first.
      'no-restricted-syntax': [
        'error',
        {
          // The API base is a RELATIVE path (src/config.ts) because the browser is
          // same-origin with the gateway in both environments. An absolute
          // `http://localhost:8080` literal in shipped code is a call that works on the
          // author's laptop, 404s behind the production nginx, and -- worse -- issues a
          // cross-origin preflight the gateway answers with a bodyless 403 that the browser
          // reports as an opaque network error. The dev proxy target lives in
          // vite.config.ts, which this rule does not cover.
          selector: 'Literal[value=/http:\\/\\/localhost:8/]',
          message:
            'No absolute API origin in src/. The API base is a relative path from src/config.ts; the dev proxy target belongs in vite.config.ts.',
        },
        {
          selector: 'TemplateElement[value.raw=/http:\\/\\/localhost:8/]',
          message:
            'No absolute API origin in src/. The API base is a relative path from src/config.ts; the dev proxy target belongs in vite.config.ts.',
        },
        {
          // src/lib/api/http.ts attaches `Authorization: Bearer <token>` and sends login
          // bodies. `console.error('failed', request)` in that file puts a bearer token or
          // a plaintext password in the browser console, where a screenshot in a support
          // ticket makes it permanent. Log method, path TEMPLATE, status, stable code and
          // correlation id -- and nothing else.
          selector:
            'CallExpression[callee.object.name="console"] > Identifier[name=/^(request|body|headers|init)$/]',
          message:
            'Never log a request, body, headers or init object: http.ts attaches Authorization and sends login bodies. Log method, path template, status, code and correlationId only.',
        },
        {
          // There is no sanitiser in this project and no trusted HTML source. Product
          // names, descriptions and problem `detail` strings are all attacker-influenced or
          // admin-typed text; React's default escaping is the control.
          selector: 'JSXAttribute[name.name="dangerouslySetInnerHTML"]',
          message:
            'dangerouslySetInnerHTML is banned: every string this app renders is admin-typed or server-supplied text, and React escaping is the control.',
        },
      ],

      // LOAD-BEARING (4 of 5). Exactly one module in this app performs network I/O.
      // Everything the platform is difficult about -- the relative base, the bearer token,
      // the outbound X-Correlation-Id, the Idempotency-Key, the `startsWith` content-type
      // check, the duplicated correlation header, the 15s abort, boundary validation -- is
      // implemented once in src/lib/api/http.ts. A second `fetch()` anywhere silently opts
      // out of all of it, and the symptom is a support request with no correlation id.
      'no-restricted-globals': [
        'error',
        {
          name: 'fetch',
          message:
            'Use src/lib/api/http.ts. It is the only fetch in the app -- it owns the bearer token, the correlation id, the Idempotency-Key, the problem+json parsing and the abort timeout.',
        },
        {
          name: 'XMLHttpRequest',
          message: 'Use src/lib/api/http.ts.',
        },
      ],
      'no-restricted-properties': [
        'error',
        {
          object: 'window',
          property: 'fetch',
          message: 'Use src/lib/api/http.ts.',
        },
        {
          object: 'globalThis',
          property: 'fetch',
          message: 'Use src/lib/api/http.ts.',
        },
      ],

      // LOAD-BEARING (5 of 5). The code-split fence. `features/admin/**` and
      // `features/agent/**` are lazy chunks; the point of splitting them is that a customer
      // never downloads them. A single import from a shared component or a customer screen
      // pulls the whole chunk back into the main bundle, and the only evidence is a number
      // in a build report nobody reads. Route ARRAYS are imported by src/routes.tsx, which
      // is exempted below -- they are a few hundred bytes and their page components are
      // behind React.lazy.
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/features/admin/*', '**/features/admin/**'],
              message:
                'features/admin/** is a lazy chunk. Only src/routes.tsx may import its route array, and only code inside features/admin/** may import the rest.',
            },
            {
              group: ['**/features/agent/*', '**/features/agent/**'],
              message:
                'features/agent/** is a lazy chunk. Only src/routes.tsx may import its route array, and only code inside features/agent/** may import the rest.',
            },
          ],
          paths: [
            {
              name: 'axios',
              message:
                'No HTTP client dependency. src/lib/api/http.ts is fetch plus the ~110 lines this platform actually needs.',
            },
          ],
        },
      ],
    },
  },

  // The http layer is the exception to its own rule: this is where `fetch` lives.
  {
    files: HTTP_LAYER,
    rules: {
      'no-restricted-globals': 'off',
      'no-restricted-properties': 'off',
    },
  },

  // A split tree may import itself. The fence is about who reaches IN.
  {
    files: SPLIT_TREES,
    rules: {
      'no-restricted-imports': 'off',
    },
  },

  // src/routes.tsx composes every feature's route ARRAY, including the two fenced trees.
  // Importing a `RouteObject[]` does not pull page code in -- each page inside those arrays
  // is wrapped in React.lazy, which is what keeps the chunk boundary at the page.
  {
    files: ['src/routes.tsx'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },

  // ---------------------------------------------------------------------------------
  // Components. `react-hooks` is not a formality here: the saga polling hook and the cart
  // persistence effect are exactly where a missing dependency array becomes an infinite
  // request loop against a live gateway. `jsx-a11y` runs in CI because this app changes the
  // screen with no user action (polling), so accessibility is a correctness property.
  // ---------------------------------------------------------------------------------
  {
    files: ['src/**/*.tsx'],
    extends: [reactHooks.configs['recommended-latest'], jsxA11y.flatConfigs.recommended],
  },

  // ---------------------------------------------------------------------------------
  // Tests may say things application code may not: assert on `any`-shaped fixtures, and
  // reach for `fetch` when a test is about the network boundary itself.
  // ---------------------------------------------------------------------------------
  {
    files: ['src/**/__tests__/**', 'src/**/*.test.ts', 'src/**/*.test.tsx', 'src/test/**'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      'no-restricted-globals': 'off',
      'no-restricted-properties': 'off',
    },
  },

  // ---------------------------------------------------------------------------------
  // Build tooling: linted, but not type-aware. It is not part of the app's tsconfig
  // project, and pulling it in would mean the app's `lib` and `jsx` settings applied to
  // Node config files.
  // ---------------------------------------------------------------------------------
  {
    files: ['vite.config.ts', 'eslint.config.js', 'scripts/**/*.mjs'],
    extends: [tseslint.configs.recommended],
    rules: {
      'no-undef': 'off',
    },
  },
);
