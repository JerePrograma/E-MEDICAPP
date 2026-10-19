import { expect, test } from '@playwright/test';

// These regressions use the same isolated database as the workflow suite.
test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

test('acceso conserva correo y contraseña al volver de otra ventana y permite corregir un ingreso', async ({
  page,
}) => {
  await page.clock.install();
  await page.goto('/');
  await page.getByRole('button', { name: 'Ingresar con otra cuenta', exact: true }).click();
  const email = page.getByRole('textbox', { name: 'Correo electrónico' });
  const password = page.getByLabel(/^Contraseña/);
  await email.fill('comprador@demo.invalid');
  await password.fill('Clave equivocada');

  // Advance beyond the session query stale time, then reproduce window return.
  await page.clock.fastForward(35_000);
  const refreshedSession = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/me'),
  );
  await page.evaluate(() => {
    window.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
  });
  expect((await refreshedSession).status()).toBe(401);
  await expect(email).toHaveValue('comprador@demo.invalid');
  await expect(password).toHaveValue('Clave equivocada');

  const rejected = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  expect((await rejected).status()).toBe(401);
  await expect(
    page.getByText('No se pudo ingresar con esos datos.', { exact: true }),
  ).toBeVisible();
  await expect(email).toHaveValue('comprador@demo.invalid');
  await password.fill('DemoMedicapp2026!');
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Nueva compra', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Nueva compra', exact: true })).toBeVisible();
});

test('registro conserva datos al cambiar de ventana y recuperación permite volver a ingresar', async ({
  page,
}) => {
  await page.clock.install();
  await page.goto('/');
  await page.getByRole('button', { name: 'Crear una cuenta de comprador', exact: true }).click();
  await page.getByRole('textbox', { name: 'Nombre completo' }).fill('Persona de prueba');
  await page.getByRole('textbox', { name: 'Correo electrónico' }).fill('registro@demo.invalid');
  await page.getByLabel('Teléfono de contacto').fill('2615551234');
  await page.clock.fastForward(65_000);
  const refreshedSession = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/me'),
  );
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
  expect((await refreshedSession).status()).toBe(401);
  await expect(page.getByRole('textbox', { name: 'Nombre completo' })).toHaveValue(
    'Persona de prueba',
  );
  await expect(page.getByRole('textbox', { name: 'Correo electrónico' })).toHaveValue(
    'registro@demo.invalid',
  );
  await expect(page.getByLabel('Teléfono de contacto')).toHaveValue('2615551234');
  await page.getByRole('button', { name: 'Volver a ingresar', exact: true }).click();
  await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Recuperar el acceso' })).toBeVisible();
  await page.getByRole('button', { name: 'Volver a ingresar', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
});

test('navegación de farmacia mantiene la sección después de actualizar y cierra la sesión', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Farmacia ·/ }).click();
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await page.getByRole('link', { name: /Stock y precios/ }).click();
  await expect(page.getByRole('heading', { name: 'Stock y precios' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Stock y precios' })).toBeVisible();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
});
