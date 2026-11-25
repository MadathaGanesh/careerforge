import { describe, expect, it } from 'vitest';
import { apiPath } from './api';

describe('download API paths', () => {
  it('preserves an already prefixed Jake download URL', () => {
    expect(apiPath('/api/v1/jake-exports/id/download')).toBe('/api/v1/jake-exports/id/download');
  });

  it('prefixes paths used by export history', () => {
    expect(apiPath('/exports/id/download')).toBe('/api/v1/exports/id/download');
  });

  it('rejects absolute hosts and network paths', () => {
    expect(() => apiPath('https://example.test/api/v1/export')).toThrow();
    expect(() => apiPath('//example.test/export')).toThrow();
  });
});
