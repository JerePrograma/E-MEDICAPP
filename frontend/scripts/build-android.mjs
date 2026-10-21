import { build } from 'vite';

const configured = process.env.VITE_API_BASE_URL?.trim();
if (!configured)
  throw new Error('Definí VITE_API_BASE_URL con el servidor HTTPS antes de compilar Android.');
const backend = new URL(configured);
const basePath = backend.pathname.replace(/\/$/, '');
const validBasePath = /^\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+$/.test(basePath) &&
  !basePath.endsWith('/api');
if (
  backend.protocol !== 'https:' ||
  backend.username ||
  backend.password ||
  backend.search ||
  backend.hash ||
  (backend.pathname !== '/' && !validBasePath) ||
  ['localhost', '127.0.0.1', '[::1]'].includes(backend.hostname)
) {
  throw new Error(
    'Android requiere la URL HTTPS del backend o proxy accesible desde el teléfono, sin /api, parámetros ni credenciales.',
  );
}
process.env.VITE_API_BASE_URL = backend.origin + (validBasePath ? basePath : '');
process.env.VITE_NATIVE_BUILD = 'true';
await build({ mode: 'android', base: '/', build: { outDir: 'dist-android' } });
