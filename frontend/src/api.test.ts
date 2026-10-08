import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, money } from './api';

afterEach(() => vi.unstubAllGlobals());
describe('Operaciones verificables desde el navegador', () => {
  it('conserva centavos y moneda de importes grandes sin convertir a punto flotante', () => {
    expect(money('99999999999999.99', 'USD')).toBe('USD 99.999.999.999.999,99');
  });
  it('un reintento tras corte de conexión conserva identidad y renueva CSRF', async () => {
    const operations: { headers: Record<string, string> }[] = [];
    let csrfCalls = 0;
    let operationCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, request?: RequestInit) => {
        if (path === '/api/auth/csrf')
          return Response.json({ token: `csrf-${++csrfCalls}`, headerName: 'X-CSRF-TOKEN' });
        operations.push({ headers: request?.headers as Record<string, string> });
        if (++operationCalls === 1) throw new TypeError('Corte de conexión');
        return Response.json({ saved: true });
      }),
    );
    const options = { method: 'POST', body: { reference: 'operación-única' }, idempotent: true };
    await expect(api('/api/test/retry', options)).rejects.toThrow('Corte de conexión');
    await expect(api('/api/test/retry', options)).resolves.toEqual({ saved: true });
    expect(operations[0]?.headers['Idempotency-Key']).toBe(
      operations[1]?.headers['Idempotency-Key'],
    );
    expect(operations[0]?.headers['X-CSRF-TOKEN']).not.toBe(operations[1]?.headers['X-CSRF-TOKEN']);
  });
});
