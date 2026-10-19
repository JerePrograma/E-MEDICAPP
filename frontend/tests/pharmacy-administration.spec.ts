import { test, expect } from '@playwright/test';
import type { Branch } from '../src/catalog/types';
import type { Overview } from '../src/admin/types';

test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  const url = new URL(process.env.EMEDICAPP_E2E_URL ?? 'http://127.0.0.1:8082');
  expect(url.origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

test('administrador de farmacia ingresa, guarda su sucursal y conserva su alcance', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page
    .getByRole('option', { name: 'Administrador de farmacia · Sofía Méndez', exact: true })
    .click();
  const loggedIn = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  expect((await loggedIn).ok()).toBeTruthy();
  await expect(page.getByRole('heading', { name: 'Administración', exact: true })).toBeVisible();
  await expect(page.getByRole('tab')).toHaveText(['Sucursales', 'Miembros']);
  await expect(page.getByRole('button', { name: 'Agregar sucursal', exact: true })).toHaveCount(0);
  const actor = await (await page.request.get('/api/auth/me')).json();
  expect(actor.roles).toEqual(['PHARMACY_ADMIN']);
  const overviewResponse = await page.request.get('/api/admin/overview');
  expect(overviewResponse.ok()).toBeTruthy();
  const overview = (await overviewResponse.json()) as Overview;
  expect(overview.branches).toHaveLength(2);
  expect(overview.branches.map((branch) => branch.name).sort()).toEqual([
    'Nivalis · Ciudad',
    'Nivalis · Dorrego',
  ]);
  expect(overview.branches.every((branch) => actor.branchIds.includes(branch.id))).toBeTruthy();
  expect(overview.plans).toEqual([]);
  expect(overview.subscriptions).toEqual([]);
  const branch = overview.branches.find((branch) => branch.name === 'Nivalis · Ciudad')!;
  await page
    .getByText(branch.name, { exact: true })
    .locator('../..')
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  let dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('combobox', { name: 'Cadena (opcional)' })).toBeDisabled();
  await expect(
    dialog.getByRole('checkbox', { name: 'Habilitación y datos verificados' }),
  ).toBeDisabled();
  const instructions = `${branch.paymentInstructions} Responsable de sucursal QA.`;
  await dialog.getByRole('textbox', { name: 'Instrucciones de pago directo' }).fill(instructions);
  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/admin/branches/${branch.id}`) &&
      response.request().method() === 'PUT',
  );
  await dialog.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
  const savedResponse = await saved;
  expect(savedResponse.ok(), await savedResponse.text()).toBeTruthy();
  await expect(dialog).not.toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Administración', exact: true })).toBeVisible();
  await page
    .getByText(branch.name, { exact: true })
    .locator('../..')
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('textbox', { name: 'Instrucciones de pago directo' })).toHaveValue(
    instructions,
  );
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await page.getByRole('tab', { name: 'Miembros', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Asignar permiso', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Asignar permiso', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(
    dialog.getByRole('button', { name: 'Buscar cuenta confirmada', exact: true }),
  ).toBeVisible();
  await dialog.getByRole('combobox', { name: 'Sucursal autorizada' }).click();
  await expect(page.getByRole('option', { name: 'Nivalis · Ciudad', exact: true })).toBeVisible();
  await expect(page.getByRole('option', { name: 'Nivalis · Dorrego', exact: true })).toBeVisible();
  await expect(page.getByRole('option', { name: 'Valnera · Godoy Cruz', exact: true })).toHaveCount(
    0,
  );
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  const publicBranchesResponse = await page.request.get('/api/catalog/branches');
  expect(publicBranchesResponse.ok()).toBeTruthy();
  const foreign = ((await publicBranchesResponse.json()) as Branch[]).find(
    (candidate) => !actor.branchIds.includes(candidate.id),
  );
  expect(foreign).toBeTruthy();
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const forbidden = await page.request.put(`/api/admin/branches/${foreign!.id}`, {
    data: branch,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(forbidden.status()).toBe(403);
  expect((await page.request.get('/api/admin/settings')).status()).toBe(403);
  // A failed background refresh must not discard the branch form being edited.
  await page.getByRole('tab', { name: 'Sucursales', exact: true }).click();
  await page
    .getByText(branch.name, { exact: true })
    .locator('../..')
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  const draft = page
    .getByRole('dialog')
    .getByRole('textbox', { name: 'Instrucciones de pago directo' });
  await draft.fill('Borrador conservado al recuperar la ventana.');
  await page.route('**/api/admin/overview', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Sin conexión temporal.' }),
    }),
  );
  const refresh = page.waitForResponse((response) =>
    response.url().endsWith('/api/admin/overview'),
  );
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
  expect((await refresh).status()).toBe(503);
  await expect(draft).toHaveValue('Borrador conservado al recuperar la ventana.');
  await page.unroute('**/api/admin/overview');
  await page.keyboard.press('Escape');
  await page.screenshot({
    path: 'test-results/administrador-farmacia-alcance.png',
    fullPage: true,
  });
});
