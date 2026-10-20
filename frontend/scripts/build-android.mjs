import { build } from 'vite';

const configured = process.env.VITE_API_BASE_URL?.trim();
if (!configured)
  throw new Error('Definí VITE_API_BASE_URL con el servidor HTTPS antes de compilar Android.');
const backend = new URL(configured);
if (
  backend.protocol !== 'https:' ||
  backend.username ||
  backend.password ||
  backend.search ||
  backend.hash ||
  backend.pathname !== '/' ||
  ['localhost', '127.0.0.1', '[::1]'].includes(backend.hostname)
) {
  throw new Error(
    'Android requiere el origen HTTPS del backend accesible desde el teléfono, sin rutas ni credenciales.',
  );
}
process.env.VITE_API_BASE_URL = backend.origin;
process.env.VITE_NATIVE_BUILD = 'true';
await build({ mode: 'android', base: '/', build: { outDir: 'dist-android' } });
