import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let connection: typeof import('./connection');

function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  };
}

beforeEach(async () => {
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
  });
  vi.resetModules();
  connection = await import('./connection');
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Conexión entre Pages y el servidor de la PC', () => {
  it('acepta HTTPS y loopback; rechaza URLs con credenciales, rutas o protocolos inseguros', () => {
    expect(connection.normalizeBackendUrl(' https://demo.example.com/ ')).toBe(
      'https://demo.example.com',
    );
    expect(connection.normalizeBackendUrl('http://127.0.0.1:8082')).toBe('http://127.0.0.1:8082');
    for (const url of [
      'http://demo.example.com',
      'https://user:pass@demo.example.com',
      'https://demo.example.com/api',
      'javascript:alert(1)',
      'https://demo.example.com/?token=secret',
    ])
      expect(() => connection.normalizeBackendUrl(url)).toThrow();
  });
  it('conserva el servidor público normalizado para compartir el enlace con otro dispositivo', () => {
    window.location.search = '?api=https%3A%2F%2Fdemo.example.com%2F&source=demo';
    expect(connection.initializeBackendConnection()).toBe('');
    expect(connection.apiUrl('/api/auth/me')).toBe('https://demo.example.com/api/auth/me');
    expect(window.history.replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/E-MEDICAPP/?api=https%3A%2F%2Fdemo.example.com&source=demo#/app',
    );
  });
  it('las sesiones son propias del servidor y una respuesta anterior no revierte una rotación', () => {
    connection.saveBackendUrl('https://first.example.com');
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'anonymous' } }),
      '',
    );
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'signed-in' } }),
      'anonymous',
    );
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'anonymous' } }),
      'anonymous',
    );
    expect(connection.authSession()).toBe('signed-in');
    connection.saveBackendUrl('https://second.example.com');
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'first-session-arrived-late' } }),
      '',
      'https://first.example.com',
    );
    expect(connection.authSession()).toBe('');
    connection.saveBackendUrl('https://first.example.com');
    connection.clearAuthSession();
    expect(connection.authSession()).toBe('');
    expect(window.localStorage.getItem('emedicapp.session:https://first.example.com')).toBeNull();
  });
  it('conserva el servidor en memoria cuando el navegador permite leer pero bloquea guardar', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('Storage writes denied');
    });
    window.location.search = '?api=https%3A%2F%2Fdemo.example.com';
    expect(connection.initializeBackendConnection()).toBe('');
    expect(window.localStorage.getItem('emedicapp.backend-url')).toBeNull();
    expect(connection.backendUrl()).toBe('https://demo.example.com');
    expect(connection.needsBackendConnection()).toBe(false);
    expect(connection.apiUrl('/api/bootstrap')).toBe('https://demo.example.com/api/bootstrap');
  });
  it('rota y elimina sesiones en memoria aunque la escritura y el borrado estén bloqueados', () => {
    connection.saveBackendUrl('https://demo.example.com');
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'anonymous' } }),
      '',
    );
    vi.spyOn(window.sessionStorage, 'setItem').mockImplementation(() => {
      throw new Error('Storage writes denied');
    });
    vi.spyOn(window.sessionStorage, 'removeItem').mockImplementation(() => {
      throw new Error('Storage removal denied');
    });
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'signed-in' } }),
      'anonymous',
    );
    expect(window.sessionStorage.getItem('emedicapp.session:https://demo.example.com')).toBe(
      'anonymous',
    );
    expect(connection.authSession()).toBe('signed-in');
    connection.clearAuthSession();
    expect(connection.authSession()).toBe('');
  });
  it('no recupera valores antiguos después de que se borra el almacenamiento normal', () => {
    connection.saveBackendUrl('https://demo.example.com');
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'signed-in' } }),
      '',
    );
    window.sessionStorage.removeItem('emedicapp.session:https://demo.example.com');
    expect(connection.authSession()).toBe('');
    window.localStorage.removeItem('emedicapp.backend-url');
    expect(connection.backendUrl()).toBe('');
  });
  it('vuelve a usar el almacenamiento normal después de recuperarse de un fallo de escritura', () => {
    const denied = vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('Storage writes denied');
    });
    connection.saveBackendUrl('https://first.example.com');
    expect(connection.backendUrl()).toBe('https://first.example.com');
    denied.mockRestore();
    connection.saveBackendUrl('https://second.example.com');
    expect(connection.backendUrl()).toBe('https://second.example.com');
    window.localStorage.removeItem('emedicapp.backend-url');
    expect(connection.backendUrl()).toBe('');
  });
});
