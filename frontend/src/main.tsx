import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App';
import './styles/global.css';

/**
 * The entry point, and deliberately the least interesting file in the project: it finds the
 * mount node, fails loudly if it is missing, and renders <App />. Everything with a decision
 * in it -- providers, routing, guards -- is in App.tsx and src/routes.tsx, where it can be
 * rendered in a test. This file cannot be tested, so nothing lives here that would need to
 * be.
 */
const container = document.getElementById('root');

if (!container) {
  /*
   * A thrown error here means index.html and this file disagree about the mount id. The
   * alternative -- `document.getElementById('root')!` -- turns that into a silent blank page
   * with a null-reference error in the console, which is a genuinely confusing five minutes
   * for whoever meets it. `noUncheckedIndexedAccess` and friends exist to make this class of
   * thing explicit; so does this check.
   */
  throw new Error('Mount node #root is missing from index.html -- nothing can be rendered.');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
