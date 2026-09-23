import { useStock } from './useStock';
import styles from './catalog.module.css';

/** Icon-free but never colour-alone: every state is also a sentence. */
export function StockLine({ productId, wanted = 1 }: { productId: string; wanted?: number }) {
  const { data, isPending, isError } = useStock(productId);
  if (isPending) return <span>Checking stock...</span>;
  if (isError) return <span>Stock unknown right now</span>;
  const available = data.totalAvailable;
  if (available <= 0) return <span className={styles.stockOut}>Out of stock</span>;
  if (available < wanted) return <span className={styles.stockLow}>Only {available} left</span>;
  if (available <= 5) return <span className={styles.stockLow}>{available} left in stock</span>;
  return <span className={styles.stockOk}>In stock</span>;
}
