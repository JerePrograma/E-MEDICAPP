import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  apiUrl,
  authSession,
  clearAuthSession,
  initializeBackendConnection,
  normalizeBackendUrl,
  rememberAuthSession,
  saveBackendUrl,
} from './connection';

function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  };
}

beforeEach(() =>
  vi.stubGlobal('window', {
    location: {
      origin: 'https://jereprograma.github.io',
      hostname: 'jereprograma.github.io',
      pathname: '/E-MEDICAPP/',
      search: '',
      hash: '#/app',
    },
    localStorage: storage(),
    sessionStorage: storage(),
    history: { replaceState: vi.fn() },
  }),
);
afterEach(() => vi.unstubAllGlobals());

describe('Conexión entre Pages y el servidor de la PC', () => {
  it('acepta HTTPS y loopback; rechaza URLs con credenciales, rutas o protocolos inseguros', () => {
    expect(normalizeBackendUrl(' https://demo.example.com/ ')).toBe('https://demo.example.com');
    expect(normalizeBackendUrl('http://127.0.0.1:8082')).toBe('http://127.0.0.1:8082');
    for (const url of [
      'http://demo.example.com',
      'https://user:pass@demo.example.com',
      'https://demo.example.com/api',
      'javascript:alert(1)',
      'https://demo.example.com/?token=secret',
    ])
      expect(() => normalizeBackendUrl(url)).toThrow();
  });
  it('el enlace del presentador configura el servidor y conserva la ruta de Pages', () => {
    window.location.search = '?api=https%3A%2F%2Fdemo.example.com&source=demo';
    expect(initializeBackendConnection()).toBe('');
    expect(apiUrl('/api/auth/me')).toBe('https://demo.example.com/api/auth/me');
    expect(window.history.replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/E-MEDICAPP/?source=demo#/app',
    );
  });
  it('las sesiones son propias del servidor y una respuesta anterior no revierte una rotación', () => {
    saveBackendUrl('https://first.example.com');
    rememberAuthSession(new Response(null, { headers: { 'X-Auth-Session': 'anonymous' } }), '');
    rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'signed-in' } }),
      'anonymous',
    );
    rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'anonymous' } }),
      'anonymous',
    );
    expect(authSession()).toBe('signed-in');
    saveBackendUrl('https://second.example.com');
    rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'first-session-arrived-late' } }),
      '',
      'https://first.example.com',
    );
    expect(authSession()).toBe('');
    saveBackendUrl('https://first.example.com');
    clearAuthSession();
    expect(authSession()).toBe('');
    expect(window.localStorage.getItem('emedicapp.session:https://first.example.com')).toBeNull();
  });
});
