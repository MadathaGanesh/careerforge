import { useRef } from 'react';
import { ApiFailure, api } from './api';

export function useIdempotentPost() {
  const keys = useRef(new Map<string, string>());

  return async function submit<T>(path: string, input: object): Promise<T> {
    const body = JSON.stringify(input);
    const signature = path + body;
    const key = keys.current.get(signature) ?? crypto.randomUUID();
    keys.current.set(signature, key);
    try {
      const result = await api<T>(path, { method: 'POST', body, headers: { 'Idempotency-Key': key } });
      keys.current.delete(signature);
      return result;
    } catch (error) {
      if (error instanceof ApiFailure && error.status < 500) keys.current.delete(signature);
      throw error;
    }
  };
}
