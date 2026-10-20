const BACKEND_KEY = 'emedicapp.backend-url';
const SESSION_PREFIX = 'emedicapp.session:';
const memory = new Map<string, string>();
const memoryOnly = new Set<string>();

function read(storage: 'localStorage' | 'sessionStorage', key: string) {
  const cacheKey = `${storage}:${key}`;
  // A failed write can leave a readable but outdated value in browser storage.
  if (memoryOnly.has(cacheKey)) return memory.get(cacheKey) ?? '';
  try {
    return window[storage].getItem(key) ?? '';
  } catch {
    return memory.get(cacheKey) ?? '';
  }
}

function write(storage: 'localStorage' | 'sessionStorage', key: string, value: string) {
  const cacheKey = `${storage}:${key}`;
  if (value) memory.set(cacheKey, value);
  else memory.delete(cacheKey);
  try {
    if (value) window[storage].setItem(key, value);
    else window[storage].removeItem(key);
    memoryOnly.delete(cacheKey);
  } catch {
    memoryOnly.add(cacheKey);
    // A restricted browser can still keep the connection for the current page.
  }
}

export function normalizeBackendUrl(value: string): string {
  const input = value.trim();
  if (!input) return '';
  let url: URL;
  try {
    url = new URL(input);
  } catch {
    throw new Error('Ingresá una URL completa, por ejemplo https://servidor.example.com.');
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback))
    throw new Error('El servidor debe usar HTTPS. HTTP solo está disponible en esta misma PC.');
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/')
    throw new Error('Usá la dirección del servidor sin /api, rutas, credenciales ni parámetros.');
  return url.origin;
}

export function backendUrl(): string {
  const configured = configuredBackend();
  if (configured) {
    try {
      return normalizeBackendUrl(configured);
    } catch {
      return '';
    }
  }
  return canConfigureBackend() ? read('localStorage', BACKEND_KEY) : '';
}

function configuredBackend(): string {
  return (import.meta.env.VITE_API_BASE_URL ?? '').trim();
}

function frontendHostname(): string {
  return window.location.hostname || new URL(window.location.origin).hostname;
}

export function canConfigureBackend(): boolean {
  return (
    !configuredBackend() && ['localhost', '127.0.0.1', '[::1]', '::1'].includes(frontendHostname())
  );
}

export function saveBackendUrl(value: string) {
  if (!canConfigureBackend())
    throw new Error('La conexión de esta aplicación está definida por su publicación.');
  write('localStorage', BACKEND_KEY, normalizeBackendUrl(value));
}

export function initializeBackendConnection(): string {
  const params = new URLSearchParams(window.location.search);
  if (!canConfigureBackend()) {
    // Published applications choose their own server. Old links and browser
    // preferences must never redirect authentication or document requests.
    write('localStorage', BACKEND_KEY, '');
    if (params.has('api')) {
      params.delete('api');
      const query = params.toString();
      window.history.replaceState(
        null,
        '',
        `${window.location.pathname}${query ? `?${query}` : ''}${window.location.hash}`,
      );
    }
    const configured = configuredBackend();
    if (configured) {
      try {
        normalizeBackendUrl(configured);
      } catch {
        return 'La conexión de la aplicación no está disponible. Contactá al administrador.';
      }
    } else if (frontendHostname().endsWith('.github.io')) {
      return 'La demostración no está disponible en este momento. Intentá nuevamente más tarde.';
    }
    return '';
  }
  if (!params.has('api')) return '';
  try {
    const value = normalizeBackendUrl(params.get('api') ?? '');
    if (!value) throw new Error('El enlace no incluye la dirección del servidor.');
    saveBackendUrl(value);
    // Keep the public server address so copying this URL works on a fresh device.
    params.set('api', value);
    window.history.replaceState(
      null,
      '',
      `${window.location.pathname}?${params}${window.location.hash}`,
    );
    return '';
  } catch (error) {
    return error instanceof Error ? error.message : 'La dirección del servidor no es válida.';
  }
}

export function needsBackendConnection(): boolean {
  return !backendUrl() && frontendHostname().endsWith('.github.io');
}

export function apiUrl(path: string): string {
  return path.startsWith('/api/') ? `${backendUrl()}${path}` : path;
}

export function crossOriginBackend(): boolean {
  return !!backendUrl() && new URL(backendUrl()).origin !== window.location.origin;
}

function sessionKey() {
  return SESSION_PREFIX + backendUrl();
}

export function authSession(): string {
  return crossOriginBackend() ? read('sessionStorage', sessionKey()) : '';
}

export function clearAuthSession(expectedBackend = backendUrl()) {
  write('sessionStorage', SESSION_PREFIX + expectedBackend, '');
}

export function rememberAuthSession(
  response: Response,
  sentSession: string,
  expectedBackend = backendUrl(),
) {
  if (backendUrl() !== expectedBackend || !crossOriginBackend() || authSession() !== sentSession)
    return;
  const value = response.headers.get('X-Auth-Session');
  if (value !== null) write('sessionStorage', sessionKey(), value);
}
