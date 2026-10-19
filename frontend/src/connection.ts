const BACKEND_KEY = 'emedicapp.backend-url';
const SESSION_PREFIX = 'emedicapp.session:';
const memory = new Map<string, string>();

function read(storage: 'localStorage' | 'sessionStorage', key: string) {
  try {
    return window[storage].getItem(key) ?? '';
  } catch {
    return memory.get(key) ?? '';
  }
}

function write(storage: 'localStorage' | 'sessionStorage', key: string, value: string) {
  if (value) memory.set(key, value);
  else memory.delete(key);
  try {
    if (value) window[storage].setItem(key, value);
    else window[storage].removeItem(key);
  } catch {
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
  return (
    read('localStorage', BACKEND_KEY) ||
    (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
  );
}

export function saveBackendUrl(value: string) {
  write('localStorage', BACKEND_KEY, normalizeBackendUrl(value));
}

export function initializeBackendConnection(): string {
  const params = new URLSearchParams(window.location.search);
  if (!params.has('api')) return '';
  try {
    const value = normalizeBackendUrl(params.get('api') ?? '');
    if (!value) throw new Error('El enlace no incluye la dirección del servidor.');
    saveBackendUrl(value);
    params.delete('api');
    const search = params.size ? `?${params}` : '';
    window.history.replaceState(
      null,
      '',
      `${window.location.pathname}${search}${window.location.hash}`,
    );
    return '';
  } catch (error) {
    return error instanceof Error ? error.message : 'La dirección del servidor no es válida.';
  }
}

export function needsBackendConnection(): boolean {
  return !backendUrl() && window.location.hostname.endsWith('.github.io');
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
