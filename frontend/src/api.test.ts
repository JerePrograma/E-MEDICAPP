import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiFetch, money } from './api';
import { authSession, saveBackendUrl } from './connection';

beforeEach(() => {
  const storage = () => {
    const values = new Map<string, string>();
    return {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    };
  };
  vi.stubGlobal('window', {
    location: { origin: 'http://127.0.0.1:8082' },
    localStorage: storage(),
    sessionStorage: storage(),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});
describe('Operaciones verificables desde el navegador', () => {
  it('el proxy del mismo origen usa cabecera de sesión y CSRF, sin cookies del sitio', async () => {
    Object.assign(window.location, { origin: 'https://scalaris.com.ar' });
    vi.stubEnv('VITE_API_BASE_URL', 'https://scalaris.com.ar/e-medicapp');
    const fetcher = vi.fn(async (url: string, request?: RequestInit) => {
      expect(url.startsWith('https://scalaris.com.ar/e-medicapp/api/')).toBe(true);
      expect(request?.credentials).toBe('omit');
      const headers = new Headers(request?.headers);
      if (url.endsWith('/csrf'))
        return Response.json(
          { token: 'csrf-proxy', headerName: 'X-CSRF-TOKEN' },
          { headers: { 'X-Auth-Session': 'proxy-anonymous' } },
        );
      expect(headers.get('X-Auth-Session')).toBe('proxy-anonymous');
      expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-proxy');
      return Response.json({ ok: true }, { headers: { 'X-Auth-Session': 'proxy-signed-in' } });
    });
    vi.stubGlobal('fetch', fetcher);
    await expect(
      api('/api/auth/login', { method: 'POST', body: { email: 'demo' } }),
    ).resolves.toEqual({ ok: true });
    expect(authSession()).toBe('proxy-signed-in');
  });
  it('conserva centavos y moneda de importes grandes sin convertir a punto flotante', () => {
    expect(money('99999999999999.99', 'USD')).toBe('USD 99.999.999.999.999,99');
  });
  it('un reintento tras corte de conexión conserva identidad y renueva CSRF', async () => {
    const operations: { headers: Headers }[] = [];
    let csrfCalls = 0;
    let operationCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, request?: RequestInit) => {
        if (path === '/api/auth/csrf')
          return Response.json({ token: `csrf-${++csrfCalls}`, headerName: 'X-CSRF-TOKEN' });
        operations.push({ headers: new Headers(request?.headers) });
        if (++operationCalls === 1) throw new TypeError('Corte de conexión');
        return Response.json({ saved: true });
      }),
    );
    const options = { method: 'POST', body: { reference: 'operación-única' }, idempotent: true };
    await expect(api('/api/test/retry', options)).rejects.toThrow(
      'No se pudo conectar con el servidor',
    );
    await expect(api('/api/test/retry', options)).resolves.toEqual({ saved: true });
    expect(operations[0]?.headers.get('Idempotency-Key')).toBe(
      operations[1]?.headers.get('Idempotency-Key'),
    );
    expect(operations[0]?.headers.get('X-CSRF-TOKEN')).not.toBe(
      operations[1]?.headers.get('X-CSRF-TOKEN'),
    );
  });
  it('Pages ingresa y consulta documentos sin cookies de terceros y rota la sesión con CSRF', async () => {
    saveBackendUrl('https://demo.example.com');
    const fetcher = vi.fn(async (url: string, request?: RequestInit) => {
      const headers = new Headers(request?.headers);
      expect(request?.credentials).toBe('omit');
      if (url.endsWith('/csrf')) {
        expect(headers.get('X-Auth-Session')).toBeNull();
        return Response.json(
          { token: 'csrf-1', headerName: 'X-CSRF-TOKEN' },
          { headers: { 'X-Auth-Session': 'anonymous-session' } },
        );
      }
      if (url.endsWith('/login')) {
        expect(headers.get('X-Auth-Session')).toBe('anonymous-session');
        expect(headers.get('X-CSRF-TOKEN')).toBe('csrf-1');
        return Response.json(
          { id: 'buyer' },
          { headers: { 'X-Auth-Session': 'authenticated-session' } },
        );
      }
      expect(url).toBe('https://demo.example.com/api/documents/evidence');
      expect(headers.get('X-Auth-Session')).toBe('authenticated-session');
      return new Response('private PDF', { headers: { 'Content-Type': 'application/pdf' } });
    });
    vi.stubGlobal('fetch', fetcher);
    await api('/api/auth/login', {
      method: 'POST',
      body: { email: 'buyer@example.com', password: 'secret' },
      form: true,
    });
    expect(authSession()).toBe('authenticated-session');
    expect(await (await apiFetch('/api/documents/evidence')).text()).toBe('private PDF');
  });
  it('conserva las cookies para el servidor local que también sirve el frontend', async () => {
    const fetcher = vi.fn(async (_url: string, request?: RequestInit) => {
      expect(request?.credentials).toBe('same-origin');
      expect(new Headers(request?.headers).has('X-Auth-Session')).toBe(false);
      return Response.json({ mode: 'DEMO' });
    });
    vi.stubGlobal('fetch', fetcher);
    await api('/api/bootstrap');
  });
  it('nunca reenvía la sesión a una URL externa recibida como recurso', async () => {
    saveBackendUrl('https://demo.example.com');
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    await expect(apiFetch('https://another.example.com/document')).rejects.toThrow(
      'La dirección del recurso no es válida.',
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
});
