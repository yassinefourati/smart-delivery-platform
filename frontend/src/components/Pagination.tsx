import ui from './ui.module.css';

/** Zero-based pages, matching Spring Data's `page` parameter; one-based in the text a person reads. */
export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Pagination" className={ui.row}>
      <button
        type="button"
        className={ui.button}
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
      >
        Previous
      </button>
      <span aria-current="page">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        className={ui.button}
        disabled={page >= totalPages - 1}
        onClick={() => onPage(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
