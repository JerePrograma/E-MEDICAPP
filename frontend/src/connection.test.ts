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
  vi.stubEnv('VITE_API_BASE_URL', '');
  vi.stubEnv('VITE_NATIVE_BUILD', '');
  vi.stubGlobal('window', {
    location: {
      origin: 'http://localhost:4174',
      hostname: 'localhost',
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
  vi.unstubAllEnvs();
});

describe('Conexión entre Pages y el servidor de la PC', () => {
  it('una compilación Android sin servidor fijo queda cerrada y no acepta un servidor guardado', () => {
    vi.stubEnv('VITE_NATIVE_BUILD', 'true');
    window.localStorage.setItem('emedicapp.backend-url', 'https://ajeno.example');
    window.location.search = '?api=https%3A%2F%2Fajeno.example';
    expect(connection.canConfigureBackend()).toBe(false);
    expect(connection.initializeBackendConnection()).not.toBe('');
    expect(connection.backendUrl()).toBe('');
    expect(connection.needsBackendConnection()).toBe(true);
    expect(() => connection.saveBackendUrl('https://ajeno.example')).toThrow();
  });
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
  it('permite configurar un frontend local sin servidor fijado y conserva su enlace de prueba', () => {
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

describe('Conexión fija de la aplicación publicada', () => {
  it('usa el proxy del dominio público y conserva sesiones aisladas aunque comparta origen', () => {
    Object.assign(window.location, {
      origin: 'https://scalaris.com.ar',
      hostname: 'scalaris.com.ar',
      pathname: '/e-medicapp/',
    });
    vi.stubEnv('VITE_API_BASE_URL', 'https://scalaris.com.ar/e-medicapp/');
    expect(connection.initializeBackendConnection()).toBe('');
    expect(connection.backendUrl()).toBe('https://scalaris.com.ar/e-medicapp');
    expect(connection.apiUrl('/api/auth/login')).toBe(
      'https://scalaris.com.ar/e-medicapp/api/auth/login',
    );
    expect(connection.crossOriginBackend()).toBe(false);
    expect(connection.usesHeaderSessions()).toBe(true);
    connection.rememberAuthSession(
      new Response(null, { headers: { 'X-Auth-Session': 'proxy-session' } }),
      '',
    );
    expect(connection.authSession()).toBe('proxy-session');
    connection.clearAuthSession();
    expect(connection.authSession()).toBe('');
    expect(() => connection.saveBackendUrl('https://other.example.com')).toThrow();
    expect(() => connection.normalizeBackendUrl('https://scalaris.com.ar/e-medicapp')).toThrow();
  });

  function pages() {
    Object.assign(window.location, {
      origin: 'https://jereprograma.github.io',
      hostname: 'jereprograma.github.io',
    });
  }

  it('prioriza el servidor publicado e ignora enlaces y preferencias anteriores', () => {
    pages();
    vi.stubEnv('VITE_API_BASE_URL', ' https://trusted.example.com/ ');
    window.localStorage.setItem('emedicapp.backend-url', 'https://untrusted.example.com');
    window.location.search = '?api=https%3A%2F%2Funtrusted.example.com&source=mobile';
    expect(connection.canConfigureBackend()).toBe(false);
    expect(connection.backendUrl()).toBe('https://trusted.example.com');
    expect(connection.apiUrl('/api/auth/login')).toBe('https://trusted.example.com/api/auth/login');
    expect(connection.initializeBackendConnection()).toBe('');
    expect(window.history.replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/E-MEDICAPP/?source=mobile#/app',
    );
    expect(window.localStorage.getItem('emedicapp.backend-url')).toBeNull();
    window.localStorage.setItem('emedicapp.backend-url', 'https://another.example.com');
    expect(connection.backendUrl()).toBe('https://trusted.example.com');
    expect(() => connection.saveBackendUrl('https://another.example.com')).toThrow(
      'definida por su publicación',
    );
  });

  it('no consulta preferencias guardadas para resolver el servidor publicado', () => {
    pages();
    vi.stubEnv('VITE_API_BASE_URL', 'https://trusted.example.com');
    const read = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('Storage reads denied');
    });
    vi.spyOn(window.localStorage, 'removeItem').mockImplementation(() => {
      throw new Error('Storage removal denied');
    });
    window.location.search = '?api=invalid';
    expect(connection.backendUrl()).toBe('https://trusted.example.com');
    expect(connection.initializeBackendConnection()).toBe('');
    expect(connection.backendUrl()).toBe('https://trusted.example.com');
    expect(read).not.toHaveBeenCalled();
    expect(window.history.replaceState).toHaveBeenCalledWith(null, '', '/E-MEDICAPP/#/app');
  });

  it('mantiene el servidor fijado incluso al abrir la aplicación desde localhost', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://trusted.example.com');
    expect(connection.canConfigureBackend()).toBe(false);
    expect(() => connection.saveBackendUrl('https://another.example.com')).toThrow();
    expect(connection.backendUrl()).toBe('https://trusted.example.com');
  });

  it('rechaza una publicación de Pages sin servidor configurado aunque el enlace incluya uno', () => {
    pages();
    window.location.search = '?api=https%3A%2F%2Funtrusted.example.com';
    window.localStorage.setItem('emedicapp.backend-url', 'https://untrusted.example.com');
    expect(connection.canConfigureBackend()).toBe(false);
    expect(connection.backendUrl()).toBe('');
    expect(connection.initializeBackendConnection()).toContain('no está disponible');
    expect(connection.needsBackendConnection()).toBe(true);
    expect(connection.backendUrl()).toBe('');
    expect(() => connection.saveBackendUrl('https://untrusted.example.com')).toThrow();
  });

  it('rechaza una configuración de publicación inválida sin usar enlaces ni valores guardados', () => {
    pages();
    vi.stubEnv('VITE_API_BASE_URL', 'https://trusted.example.com/api');
    window.location.search = '?api=https%3A%2F%2Funtrusted.example.com';
    window.localStorage.setItem('emedicapp.backend-url', 'https://untrusted.example.com');
    expect(connection.initializeBackendConnection()).toContain('no está disponible');
    expect(connection.backendUrl()).toBe('');
    expect(connection.canConfigureBackend()).toBe(false);
  });

  it('usa el servidor del mismo origen en una instalación pública del JAR', () => {
    Object.assign(window.location, {
      origin: 'https://medicapp.example.com',
      hostname: 'medicapp.example.com',
    });
    window.location.search = '?source=mobile&api=https%3A%2F%2Funtrusted.example.com';
    window.localStorage.setItem('emedicapp.backend-url', 'https://untrusted.example.com');
    expect(connection.canConfigureBackend()).toBe(false);
    expect(connection.backendUrl()).toBe('');
    expect(connection.initializeBackendConnection()).toBe('');
    expect(connection.apiUrl('/api/auth/login')).toBe('/api/auth/login');
    expect(connection.needsBackendConnection()).toBe(false);
    expect(window.history.replaceState).toHaveBeenCalledWith(
      null,
      '',
      '/E-MEDICAPP/?source=mobile#/app',
    );
  });

  it('permite configuración manual únicamente en orígenes de loopback sin servidor publicado', () => {
    for (const hostname of ['localhost', '127.0.0.1', '[::1]']) {
      window.location.hostname = hostname;
      expect(connection.canConfigureBackend()).toBe(true);
    }
    for (const hostname of ['jereprograma.github.io', 'medicapp.example.com', '192.168.1.10']) {
      window.location.hostname = hostname;
      expect(connection.canConfigureBackend()).toBe(false);
    }
  });
});
