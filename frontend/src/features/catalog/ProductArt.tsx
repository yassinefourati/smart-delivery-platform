import styles from './catalog.module.css';

/**
 * A calm lettered tile in a fixed aspect-ratio box: the product's initials on a white disc,
 * on the same pale green-grey for every product, so the grid reads as one catalog rather
 * than a wall of colours.
 *
 * NO <img> FROM `imageUrl`, on purpose: the field is an arbitrary absolute URL an admin typed
 * (null on every seeded product, no upload endpoint, no validation). The CSP is
 * `img-src 'self' data:`, so it would fail closed anyway, and rendering third-party URLs would
 * leak our page URLs to whatever host was pasted. The tile means the common case is the good
 * case and a missing image never reflows the grid.
 */
export function ProductArt({ name }: { sku: string; name: string }) {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
  return (
    <div className={styles.art} aria-hidden="true">
      <span className={styles.artMark}>{letters || '?'}</span>
    </div>
  );
}
