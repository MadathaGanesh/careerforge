import { useCallback, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from './api';

export type CursorResult<T> = { items: T[]; nextCursor: string | null };
export type PageNavigation = {
  canGoBack: boolean;
  hasNext: boolean;
  isFetching: boolean;
  pageNumber: number;
  previous: () => void;
  next: () => void;
};

export function useCursorPage<T>(key: string, path: string, enabled = true) {
  const [cursors, setCursors] = useState<Array<string | null>>([null]);
  const cursor = cursors.at(-1);
  const query = useQuery({
    queryKey: [key, 'page', path, cursor],
    queryFn: ({ signal }) => api<CursorResult<T>>(path + (cursor ? (path.includes('?') ? '&' : '?') + 'cursor=' + encodeURIComponent(cursor) : ''), { signal }),
    enabled,
  });
  const previous = useCallback(() => {
    setCursors(value => value.length > 1 ? value.slice(0, -1) : value);
  }, []);
  return {
    ...query,
    items: query.data?.items ?? [],
    canGoBack: cursors.length > 1,
    pageNumber: cursors.length,
    hasNext: !!query.data?.nextCursor,
    reset: () => setCursors([null]),
    previous,
    next: () => {
      const next = query.data?.nextCursor;
      if (next) setCursors(value => [...value, next]);
    },
  };
}
