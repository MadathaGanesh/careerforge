import { useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { api } from './api';
import type { Session } from './api-types';

export const sessionQuery = {
  queryKey: ['session'],
  queryFn: () => api<Session>('/auth/session'),
  retry: false as const,
};

export function internalDestination(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\s]/.test(value)) {
    return '/dashboard';
  }
  return value;
}

export function useCompleteSignIn() {
  const client = useQueryClient();
  const location = useLocation();
  const navigate = useNavigate();

  return async () => {
    // The guard may have cached an anonymous session before sign-in.
    await client.cancelQueries({ queryKey: sessionQuery.queryKey });
    const session = await client.fetchQuery({ ...sessionQuery, staleTime: 0 });
    if (!session.authenticated) throw new Error('Could not establish your session. Please try signing in again.');
    navigate(internalDestination(location.state?.from), { replace: true });
  };
}
