import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve, sep } from 'node:path';

test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

async function prepareStaticFrontend(page: Page) {
  await page
    .context()
    .grantPermissions(['local-network-access'], { origin: 'http://localhost:4174' });
  const dist = resolve('dist');
  // Serve only the compiled static files, with no proxy or API on this frontend origin.
  await page.route('http://localhost:4174/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const file =
      pathname === '/E-MEDICAPP/' ? 'index.html' : pathname.replace(/^\/(?:E-MEDICAPP\/)?/, '');
    const path = resolve(dist, file);
    if (!path.startsWith(dist + sep) || pathname.includes('/api/')) {
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
            : 'application/octet-stream';
      await route.fulfill({ status: 200, contentType, body: readFileSync(path) });
    } catch {
      await route.fulfill({ status: 404 });
    }
  });
}

test('frontend estático en otro origen: ingresa sin cookies, renueva la página y cierra la sesión', async ({
  page,
}) => {
  await prepareStaticFrontend(page);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const requests: { path: string; cookie?: string; session?: string }[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith('http://127.0.0.1:8083/api/'))
      requests.push({
        path: new URL(request.url()).pathname,
        cookie: request.headers().cookie,
        session: request.headers()['x-auth-session'],
      });
  });
  await page.goto('http://localhost:4174/E-MEDICAPP/?api=http%3A%2F%2F127.0.0.1%3A8083#/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Farmacia ·/ }).click();
  const signedIn = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const login = await signedIn;
  expect(login.ok(), await login.text()).toBeTruthy();
  expect(login.headers()['x-auth-session']).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  await page.getByRole('link', { name: /Stock y precios/ }).click();
  await expect(page.getByRole('heading', { name: 'Stock y precios' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Stock y precios' })).toBeVisible();
  const shareable = new URL(page.url());
  expect(shareable.pathname).toBe('/E-MEDICAPP/');
  expect(shareable.searchParams.get('api')).toBe('http://127.0.0.1:8083');
  expect(shareable.hash).toBe('#/app/stock');
  expect(
    requests.some((request) => request.path === '/api/auth/me' && request.session),
  ).toBeTruthy();
  expect(requests.every((request) => !request.cookie)).toBeTruthy();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  // A copied working link must configure another browser without saved connection data.
  await page.evaluate(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });
  await page.goto('about:blank');
  await page.goto(shareable.toString());
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.localStorage.getItem('emedicapp.backend-url'))).toBe(
    'http://127.0.0.1:8083',
  );
  expect(errors).toEqual([]);
});

test('frontend estático móvil: conecta y mantiene el ingreso aunque no pueda guardar localStorage', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await prepareStaticFrontend(page);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (this: Storage, key: string, value: string) {
      if (this === window.localStorage)
        throw new DOMException('Storage writes denied', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  await page.goto('http://localhost:4174/E-MEDICAPP/?api=invalid&source=mobile-test#/');
  await expect(
    page.getByRole('heading', { name: 'Conectar E-MEDICAPP', exact: true }),
  ).toBeVisible();
  await page
    .getByRole('textbox', { name: 'Dirección del servidor', exact: true })
    .fill('http://127.0.0.1:8083/');
  const connected = page.waitForResponse((response) => response.url().endsWith('/api/bootstrap'));
  await page.getByRole('button', { name: 'Conectar', exact: true }).click();
  expect((await connected).ok()).toBeTruthy();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  let currentUrl = new URL(page.url());
  expect(currentUrl.searchParams.get('api')).toBe('http://127.0.0.1:8083');
  expect(currentUrl.searchParams.get('source')).toBe('mobile-test');
  expect(currentUrl.hash).toBe('#/');
  expect(
    await page.evaluate(() => window.localStorage.getItem('emedicapp.backend-url')),
  ).toBeNull();

  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Comprador ·/ }).click();
  const signedIn = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const login = await signedIn;
  expect(login.ok(), await login.text()).toBeTruthy();
  expect(login.headers()['x-auth-session']).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  const session = page.waitForResponse((response) => response.url().endsWith('/api/auth/me'));
  await page.reload();
  const authenticated = await session;
  expect(authenticated.ok(), await authenticated.text()).toBeTruthy();
  expect((await authenticated.json()).roles).toContain('BUYER');
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  currentUrl = new URL(page.url());
  expect(currentUrl.searchParams.get('api')).toBe('http://127.0.0.1:8083');
  expect(currentUrl.searchParams.get('source')).toBe('mobile-test');
  expect(
    await page.evaluate(() => window.localStorage.getItem('emedicapp.backend-url')),
  ).toBeNull();
  expect(errors).toEqual([]);
});
