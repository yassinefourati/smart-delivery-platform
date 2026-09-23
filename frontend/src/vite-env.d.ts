/// <reference types="vite/client" />

/**
 * Typed build-time environment. Vite INLINES every `VITE_`-prefixed variable into the
 * bundle it produces, so everything declared here is public by construction: it ships to
 * every browser that loads the app and is readable in devtools. Nothing secret may be
 * added to this interface, ever. This client holds no credential it was not handed by a
 * login response, and that is a property worth keeping.
 */
interface ImportMetaEnv {
  /**
   * Optional path prefix for API calls. Defaults to `''` -- see src/config.ts, which is the
   * only module allowed to read it, documents why the default is the right answer, and
   * rejects any value that expresses an absolute origin.
   */
  readonly VITE_API_BASE_PATH?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
