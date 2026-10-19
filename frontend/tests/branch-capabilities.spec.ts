import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { Branch } from '../src/catalog/types';

// The workflow runner provides a new database and private files for this scenario.
test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

async function login(page: Page, profile: 'Administrador' | 'Comprador') {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  const signedIn = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  expect((await signedIn).ok()).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
}

async function mutate(
  request: APIRequestContext,
  method: 'POST' | 'PUT',
  path: string,
  body: unknown,
) {
  const csrf = await (await request.get('/api/auth/csrf')).json();
  const response = await request.fetch(path, {
    method,
    data: body,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
  return response.json();
}

const evidence = {
  name: 'control-conservacion.pdf',
  mimeType: 'application/pdf',
  buffer: readFileSync(new URL('./fixtures/control-conservacion.pdf', import.meta.url)),
};

async function readBranch(page: Page, id: string): Promise<Branch> {
  const response = await page.request.get('/api/admin/overview');
  expect(response.ok()).toBeTruthy();
  const branch = (await response.json()).branches.find((branch: Branch) => branch.id === id);
  expect(branch).toBeTruthy();
  return branch;
}

test('Incidencias: sucursal con frío, acreditación, persistencia y solicitud de entrega', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyerContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  const buyer = await buyerContext.newPage();
  admin.setDefaultTimeout(15000);
  buyer.setDefaultTimeout(15000);
  const errors: string[] = [];
  admin.on('pageerror', (error) => errors.push(error.message));
  buyer.on('pageerror', (error) => errors.push(error.message));
  const branchName = `Sucursal frío QA ${Date.now()}`;

  try {
    await login(admin, 'Administrador');
    await admin.getByRole('tab', { name: 'Sucursales', exact: true }).click();
    await admin.getByRole('button', { name: 'Agregar sucursal', exact: true }).click();
    let editor = admin.getByRole('dialog');
    await editor.getByLabel(/^Nombre de la sucursal(?:\s*\*)?$/).fill(branchName);
    await editor
      .getByLabel(/^Domicilio(?:\s*\*)?$/)
      .fill('Domicilio sintético QA, Ciudad, Mendoza');
    // Keep the QA marker apart from seeded branches used by other browser workflows.
    await editor.getByLabel(/^Latitud(?:\s*\*)?$/).fill('-32.9');
    await editor.getByLabel(/^Longitud(?:\s*\*)?$/).fill('-68.86');
    await editor
      .getByLabel(/^Farmacia que cobra y factura(?:\s*\*)?$/)
      .fill('Farmacia sintética QA');
    await editor
      .getByRole('checkbox', { name: 'Habilitación y datos verificados', exact: true })
      .check();
    await editor
      .getByRole('checkbox', { name: 'Acuerdo de adhesión aceptado', exact: true })
      .check();
    await editor
      .getByRole('checkbox', { name: 'Tiene cadete propio disponible', exact: true })
      .check();
    const cold = () =>
      editor.getByRole('checkbox', {
        name: 'Capacidad comprobada para cadena de frío',
        exact: true,
      });
    await expect(cold()).toBeEnabled();
    await cold().check();
    await editor
      .getByRole('checkbox', {
        name: 'Capacidad comprobada para controles especiales',
        exact: true,
      })
      .check();
    const created = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/admin/branches') && response.request().method() === 'POST',
    );
    await editor.getByRole('button', { name: 'Continuar con acreditación', exact: true }).click();
    const creation = await created;
    expect(creation.ok(), await creation.text()).toBeTruthy();
    const initial: Branch = await creation.json();
    expect(initial.coldChainCapable).toBe(false);
    expect(initial.restrictedCapable).toBe(false);
    expect(creation.request().postDataJSON().coldChainCapable).toBe(false);
    await expect(editor).toBeVisible();
    await expect(editor.getByLabel(/^Nombre de la sucursal(?:\s*\*)?$/)).toHaveValue(branchName);
    await expect(cold()).toBeChecked();
    await expect(
      editor.getByRole('checkbox', {
        name: 'Capacidad comprobada para controles especiales',
        exact: true,
      }),
    ).toBeChecked();
    await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
    await expect(
      editor.getByText(
        'Adjuntá la evidencia de habilitación y capacidad para confirmar la selección.',
        { exact: true },
      ),
    ).toBeVisible();
    expect((await readBranch(admin, initial.id)).coldChainCapable).toBe(false);

    // The existing server validation remains mandatory even if a caller skips the form.
    const csrf = await (await admin.request.get('/api/auth/csrf')).json();
    const withoutEvidence = await admin.request.put(`/api/admin/branches/${initial.id}`, {
      data: { ...initial, coldChainCapable: true },
      headers: { [csrf.headerName]: csrf.token },
    });
    expect(withoutEvidence.status()).toBe(400);
    expect((await withoutEvidence.json()).code).toBe('CAPABILITY_EVIDENCE_REQUIRED');

    const uploaded = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/documents') && response.request().method() === 'POST',
    );
    await editor
      .getByLabel('Evidencia de habilitación y capacidad', { exact: true })
      .setInputFiles(evidence);
    const documentResponse = await uploaded;
    expect(documentResponse.ok(), await documentResponse.text()).toBeTruthy();
    const documentId = (await documentResponse.json()).id;
    await expect(editor.getByText('Archivo guardado', { exact: true })).toBeVisible();
    const saved = admin.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/admin/branches/${initial.id}`) &&
        response.request().method() === 'PUT',
    );
    await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
    const finalSave = await saved;
    expect(finalSave.ok(), await finalSave.text()).toBeTruthy();
    await expect(editor).not.toBeVisible();
    await admin.reload();
    const persisted = await readBranch(admin, initial.id);
    expect(persisted.coldChainCapable).toBe(true);
    expect(persisted.restrictedCapable).toBe(true);
    expect(persisted.deliveryEnabled).toBe(true);
    expect(persisted.capabilities.capabilityEvidenceDocumentId).toBe(documentId);
    expect(persisted.capabilities.verifiedAt).toBeTruthy();
    expect(persisted.capabilities.verifiedBy).toBeTruthy();
    await admin.getByRole('tab', { name: 'Sucursales', exact: true }).click();
    await admin
      .getByText(branchName, { exact: true })
      .locator('../..')
      .getByRole('button', { name: 'Modificar', exact: true })
      .click();
    editor = admin.getByRole('dialog');
    await expect(cold()).toBeChecked();
    await expect(editor.getByText('Archivo guardado', { exact: true })).toBeVisible();
    await editor.getByText('Archivo guardado', { exact: true }).scrollIntoViewIfNeeded();
    await admin.screenshot({
      path: 'test-results/sucursal-frio-capacidad-persistida.png',
      fullPage: true,
    });

    // Evidence replacement must finish before a save. Failed uploads retain the existing
    // accreditation and the draft, and a retry must persist the replacement document.
    let allowUpload: (() => void) | undefined;
    let uploadGate = new Promise<void>((resolve) => {
      allowUpload = resolve;
    });
    let failUpload = true;
    let branchUpdates = 0;
    await admin.route(`**/api/admin/branches/${initial.id}`, async (route) => {
      if (route.request().method() === 'PUT') branchUpdates += 1;
      await route.continue();
    });
    await admin.route('**/api/documents', async (route) => {
      await uploadGate;
      if (failUpload) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ detail: 'La evidencia no se pudo guardar. Reintentá.' }),
        });
      } else await route.continue();
    });
    const replacementEvidence = {
      ...evidence,
      name: 'control-conservacion-reemplazo-qa.pdf',
      buffer: Buffer.concat([evidence.buffer, Buffer.from('\n% Reemplazo sintetico QA\n')]),
    };
    const failedUpload = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/documents') && response.request().method() === 'POST',
    );
    await editor
      .getByLabel('Evidencia de habilitación y capacidad', { exact: true })
      .setInputFiles(replacementEvidence);
    await expect(editor.locator('button[type="submit"]')).toBeDisabled();
    await expect(editor.getByRole('button', { name: 'Cerrar', exact: true })).toBeDisabled();
    await editor.getByLabel(/^Nombre de la sucursal(?:\s*\*)?$/).press('Enter');
    await admin.keyboard.press('Escape');
    await expect(editor).toBeVisible();
    expect(branchUpdates).toBe(0);
    allowUpload!();
    expect((await failedUpload).status()).toBe(503);
    await expect(
      editor.getByText('La evidencia no se pudo guardar. Reintentá.', { exact: true }),
    ).toBeVisible();
    await expect(editor.locator('button[type="submit"]')).toBeEnabled();
    await expect(cold()).toBeChecked();
    expect((await readBranch(admin, initial.id)).capabilities.capabilityEvidenceDocumentId).toBe(
      documentId,
    );

    failUpload = false;
    uploadGate = new Promise<void>((resolve) => {
      allowUpload = resolve;
    });
    const retriedUpload = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/documents') && response.request().method() === 'POST',
    );
    await editor
      .getByLabel('Evidencia de habilitación y capacidad', { exact: true })
      .setInputFiles(replacementEvidence);
    await expect(editor.locator('button[type="submit"]')).toBeDisabled();
    expect(branchUpdates).toBe(0);
    allowUpload!();
    const retryResponse = await retriedUpload;
    expect(retryResponse.ok(), await retryResponse.text()).toBeTruthy();
    const replacementDocumentId = (await retryResponse.json()).id;
    expect(replacementDocumentId).not.toBe(documentId);
    await expect(editor.getByText(replacementEvidence.name, { exact: true })).toBeVisible();
    await expect(editor.locator('button[type="submit"]')).toBeEnabled();
    const replacementSaved = admin.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/admin/branches/${initial.id}`) &&
        response.request().method() === 'PUT',
    );
    await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
    expect((await replacementSaved).ok()).toBeTruthy();
    await expect(editor).not.toBeVisible();
    expect(branchUpdates).toBe(1);
    expect((await readBranch(admin, initial.id)).capabilities.capabilityEvidenceDocumentId).toBe(
      replacementDocumentId,
    );
    await admin.unroute('**/api/documents');
    await admin.unroute(`**/api/admin/branches/${initial.id}`);
    await admin
      .getByText(branchName, { exact: true })
      .locator('../..')
      .getByRole('button', { name: 'Modificar', exact: true })
      .click();
    editor = admin.getByRole('dialog');
    await expect(cold()).toBeChecked();
    await editor.getByRole('button', { name: 'Cerrar', exact: true }).click();

    // A new branch also needs its own driver accreditation; branch capacity alone is insufficient.
    const overview = await (await admin.request.get('/api/admin/overview')).json();
    const driver = overview.users.find(
      (user: { email: string }) => user.email === 'cadete@demo.invalid',
    );
    expect(driver).toBeTruthy();
    await admin.getByRole('tab', { name: 'Miembros', exact: true }).click();
    await admin.getByRole('button', { name: 'Asignar permiso', exact: true }).click();
    editor = admin.getByRole('dialog');
    await editor.getByRole('combobox', { name: /^Persona(?:\s*\*)?$/ }).click();
    await admin.getByRole('option', { name: new RegExp(`cadete@demo.invalid$`) }).click();
    await editor.getByRole('combobox', { name: /^Sucursal autorizada(?:\s*\*)?$/ }).click();
    await admin.getByRole('option', { name: branchName, exact: true }).click();
    await editor.getByRole('combobox', { name: /^Permiso(?:\s*\*)?$/ }).click();
    await admin.getByRole('option', { name: 'Cadete propio', exact: true }).click();
    await editor
      .getByRole('checkbox', { name: 'Acreditar transporte con cadena de frío', exact: true })
      .check();
    await editor
      .getByLabel('Evidencia de capacidad del cadete', { exact: true })
      .setInputFiles(evidence);
    await expect(editor.getByText('Archivo guardado', { exact: true })).toBeVisible();
    const driverSaved = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/admin/memberships') && response.request().method() === 'POST',
    );
    await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
    const membershipResponse = await driverSaved;
    expect(membershipResponse.ok(), await membershipResponse.text()).toBeTruthy();
    const member = await membershipResponse.json();
    expect(member.coldChainCapable).toBe(true);
    expect(member.capabilityVerifiedAt).toBeTruthy();
    expect(member.branchId).toBe(initial.id);
    expect(member.userId).toBe(driver.id);
    await expect(editor).not.toBeVisible();

    const product = await mutate(admin.request, 'POST', '/api/admin/products', {
      code: `COLD-QA-${Date.now()}`,
      name: 'Presentación sintética de cadena de frío QA',
      presentation: 'Envase sintético QA',
      requiresPrescription: false,
      coldChain: true,
      restricted: false,
      active: true,
      version: 0,
      metadata: { minTemperatureC: 2, maxTemperatureC: 8, demo: true },
    });
    const stock = await mutate(
      admin.request,
      'PUT',
      `/api/branches/${initial.id}/stock/${product.id}`,
      {
        quantity: 0,
        price: '100.00',
        expectedVersion: 0,
        reason: 'Oferta sintética para verificar la entrega con cadena de frío.',
      },
    );
    await mutate(admin.request, 'POST', `/api/branches/${initial.id}/stock/${product.id}/lots`, {
      lotCode: `FRIO-QA-${Date.now()}`,
      expiresOn: '2099-12-31',
      quantity: 5,
      status: 'AVAILABLE',
      expectedVersion: 0,
      stockVersion: stock.version,
      reason: 'Lote sintético con conservación y vencimiento del escenario QA.',
    });
    await login(buyer, 'Comprador');
    const actor = await (await buyer.request.get('/api/auth/me')).json();
    const purchase = await mutate(buyer.request, 'POST', '/api/purchases', {
      beneficiaryName: actor.name,
      beneficiaryBirthDate: actor.birthDate,
      thirdParty: false,
      mode: 'DELIVERY',
      destinationLat: -32.889,
      destinationLon: -68.845,
      destinationAddress: 'Av. España 1185, Ciudad, Mendoza',
      preferredBranchId: initial.id,
      items: [{ productId: product.id, quantity: 1 }],
    });
    const order = purchase.orders.find(
      (order: { branchId: string; status: string }) =>
        order.branchId === initial.id && order.status === 'PROPOSED',
    );
    expect(order, JSON.stringify(purchase)).toBeTruthy();
    await admin.screenshot({ path: 'test-results/sucursal-frio-acreditada.png', fullPage: true });
    expect(errors).toEqual([]);
  } finally {
    await adminContext.close();
    await buyerContext.close();
  }
});
