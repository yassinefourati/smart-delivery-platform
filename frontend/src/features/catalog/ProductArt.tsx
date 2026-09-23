import styles from './catalog.module.css';

/**
 * A deterministic lettered tile keyed off the SKU, in a fixed aspect-ratio box.
 *
 * NO <img> FROM `imageUrl`, on purpose: the field is an arbitrary absolute URL an admin typed
 * (null on every seeded product, no upload endpoint, no validation). The CSP is
 * `img-src 'self' data:`, so it would fail closed anyway, and rendering third-party URLs would
 * leak our page URLs to whatever host was pasted. The tile means the common case is the good
 * case and a missing image never reflows the grid.
 */
export function ProductArt({ sku, name }: { sku: string; name: string }) {
  let hash = 0;
  for (const ch of sku) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  const hue = hash % 360;
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
  return (
    <div className={styles.art} style={{ background: `hsl(${hue} 45% 42%)` }} aria-hidden="true">
      {letters || '?'}
    </div>
  );
}
