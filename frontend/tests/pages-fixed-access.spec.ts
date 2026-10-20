import { expect, test, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendOrigin = 'http://localhost:4174';
const configuredBackend = 'http://127.0.0.1:8083';
const untrustedBackend = 'https://attacker.example.com';
const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
const runtime = resolve(projectRoot, '.runtime');
const evidence = join(runtime, 'fixed-connection-qa');
let compiledFrontend: string;

test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe(configuredBackend);
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
  mkdirSync(evidence, { recursive: true });
  // Build the real fixed configuration in a new private directory, preserving dist/.
  compiledFrontend = mkdtempSync(join(runtime, 'frontend-fixed-qa-'));
  expect(compiledFrontend.startsWith(runtime + sep)).toBe(true);
  execFileSync(
    process.execPath,
    [
      resolve(projectRoot, 'frontend/node_modules/vite/bin/vite.js'),
      'build',
      '--outDir',
      compiledFrontend,
    ],
    {
      cwd: resolve(projectRoot, 'frontend'),
      env: {
        ...process.env,
        VITE_API_BASE_URL: configuredBackend,
        VITE_BASE_PATH: '/E-MEDICAPP/',
      },
      stdio: 'pipe',
      timeout: 60000,
    },
  );
});

async function prepareConfiguredFrontend(page: Page) {
  await page.context().grantPermissions(['local-network-access'], { origin: frontendOrigin });
  await page.route(`${untrustedBackend}/**`, (route) => route.abort());
  await page.route(`${frontendOrigin}/**`, async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const file =
      pathname === '/E-MEDICAPP/' ? 'index.html' : pathname.replace(/^\/E-MEDICAPP\//, '');
    const path = resolve(compiledFrontend, file);
    if (!path.startsWith(compiledFrontend + sep) || pathname.includes('/api/')) {
      await route.fulfill({ status: 404, body: 'Static frontend only' });
      return;
    }
    try {
      const contentType = file.endsWith('.html')
        ? 'text/html'
        : file.endsWith('.js')
          ? 'text/javascript'
          : file.endsWith('.css')
            ? 'text/css'
            : file.endsWith('.webp')
              ? 'image/webp'
              : 'application/octet-stream';
      await route.fulfill({ status: 200, contentType, body: readFileSync(path) });
    } catch {
      await route.fulfill({ status: 404 });
    }
  });
}

async function expectNoConnectionEditor(page: Page) {
  await expect(page.getByRole('button', { name: 'Conexión', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Cambiar servidor', exact: true })).toHaveCount(0);
  await expect(
    page.getByRole('textbox', { name: 'Dirección del servidor', exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Conectar E-MEDICAPP', exact: true })).toHaveCount(
    0,
  );
}

function observeApiRequests(page: Page) {
  const requests: { url: string; cookie: boolean }[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/'))
      requests.push({ url: request.url(), cookie: !!request.headers().cookie });
  });
  return requests;
}

test('frontend configurado móvil: ignora enlaces y ajustes ajenos y mantiene la sesión del servidor publicado', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await prepareConfiguredFrontend(page);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const requests = observeApiRequests(page);
  await page.addInitScript((server) => {
    window.localStorage.setItem('emedicapp.backend-url', server);
  }, untrustedBackend);
  await page.goto(
    `${frontendOrigin}/E-MEDICAPP/?api=${encodeURIComponent(untrustedBackend)}&source=fixed-test#/app/catalog`,
  );
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  let url = new URL(page.url());
  expect(url.searchParams.has('api')).toBe(false);
  expect(url.searchParams.get('source')).toBe('fixed-test');
  expect(url.hash).toBe('#/app/catalog');
  await expectNoConnectionEditor(page);

  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Comprador ·/ }).click();
  const signedIn = page.waitForResponse(
    (response) => response.url() === `${configuredBackend}/api/auth/login`,
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const login = await signedIn;
  expect(login.ok(), await login.text()).toBeTruthy();
  expect(login.headers()['x-auth-session']).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Catálogo', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();

  // Editing browser storage after authentication must not redirect subsequent requests.
  await page.evaluate((server) => {
    window.localStorage.setItem('emedicapp.backend-url', server);
  }, untrustedBackend);
  const reloadedSession = page.waitForResponse(
    (response) => response.url() === `${configuredBackend}/api/auth/me`,
  );
  await page.reload();
  const session = await reloadedSession;
  expect(session.ok(), await session.text()).toBeTruthy();
  expect((await session.json()).roles).toContain('BUYER');
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();
  await expectNoConnectionEditor(page);
  url = new URL(page.url());
  expect(url.searchParams.has('api')).toBe(false);
  expect(url.searchParams.get('source')).toBe('fixed-test');
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((request) => request.url.startsWith(`${configuredBackend}/api/`))).toBe(
    true,
  );
  expect(requests.every((request) => !request.cookie)).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({ path: join(evidence, 'fixed-connection-mobile.png'), fullPage: false });
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  await expectNoConnectionEditor(page);
});

test('frontend configurado: una falla del servidor permite reintentar sin habilitar cambios de conexión', async ({
  page,
}) => {
  await prepareConfiguredFrontend(page);
  const requests = observeApiRequests(page);
  let unavailable = true;
  await page.route(`${configuredBackend}/api/bootstrap`, async (route) => {
    if (unavailable)
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'QA_UNAVAILABLE',
          detail: 'Servidor de prueba temporalmente no disponible.',
        }),
      });
    else await route.continue();
  });
  await page.goto(`${frontendOrigin}/E-MEDICAPP/?api=invalid&source=retry-test#/`);
  await expect(page.getByText('No se pudo conectar con E-MEDICAPP.', { exact: true })).toBeVisible({
    timeout: 30000,
  });
  await expectNoConnectionEditor(page);
  expect(new URL(page.url()).searchParams.has('api')).toBe(false);
  unavailable = false;
  const recovered = page.waitForResponse(
    (response) => response.url() === `${configuredBackend}/api/bootstrap` && response.ok(),
  );
  await page.getByRole('button', { name: 'Volver a intentar', exact: true }).click();
  await recovered;
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  await expectNoConnectionEditor(page);
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((request) => request.url.startsWith(`${configuredBackend}/api/`))).toBe(
    true,
  );
});
