import type { RefObject } from 'react';
import type { PageNavigation } from '../lib/useCursorPage';

export function PageControls({ pages, records, focusTarget, disabled = false }: {
  pages: PageNavigation;
  records: 'resumes' | 'jobs' | 'applications';
  focusTarget: RefObject<HTMLElement | null>;
  disabled?: boolean;
}) {
  const { canGoBack, hasNext, isFetching, pageNumber } = pages;
  function go(action: () => void) {
    focusTarget.current?.focus();
    action();
  }

  return <>
    <p className="visually-hidden" role="status">{isFetching ? 'Loading ' + records + '…'
      : pageNumber > 1 || hasNext ? records + ' page ' + pageNumber : ''}</p>
    {(canGoBack || hasNext) && <nav className="btn-row" aria-label={records + ' pages'}>
      {canGoBack && <button type="button" className="button muted small" disabled={disabled || isFetching}
        onClick={() => go(pages.previous)}>Newer {records}</button>}
      {hasNext && <button type="button" className="button muted small" disabled={disabled || isFetching}
        onClick={() => go(pages.next)}>Older {records}</button>}
    </nav>}
  </>;
}
