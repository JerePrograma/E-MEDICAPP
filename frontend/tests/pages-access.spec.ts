import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve, sep } from 'node:path';

test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

test('frontend estático en otro origen: ingresa sin cookies, renueva la página y cierra la sesión', async ({
  page,
}) => {
  await page
    .context()
    .grantPermissions(['local-network-access'], { origin: 'http://localhost:4174' });
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
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
  expect(page.url()).toContain('/E-MEDICAPP/#/app/stock');
  expect(
    requests.some((request) => request.path === '/api/auth/me' && request.session),
  ).toBeTruthy();
  expect(requests.every((request) => !request.cookie)).toBeTruthy();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
