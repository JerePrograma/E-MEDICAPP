import {
  test,
  expect,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from '@playwright/test';

import { readFileSync } from 'node:fs';

// The runner creates an isolated database and private document directory for every invocation.
// No test may operate on the presentation database or a remote environment.
test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  const url = new URL(process.env.EMEDICAPP_E2E_URL ?? 'http://127.0.0.1:8082');
  expect(url.origin).toBe('http://127.0.0.1:8083');
  const bootstrap = await (await request.get('/api/bootstrap')).json();
  expect(bootstrap.mode).toBe('DEMO');
});

function fixture(filename: string) {
  return {
    name: filename,
    mimeType: 'application/pdf',
    buffer: readFileSync(new URL(`./fixtures/${filename}`, import.meta.url)),
  };
}
const evidence = fixture('comprobante-pago.pdf');
const invoiceEvidence = fixture('factura-farmacia.pdf');
const conservationEvidence = fixture('control-conservacion.pdf');
async function send(request: APIRequestContext, path: string, body: unknown) {
  const token = await (await request.get('/api/auth/csrf')).json();
  const response = await request.post(path, {
    data: body,
    headers: { [token.headerName]: token.token, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
  return response.json();
}
// Keep valid sessions only in memory. Every context still checks server authority and the actor.
// This mirrors separate browser tabs without turning repeated fixture logins into an IP burst.
const demoSessions = new Map<
  string,
  {
    actorId: string;
    cookies: Awaited<ReturnType<BrowserContext['cookies']>>;
  }
>();
const demoRoles: Record<string, string> = {
  Comprador: 'BUYER',
  Farmacia: 'PHARMACY',
  Farmacéutico: 'PHARMACIST',
  'Segundo farmacéutico': 'PHARMACIST',
  Cadete: 'DRIVER',
  Administrador: 'ADMIN',
};
async function login(page: Page, profile = 'Comprador') {
  const role = demoRoles[profile];
  if (!role) throw new Error('Perfil de QA desconocido.');
  const saved = demoSessions.get(profile);
  if (saved) {
    await page.context().addCookies(saved.cookies);
    const response = await page.request.get('/api/auth/me');
    if (response.ok()) {
      const actor = await response.json();
      if (actor.id === saved.actorId && actor.roles.includes(role)) {
        await page.goto('/');
        await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
        return;
      }
    }
    demoSessions.delete(profile);
    await page.context().clearCookies();
  }
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  const signedIn = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const response = await signedIn;
  expect(
    response.ok(),
    `Ingreso QA ${profile}: HTTP ${response.status()} ${await response.text()}`,
  ).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  const actorResponse = await page.request.get('/api/auth/me');
  expect(actorResponse.ok()).toBeTruthy();
  const actor = await actorResponse.json();
  expect(actor.roles).toContain(role);
  demoSessions.set(profile, { actorId: actor.id, cookies: await page.context().cookies() });
}
async function selectPurchase(page: Page, id: string) {
  await page
    .getByText(`Compra ${id.slice(0, 8).toUpperCase()}`, { exact: true })
    .first()
    .locator('..')
    .getByRole('button', { name: 'Ver detalle' })
    .click();
}
async function dialogAction(page: Page, label: string) {
  await page.getByRole('button', { name: label, exact: true }).click();
  return page.getByRole('dialog');
}
async function confirm(page: Page) {
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Confirmar registro', exact: true })
    .click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
}
async function screenshots(page: Page, name: string) {
  const previous = page.viewportSize() ?? { width: 1440, height: 1000 };
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `test-results/${name}.png`, fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBeTruthy();
  await page.screenshot({ path: `test-results/${name}-movil.png`, fullPage: true });
  await page.setViewportSize(previous);
}
async function financialControl(page: Page, purchaseId: string, label: string) {
  await page.reload();
  await selectPurchase(page, purchaseId);
  const dialog = await dialogAction(page, `Revisar ${label}`);
  await dialog
    .getByLabel('Comprobaciones realizadas para el control')
    .fill('Revisión independiente del importe, destinatario y evidencia del escenario aislado.');
  await dialog
    .getByLabel('Evidencia del control de alto valor', { exact: true })
    .setInputFiles(
      fixture(
        label.includes('reintegro')
          ? 'comprobante-reintegro-alto-valor.pdf'
          : 'comprobante-pago-alto-valor.pdf',
      ),
    );
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await confirm(page);
}
async function upload(
  request: APIRequestContext,
  kind: string,
  branchId?: string,
  file = evidence,
) {
  const csrf = await (await request.get('/api/auth/csrf')).json();
  const response = await request.post('/api/documents', {
    multipart: { file, kind, ...(branchId ? { branchId } : {}) },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()).id;
}

async function readyDelivery(
  buyer: Page,
  pharmacy: Page,
  referenceSuffix: string,
  address: string,
) {
  const products = await (await buyer.request.get('/api/catalog/products')).json();
  const branches = await (await buyer.request.get('/api/catalog/branches')).json();
  const product = products.find((p: { name: string }) => p.name === 'Paracetamol Nivalen 500 mg');
  const branch = branches.find((b: { name: string }) => b.name === 'Nivalis · Ciudad');
  let purchase = await send(buyer.request, '/api/purchases', {
    beneficiaryName: 'Lucía Fernández',
    beneficiaryBirthDate: '1990-01-01',
    thirdParty: false,
    mode: 'DELIVERY',
    destinationLat: -32.889,
    destinationLon: -68.845,
    destinationAddress: address,
    preferredBranchId: branch.id,
    items: [{ productId: product.id, quantity: 1 }],
  });
  let order = purchase.orders.find((o: { status: string }) => o.status === 'PROPOSED');
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/review`, {
    expectedVersion: order.version,
    decision: 'APPROVE',
    reason: 'DEMO: verificación manual ficticia.',
    authorizationChecked: true,
    originVerified: false,
    deliveryMinutes: 45,
    lines: [{ productId: product.id, coverageAmount: '0.00', authorizedQuantity: 1 }],
  });
  purchase = await send(buyer.request, `/api/purchases/${purchase.id}/accept`, {
    expectedVersion: purchase.version,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  const payment = await upload(buyer.request, 'EVIDENCIA');
  purchase = await send(buyer.request, `/api/orders/${order.id}/payment-report`, {
    expectedVersion: order.version,
    reference: `NV-00874321-${referenceSuffix}`,
    evidenceDocumentId: payment,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/payment`, {
    expectedVersion: order.version,
    reference: `NV-00874321-${referenceSuffix}`,
    evidenceDocumentId: payment,
    verifiedExternally: true,
    amount: order.outstandingAmount,
    currency: order.currency,
    method: 'TRANSFER',
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  const invoice = await upload(pharmacy.request, 'FACTURA', branch.id, invoiceEvidence);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/invoice`, {
    expectedVersion: order.version,
    reference: `0003-00001848-${referenceSuffix}`,
    evidenceDocumentId: invoice,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/prepare`, {
    expectedVersion: order.version,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/dispense`, {
    expectedVersion: order.version,
    conditionNote: 'DEMO: embalaje ficticio íntegro.',
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  expect(order.fulfillment.status).toBe('ASSIGNED');
  return { purchase, order, branch };
}

test('compra real persistida: solicitud, revisión, aceptación, pago documentado, factura y retiro', async ({
  browser,
}) => {
  const buyerContext = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    geolocation: { latitude: -32.889, longitude: -68.845 },
    permissions: ['geolocation'],
  });
  const pharmacyContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyer = await buyerContext.newPage(),
    pharmacy = await pharmacyContext.newPage();
  const failures: string[] = [];
  buyer.on('pageerror', (e) => failures.push(e.message));
  pharmacy.on('pageerror', (e) => failures.push(e.message));
  await login(buyer);
  await login(pharmacy, 'Farmacéutico');
  await buyer.getByRole('button', { name: 'Nueva compra' }).click();
  await expect(buyer.getByLabel('Fecha de nacimiento del beneficiario')).toHaveValue('1990-01-01');
  await expect(buyer.getByLabel('Nombre del beneficiario')).toHaveValue('Lucía Fernández');
  await buyer.getByRole('combobox', { name: 'Cómo querés recibir la compra' }).click();
  await buyer.getByRole('option', { name: 'Retiro en farmacia', exact: true }).click();
  await buyer.getByRole('button', { name: 'Usar mi ubicación actual' }).click();
  await expect(buyer.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await buyer.getByRole('combobox', { name: 'Farmacia de preferencia' }).click();
  await buyer.getByRole('option', { name: /^Nivalis · Ciudad/ }).click();
  await buyer.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  await buyer.getByLabel('Cantidad de Paracetamol Nivalen 500 mg', { exact: true }).fill('1');
  await buyer.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await buyer.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
  const created = buyer.waitForResponse(
    (r) => r.url().endsWith('/api/purchases') && r.request().method() === 'POST',
  );
  await buyer.getByRole('button', { name: 'Solicitar propuesta a la farmacia' }).click();
  let purchase = await (await created).json();
  expect(purchase.status).toBe('ROUTING');
  await pharmacy.reload();
  await selectPurchase(pharmacy, purchase.id);
  let dialog = await dialogAction(pharmacy, 'Revisar solicitud');
  await dialog
    .getByLabel('Comprobaciones realizadas y motivo')
    .fill('DEMO: existencia e identidad verificadas con datos ficticios.');
  await dialog
    .getByRole('checkbox', {
      name: 'Verifiqué personalmente edad, identidad y autorización cuando corresponde',
    })
    .check();
  await dialog
    .getByRole('checkbox', {
      name: 'Confirmé existencias físicas, precio vigente y presentación solicitada',
    })
    .check();
  await dialog.getByLabel('Plazo estimado para estar listo (minutos)').fill('30');
  await confirm(pharmacy);
  await expect(buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' })).toBeVisible({
    timeout: 15000,
  });
  await buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' }).click();
  await buyer
    .getByRole('checkbox', {
      name: 'Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes, plazos y modalidad de esta propuesta',
    })
    .check();
  await buyer.getByRole('button', { name: 'Aceptar presupuesto', exact: true }).click();
  await expect(buyer.getByRole('dialog')).not.toBeVisible();
  await expect(buyer.getByRole('button', { name: 'Informar pago', exact: true })).toBeVisible();
  dialog = await dialogAction(buyer, 'Informar pago');
  const paymentReference = 'NV-00874321';
  await dialog.getByLabel('Referencia del pago realizado').fill(paymentReference);
  await dialog
    .getByLabel('Comprobante del pago a la farmacia', { exact: true })
    .setInputFiles(evidence);
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await dialog.getByRole('button', { name: 'Enviar comprobante', exact: true }).click();
  await expect(buyer.getByRole('dialog')).not.toBeVisible();
  await expect
    .poll(
      async () =>
        (await (await pharmacy.request.get(`/api/purchases/${purchase.id}`)).json()).orders[0]
          .paymentSubmission?.reference,
    )
    .toBe(paymentReference);
  await pharmacy.reload();
  await selectPurchase(pharmacy, purchase.id);
  dialog = await dialogAction(pharmacy, 'Verificar pago');
  await expect(dialog.getByLabel('Referencia de la operación externa')).toHaveValue(
    paymentReference,
  );
  const proof = await pharmacy.request.get(
    new URL(
      (await dialog
        .getByRole('link', { name: 'Consultar comprobante de pago' })
        .getAttribute('href')) ?? '',
      test.info().project.use.baseURL,
    ).href,
  );
  expect(proof.ok()).toBeTruthy();
  expect(await proof.body()).toEqual(evidence.buffer);
  await dialog
    .getByRole('checkbox', { name: 'Comprobé que la farmacia recibió el importe registrado' })
    .check();
  await dialog.getByRole('combobox', { name: 'Medio por el que se recibió el pago' }).click();
  await pharmacy.getByRole('option', { name: 'Transferencia bancaria', exact: true }).click();
  await confirm(pharmacy);
  purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  expect(purchase.orders[0].payments[0].method).toBe('TRANSFER');
  dialog = await dialogAction(pharmacy, 'Informar problema de facturación');
  const invoiceIssueReason =
    'El sistema externo de facturación no emitió el comprobante por una interrupción de conexión. La farmacia documenta la falla y debe reintentar manualmente.';
  await dialog.getByLabel('Problema comprobado al emitir la factura').fill(invoiceIssueReason);
  await dialog
    .getByLabel('Evidencia operativa del problema de facturación', { exact: true })
    .setInputFiles(fixture('incidencia-facturacion.pdf'));
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await confirm(pharmacy);
  purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  expect(purchase.orders[0].invoiceIssue).toMatchObject({
    active: true,
    priority: 'HIGH',
    manualRetryRequired: true,
    attemptCount: 1,
  });
  await expect(
    pharmacy.getByText('Problema de facturación pendiente', { exact: true }),
  ).toBeVisible();
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  await login(admin, 'Administrador');
  await expect(
    admin.getByText('Facturación pendiente · prioridad alta', { exact: true }),
  ).toBeVisible();
  await expect(admin.getByText(invoiceIssueReason, { exact: true })).toBeVisible();
  await screenshots(admin, 'admin-facturacion-pendiente');
  dialog = await dialogAction(pharmacy, 'Adjuntar factura');
  await dialog.getByLabel('Número/referencia de factura').fill('0003-00001847');
  await dialog
    .getByLabel('Factura emitida por la farmacia', { exact: true })
    .setInputFiles(invoiceEvidence);
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await confirm(pharmacy);
  purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  expect(purchase.orders[0].invoiceIssue?.active ?? false).toBe(false);
  await expect
    .poll(async () => (await (await admin.request.get('/api/admin/invoice-issues')).json()).length)
    .toBe(0);
  await admin.reload();
  await expect(
    admin.getByText('Facturación pendiente · prioridad alta', { exact: true }),
  ).not.toBeVisible();
  await adminContext.close();
  await dialogAction(pharmacy, 'Preparar');
  await confirm(pharmacy);
  await expect(
    pharmacy.getByRole('button', { name: 'Registrar dispensación', exact: true }),
  ).toBeEnabled();
  await screenshots(pharmacy, 'farmacia-preparacion');
  dialog = await dialogAction(pharmacy, 'Registrar dispensación');
  await dialog
    .getByLabel('Condiciones comprobadas', { exact: true })
    .fill('Presentación completa e íntegra, lote vigente y unidades comprobadas para retiro.');
  await dialog
    .getByRole('checkbox', {
      name: 'Comprobé presentación, cantidad y condiciones; verifiqué la receta cuando corresponde',
    })
    .check();
  await confirm(pharmacy);
  await expect(buyer.getByRole('button', { name: 'Mi código de recepción' })).toBeVisible({
    timeout: 15000,
  });
  const receipt = buyer.waitForResponse((r) => r.url().includes('/receipt-code'));
  await buyer.getByRole('button', { name: 'Mi código de recepción' }).click();
  const { code } = await (await receipt).json();
  dialog = await dialogAction(pharmacy, 'Confirmar retiro');
  await dialog.getByLabel('Código de recepción del comprador').fill(code);
  await dialog.getByLabel('Nombre de quien retira').fill('Lucía Fernández');
  const buyerIdentity = await (await buyer.request.get('/api/auth/me')).json();
  await dialog.getByLabel('DNI comprobado de quien retira').fill(buyerIdentity.dni);
  await dialog
    .getByRole('checkbox', {
      name: 'Medicamentos, cantidades y presentación coinciden con el pedido',
    })
    .check();
  await dialog
    .getByRole('checkbox', { name: 'Verifiqué presencialmente al receptor y su autorización' })
    .check();
  await dialog
    .getByLabel('Constancia de recepción')
    .fill('DEMO: retiro presencial ficticio comprobado.');
  await dialog
    .getByLabel('Código de recepción del comprador')
    .fill(code === '000000' ? '111111' : '000000');
  await dialog.getByRole('button', { name: 'Confirmar registro', exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByText('El código no coincide. El intento quedó registrado.', { exact: true }),
  ).toBeVisible();
  await dialog.getByLabel('Código de recepción del comprador').fill(code);
  await confirm(pharmacy);
  await expect
    .poll(
      async () => (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).status,
    )
    .toBe('COMPLETED');
  await buyer.reload();
  await selectPurchase(buyer, purchase.id);
  await screenshots(buyer, 'compra-completada');
  expect(failures).toEqual([]);
  await buyerContext.close();
  await pharmacyContext.close();
});

test('entrega a domicilio con cadete propio: acceso, custodia, código y cierre', async ({
  browser,
}) => {
  const contexts = await Promise.all([
    browser.newContext({ baseURL: test.info().project.use.baseURL }),
    browser.newContext({ baseURL: test.info().project.use.baseURL }),
    browser.newContext({ baseURL: test.info().project.use.baseURL }),
  ]);
  const [buyerContext, pharmacyContext, driverContext] = contexts;
  if (!buyerContext || !pharmacyContext || !driverContext)
    throw new Error('No se crearon las sesiones.');
  const buyer = await buyerContext.newPage(),
    pharmacy = await pharmacyContext.newPage(),
    driver = await driverContext.newPage();
  await login(buyer);
  await login(pharmacy, 'Farmacéutico');
  await login(driver, 'Cadete');
  const { purchase, order } = await readyDelivery(
    buyer,
    pharmacy,
    'ENTREGA',
    '9 de Julio 1248, departamento 3, Ciudad, Mendoza',
  );
  await driver.reload();
  await expect(driver.getByRole('heading', { name: 'Entregas', exact: true })).toBeVisible();
  let card = driver.getByText(`Pedido ${order.id.slice(0, 8).toUpperCase()}`).locator('..');
  await card.getByRole('button', { name: 'Aceptar tarea', exact: true }).click();
  await driver.getByRole('dialog').getByRole('button', { name: 'Confirmar', exact: true }).click();
  await expect(driver.getByRole('dialog')).not.toBeVisible();
  card = driver.getByText(`Pedido ${order.id.slice(0, 8).toUpperCase()}`).locator('..');
  await card.getByRole('button', { name: 'Recibí el paquete de la farmacia' }).click();
  await driver
    .getByRole('dialog')
    .getByLabel('Condiciones del paquete recibido')
    .fill('DEMO: retiro de paquete ficticio íntegro.');
  await driver.getByRole('dialog').getByRole('button', { name: 'Confirmar', exact: true }).click();
  await expect(driver.getByRole('dialog')).not.toBeVisible();
  await screenshots(driver, 'cadete-en-camino');
  const receipt = await (await buyer.request.get(`/api/orders/${order.id}/receipt-code`)).json();
  card = driver.getByText(`Pedido ${order.id.slice(0, 8).toUpperCase()}`).locator('..');
  await card.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await driver
    .getByRole('dialog')
    .getByLabel('Constancia de entrega')
    .fill('DEMO: recepción ficticia comprobada.');
  await driver
    .getByRole('dialog')
    .getByLabel('Código informado por el receptor')
    .fill(receipt.code);
  const receiver = await (await buyer.request.get('/api/auth/me')).json();
  await driver
    .getByRole('dialog')
    .getByLabel('Nombre de la persona que recibe')
    .fill(receiver.name);
  await driver.getByRole('dialog').getByLabel('DNI comprobado de quien recibe').fill(receiver.dni);
  await driver
    .getByRole('checkbox', {
      name: 'Medicamentos, cantidades y presentación coinciden con el pedido',
    })
    .check();
  await driver
    .getByRole('checkbox', { name: 'Verifiqué que recibe el titular o la persona autorizada' })
    .check();
  await driver.getByRole('dialog').getByRole('button', { name: 'Confirmar', exact: true }).click();
  await expect(driver.getByRole('dialog')).not.toBeVisible();
  await expect
    .poll(
      async () => (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).status,
    )
    .toBe('COMPLETED');
  await driver.getByRole('combobox', { name: 'Mostrar entregas' }).click();
  await driver.getByRole('option', { name: 'Finalizadas', exact: true }).click();
  await expect(driver.getByText(`Pedido ${order.id.slice(0, 8).toUpperCase()}`)).toBeVisible();
  await screenshots(driver, 'entrega-completada');
  await Promise.all(contexts.map((c) => c.close()));
});

test('administración mutable y presentación móvil sin errores de navegador', async ({
  browser,
}) => {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const page = await context.newPage();
  const failures: string[] = [];
  page.on('pageerror', (e) => failures.push(e.message));
  await login(page, 'Administrador');
  await expect(page.getByRole('heading', { name: 'Administración', exact: true })).toBeVisible();
  const productCode = `EMC-TOP-${Date.now()}`;
  const productName = 'Crema humectante Dermavia';
  await page.getByRole('button', { name: 'Agregar producto', exact: true }).click();
  let editor = page.getByRole('dialog');
  await editor.getByLabel('Código comercial').fill(productCode);
  await editor.getByLabel('Nombre').fill(productName);
  await editor.getByLabel('Presentación vendible').fill('Pomo de 100 g');
  await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
  await expect(editor).not.toBeVisible();
  await page
    .getByText(`${productName} · Pomo de 100 g`, { exact: true })
    .locator('../..')
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  editor = page.getByRole('dialog');
  await editor.getByLabel('Nombre').fill(`${productName} piel sensible`);
  await editor.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
  await expect(editor).not.toBeVisible();
  await expect(
    page.getByText(`${productName} piel sensible · Pomo de 100 g`, { exact: true }),
  ).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await screenshots(page, 'panel-administracion');
  for (const tab of [
    'Productos',
    'Cadenas',
    'Sucursales',
    'Planes',
    'Suscripciones',
    'Miembros',
    'Configuración',
    'Lotes',
    'Usuarios',
  ]) {
    await page.getByRole('tab', { name: tab, exact: true }).click();
    await expect(page.getByRole('tab', { name: tab, exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  }
  const logoutResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/logout') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  expect((await logoutResponse).ok()).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  await expect(page.getByRole('button', { name: 'Nueva compra' })).toBeVisible();
  const products = await (await page.request.get('/api/catalog/products')).json();
  const configuredProduct = products.find(
    (product: { code: string }) => product.code === productCode,
  );
  expect(configuredProduct.name).toBe(`${productName} piel sensible`);
  const unavailable = await send(page.request, '/api/purchases', {
    beneficiaryName: 'Lucía Fernández',
    beneficiaryBirthDate: '1990-01-01',
    thirdParty: false,
    mode: 'PICKUP',
    destinationLat: -32.889,
    destinationLon: -68.845,
    items: [{ productId: configuredProduct.id, quantity: 1 }],
  });
  expect(unavailable.status).toBe('UNAVAILABLE');
  expect(unavailable.orders).toEqual([]);
  expect(unavailable.message).toBeTruthy();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Nueva compra' })).toBeVisible();
  await selectPurchase(page, unavailable.id);
  await expect(page.getByText(unavailable.message, { exact: true })).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: 'test-results/comprador-movil.png', fullPage: true });
  expect(failures).toEqual([]);
  await context.close();
});

test('familiar adulto con receta, cobertura y alto valor: doble revisión, pago y reintegro completo', async ({
  browser,
}) => {
  const buyerContext = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    geolocation: { latitude: -32.889, longitude: -68.845 },
    permissions: ['geolocation'],
  });
  const firstContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const secondContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyer = await buyerContext.newPage();
  const first = await firstContext.newPage();
  const second = await secondContext.newPage();
  const failures: string[] = [];
  for (const page of [buyer, first, second])
    page.on('pageerror', (error) => failures.push(error.message));
  await login(buyer);
  await login(first, 'Farmacéutico');
  await login(second, 'Segundo farmacéutico');
  const catalog = await (await buyer.request.get('/api/catalog/products')).json();
  const highValueProduct = catalog.find(
    (product: { name: string }) => product.name === 'Adalimumab Immunavia 40 mg',
  );
  const branches = await (await buyer.request.get('/api/catalog/branches')).json();
  const center = branches.find((branch: { name: string }) => branch.name === 'Nivalis · Ciudad');
  const before = await (await first.request.get(`/api/branches/${center.id}/stock`)).json();
  const availableBefore = before.find(
    (row: { productId: string }) => row.productId === highValueProduct.id,
  ).availableQuantity;
  await buyer.getByRole('button', { name: 'Nueva compra' }).click();
  await buyer.getByRole('checkbox', { name: 'Compro para un familiar adulto' }).check();
  await buyer.getByLabel('Nombre del beneficiario').fill('Elena Fernández');
  await buyer.getByLabel('Fecha de nacimiento del beneficiario').fill('1962-05-17');
  await buyer.getByRole('textbox', { name: 'DNI del beneficiario', exact: true }).fill('14852761');
  await buyer
    .getByLabel('Autorización firmada por el titular', { exact: true })
    .setInputFiles(fixture('autorizacion-familiar.pdf'));
  await expect(buyer.getByText('Archivo guardado', { exact: true })).toHaveCount(1);
  await buyer.getByRole('combobox', { name: 'Cómo querés recibir la compra' }).click();
  await buyer.getByRole('option', { name: 'Retiro en farmacia', exact: true }).click();
  await buyer.getByRole('button', { name: 'Usar mi ubicación actual' }).click();
  await expect(buyer.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await buyer.getByRole('combobox', { name: 'Farmacia de preferencia' }).click();
  await buyer.getByRole('option', { name: /^Nivalis · Ciudad/ }).click();
  await buyer.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  await buyer.getByLabel('Buscar por nombre o presentación').fill('Immunavia');
  await buyer.getByLabel('Cantidad de Adalimumab Immunavia 40 mg', { exact: true }).fill('1');
  await buyer
    .getByLabel('Receta externa, si corresponde', { exact: true })
    .setInputFiles(fixture('receta-externa.pdf'));
  await expect(buyer.getByText('Archivo guardado', { exact: true })).toHaveCount(1);
  await buyer.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await buyer.getByRole('combobox', { name: 'Cómo querés cotizar los productos' }).click();
  await buyer
    .getByRole('option', { name: 'Solicitar cobertura de obra social o prepaga', exact: true })
    .click();
  await buyer.getByLabel('Obra social o prepaga del beneficiario').fill('Previsión Andina');
  await buyer.getByLabel('Plan, si corresponde').fill('Integral');
  await buyer.getByLabel('Número de afiliado del beneficiario').fill('000458721');
  await buyer
    .getByLabel('Credencial de obra social o prepaga', { exact: true })
    .setInputFiles(fixture('credencial-cobertura.pdf'));
  await expect(buyer.getByText('Archivo guardado', { exact: true })).toHaveCount(1);
  await buyer.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
  const created = buyer.waitForResponse(
    (response) =>
      response.url().endsWith('/api/purchases') && response.request().method() === 'POST',
  );
  await buyer.getByRole('button', { name: 'Solicitar propuesta a la farmacia' }).click();
  let purchase = await (await created).json();
  expect(purchase.status).toBe('ROUTING');
  expect(purchase.thirdParty).toBe(true);
  expect(purchase.beneficiary.name).toBe('Elena Fernández');
  expect(purchase.coverageRequest).toMatchObject({
    provider: 'Previsión Andina',
    plan: 'Integral',
    memberNumber: '000458721',
  });
  expect(typeof purchase.coverageRequest.memberNumber).toBe('string');
  const coverageDocumentId = purchase.coverageRequest.evidenceDocumentId;
  const ownMetadata = await buyer.request.get(`/api/documents/${coverageDocumentId}/metadata`);
  expect(ownMetadata.status()).toBe(200);
  expect(await ownMetadata.json()).toMatchObject({
    kind: 'COBERTURA',
    originalName: 'credencial-cobertura.pdf',
    contentType: 'application/pdf',
  });
  async function clinicalReview(page: Page, secondReview: boolean) {
    await page.reload();
    await selectPurchase(page, purchase.id);
    const dialog = await dialogAction(page, 'Revisar solicitud');
    await expect(
      dialog.getByText(/Previsión Andina.*Plan Integral.*Afiliación 000458721/),
    ).toBeVisible();
    const credentialLink = dialog.getByRole('link', {
      name: 'Consultar credencial de cobertura',
      exact: true,
    });
    await expect(credentialLink).toHaveAttribute('href', `/api/documents/${coverageDocumentId}`);
    const privateCredential = await page.request.get(`/api/documents/${coverageDocumentId}`);
    expect(privateCredential.status()).toBe(200);
    expect(await privateCredential.body()).toEqual(fixture('credencial-cobertura.pdf').buffer);
    expect((await page.request.get(`/api/documents/${coverageDocumentId}/metadata`)).status()).toBe(
      200,
    );
    await dialog
      .getByLabel('Comprobaciones realizadas y motivo')
      .fill(
        'Identidad adulta, autorización firmada, origen y vigencia de receta, lote, conservación y precio comprobados en el escenario.',
      );
    await dialog
      .getByRole('checkbox', {
        name: 'Verifiqué personalmente edad, identidad y autorización cuando corresponde',
      })
      .check();
    await dialog
      .getByRole('checkbox', {
        name: 'Confirmé existencias físicas, precio vigente y presentación solicitada',
      })
      .check();
    if (secondReview) {
      await expect(dialog.getByLabel('Referencia de receta y verificación de origen')).toHaveValue(
        'REC-CU-2026-00914',
      );
      await expect(dialog.getByLabel('Vigencia de la receta')).toHaveValue('2026-11-07T23:59');
    } else {
      await dialog
        .getByLabel('Referencia de receta y verificación de origen')
        .fill('REC-CU-2026-00914');
      await dialog
        .getByLabel('Emisor de la receta')
        .fill('Centro Médico Piedemonte / Profesional simulado');
      await dialog.getByLabel('Vigencia de la receta').fill('2026-11-07T23:59');
    }
    await dialog
      .getByRole('checkbox', { name: 'Verifiqué el origen en la fuente correspondiente' })
      .check();
    if (secondReview) {
      await expect(dialog.getByLabel('Aporte total de obra social')).toHaveValue(
        /^375000(?:\.00)?$/,
      );
      await expect(dialog.getByLabel('Referencia de autorización de obra social')).toHaveValue(
        'PA-2026-000184',
      );
      await expect(
        dialog.getByRole('link', { name: 'Consultar autorización de cobertura', exact: true }),
      ).toHaveAttribute('href', `/api/documents/${coverageAuthorizationDocumentId}`);
    } else {
      await dialog.getByLabel('Aporte total de obra social').fill('375000.00');
      await dialog.getByLabel('Referencia de autorización de obra social').fill('PA-2026-000184');
      await dialog
        .getByLabel('Evidencia de autorización de cobertura', { exact: true })
        .setInputFiles(fixture('autorizacion-cobertura.pdf'));
      await expect(dialog.getByText('Archivo guardado')).toHaveCount(1);
    }

    await dialog.getByLabel('Plazo estimado para estar listo (minutos)').fill('45');
    await dialog
      .getByLabel('Evidencia de condiciones especiales', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toHaveCount(2);
    await confirm(page);
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  }
  await clinicalReview(first, false);
  expect(purchase.status).toBe('ROUTING');
  expect(purchase.orders[0].reviewCount).toBe(1);
  expect(purchase.orders[0].reviewApproved).toBe(false);
  expect(purchase.orders[0].lines[0].coverageEvidenceDocumentId).toBeUndefined();
  const clinicalPurchase = await (await first.request.get(`/api/purchases/${purchase.id}`)).json();
  const coverageAuthorizationDocumentId =
    clinicalPurchase.orders[0].lines[0].coverageEvidenceDocumentId;
  expect(purchase.orders[0].lines[0].coverageAmount).toBe('375000.00');
  for (const page of [first, second]) {
    const authorizationMetadata = await page.request.get(
      `/api/documents/${coverageAuthorizationDocumentId}/metadata`,
    );
    expect(authorizationMetadata.status()).toBe(200);
    expect(await authorizationMetadata.json()).toMatchObject({
      kind: 'COBERTURA',
      branchId: center.id,
      originalName: 'autorizacion-cobertura.pdf',
    });
    const authorization = await page.request.get(
      `/api/documents/${coverageAuthorizationDocumentId}`,
    );
    expect(authorization.status()).toBe(200);
    expect(await authorization.body()).toEqual(fixture('autorizacion-cobertura.pdf').buffer);
  }
  for (const profile of ['Farmacia', 'Administrador', 'Cadete']) {
    const restrictedContext = await browser.newContext({
      baseURL: test.info().project.use.baseURL,
    });
    const restricted = await restrictedContext.newPage();
    await login(restricted, profile);
    for (const documentId of [coverageDocumentId, coverageAuthorizationDocumentId]) {
      expect(
        (await restricted.request.get(`/api/documents/${documentId}`)).status(),
        `${profile} no puede descargar documentos clínicos de cobertura`,
      ).toBe(403);
      expect(
        (await restricted.request.get(`/api/documents/${documentId}/metadata`)).status(),
        `${profile} no puede consultar metadata clínica de cobertura`,
      ).toBe(403);
    }
    await restrictedContext.close();
  }

  await expect(
    buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' }),
  ).not.toBeVisible();
  await clinicalReview(second, true);
  expect(purchase.status).toBe('QUOTED');
  expect(purchase.orders[0].reviewCount).toBe(2);
  expect(purchase.orders[0].reviewApproved).toBe(true);
  expect(purchase.total).toBe('1500000.00');
  await buyer.reload();
  await selectPurchase(buyer, purchase.id);
  await buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' }).click();
  await buyer
    .getByRole('checkbox', {
      name: 'Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes, plazos y modalidad de esta propuesta',
    })
    .check();
  await buyer.getByRole('button', { name: 'Aceptar presupuesto', exact: true }).click();
  await expect(buyer.getByRole('dialog')).not.toBeVisible();
  let dialog = await dialogAction(buyer, 'Informar pago');
  await dialog.getByLabel('Referencia del pago realizado').fill('NV-00874322');
  await dialog
    .getByLabel('Comprobante del pago a la farmacia', { exact: true })
    .setInputFiles(fixture('comprobante-pago-alto-valor.pdf'));
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await dialog.getByRole('button', { name: 'Enviar comprobante', exact: true }).click();
  await expect(buyer.getByRole('dialog')).not.toBeVisible();
  await first.reload();
  await selectPurchase(first, purchase.id);
  dialog = await dialogAction(first, 'Verificar pago');
  await expect(dialog.getByLabel('Referencia de la operación externa')).toHaveValue('NV-00874322');
  await dialog
    .getByRole('checkbox', { name: 'Comprobé que la farmacia recibió el importe registrado' })
    .check();
  await confirm(first);
  await expect
    .poll(
      async () => (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).status,
    )
    .toBe('PAID');
  await buyer.reload();
  await selectPurchase(buyer, purchase.id);
  dialog = await dialogAction(buyer, 'Solicitar cancelación');
  await dialog
    .getByRole('textbox', { name: 'Motivo', exact: true })
    .fill(
      'La titular cancela antes de la dispensación; solicita la devolución completa del cobro.',
    );
  await dialog.getByRole('button', { name: 'Confirmar solicitud', exact: true }).click();
  await expect(buyer.getByRole('dialog')).not.toBeVisible();
  purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  expect(purchase.orders[0].cancellationPending).toBe(true);
  expect(purchase.orders[0].refunds).toEqual([]);
  await financialControl(first, purchase.id, 'cancelación de alto valor');
  await financialControl(second, purchase.id, 'cancelación de alto valor');
  await first.reload();
  await selectPurchase(first, purchase.id);
  dialog = await dialogAction(first, 'Confirmar cancelación autorizada');
  await dialog
    .getByRole('checkbox', {
      name: 'Confirmo que esta farmacia no podrá cumplir el pedido aceptado',
    })
    .check();
  await dialog
    .getByRole('button', { name: 'Confirmar cancelación autorizada', exact: true })
    .click();
  await expect(dialog).not.toBeVisible();
  purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
  expect(purchase.orders[0].cancellationPending).toBe(false);
  expect(purchase.orders[0].refunds[0].amount).toBe('1500000.00');
  expect(purchase.orders[0].refunds[0].status).toBe('REQUESTED');
  const released = await (await first.request.get(`/api/branches/${center.id}/stock`)).json();
  expect(
    released.find((row: { productId: string }) => row.productId === highValueProduct.id)
      .availableQuantity,
  ).toBe(availableBefore);
  await financialControl(first, purchase.id, 'control de reintegro');
  await financialControl(second, purchase.id, 'control de reintegro');
  await first.reload();
  await selectPurchase(first, purchase.id);
  dialog = await dialogAction(first, 'Registrar reintegro');
  await dialog.getByLabel('Referencia de la operación externa').fill('NV-00874323');
  await dialog
    .getByLabel('Comprobante de reintegro realizado', { exact: true })
    .setInputFiles(fixture('comprobante-reintegro-alto-valor.pdf'));
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await dialog
    .getByRole('checkbox', {
      name: 'Comprobé que la farmacia reintegró el importe correspondiente',
    })
    .check();
  await confirm(first);
  await expect
    .poll(
      async () =>
        (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).orders[0]
          .refunds[0].status,
    )
    .toBe('VERIFIED');
  await buyer.reload();
  await selectPurchase(buyer, purchase.id);
  await buyer.screenshot({
    path: 'test-results/familiar-alto-valor-reintegrado.png',
    fullPage: true,
  });
  expect(failures).toEqual([]);
  await Promise.all([buyerContext.close(), firstContext.close(), secondContext.close()]);
});

test('receptor ausente: retorno físico, único reintento coordinado y recepción verificada', async ({
  browser,
}) => {
  test.setTimeout(180000);
  const contexts = await Promise.all(
    Array.from({ length: 3 }, () =>
      browser.newContext({ baseURL: test.info().project.use.baseURL }),
    ),
  );
  const [buyerContext, pharmacyContext, driverContext] = contexts;
  if (!buyerContext || !pharmacyContext || !driverContext)
    throw new Error('No se crearon las sesiones de logística.');
  const buyer = await buyerContext.newPage();
  const pharmacy = await pharmacyContext.newPage();
  const driver = await driverContext.newPage();
  const failures: string[] = [];
  for (const page of [buyer, pharmacy, driver])
    page.on('pageerror', (e) => failures.push(e.message));
  try {
    await login(buyer);
    await login(pharmacy, 'Farmacéutico');
    await login(driver, 'Cadete');
    const prepared = await readyDelivery(
      buyer,
      pharmacy,
      'REINTENTO',
      'San Martín 1685, piso 2, Ciudad, Mendoza',
    );
    let purchase = prepared.purchase;
    let order = prepared.order;
    const deliveryCard = () =>
      driver.getByText(`Pedido ${order.id.slice(0, 8).toUpperCase()}`).locator('..');
    await driver.reload();
    await deliveryCard().getByRole('button', { name: 'Aceptar tarea', exact: true }).click();
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    await deliveryCard().getByRole('button', { name: 'Recibí el paquete de la farmacia' }).click();
    await driver
      .getByRole('dialog')
      .getByLabel('Condiciones del paquete recibido')
      .fill(
        'Paquete íntegro recibido en farmacia, presentación y custodia comprobadas en el escenario.',
      );
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    await deliveryCard().getByRole('button', { name: 'Informar incidencia', exact: true }).click();
    let dialog = driver.getByRole('dialog');
    await dialog.getByRole('combobox', { name: 'Tipo de incidencia' }).click();
    await driver
      .getByRole('option', { name: 'No hay un receptor autorizado en el domicilio', exact: true })
      .click();
    await dialog
      .getByLabel('Qué ocurrió y qué necesita resolver la farmacia')
      .fill(
        'El titular no estaba en el domicilio acordado. Se coordinó el retorno del paquete cerrado a la sucursal.',
      );
    await dialog.getByRole('button', { name: 'Confirmar', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    order = purchase.orders.find((o: { id: string }) => o.id === order.id);
    expect(order.fulfillment).toMatchObject({
      status: 'RETURN_REQUIRED',
      custodyHolder: 'DRIVER',
      incidentKind: 'RECIPIENT_ABSENT',
    });
    await expect(
      deliveryCard().getByRole('button', { name: 'Confirmar entrega', exact: true }),
    ).not.toBeVisible();
    await screenshots(driver, 'cadete-retorno-pendiente');
    await pharmacy.reload();
    await selectPurchase(pharmacy, purchase.id);
    dialog = await dialogAction(pharmacy, 'Registrar recepción física del retorno');
    await dialog
      .getByLabel('Constancia de custodia y estado del paquete retornado')
      .fill(
        'Paquete retornado físicamente, cerrado e íntegro. Se verifica custodia de farmacia sin reincorporación automática al stock.',
      );
    await dialog
      .getByLabel('Evidencia de recepción física del retorno', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await confirm(pharmacy);
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    order = purchase.orders.find((o: { id: string }) => o.id === order.id);
    expect(order.fulfillment).toMatchObject({ status: 'RETURNED', custodyHolder: 'PHARMACY' });
    expect(order.fulfillment.returnReceivedAt).toBeTruthy();
    dialog = await dialogAction(pharmacy, 'Resolver incidencia');
    await dialog.getByRole('combobox', { name: 'Resolución' }).click();
    await pharmacy
      .getByRole('option', { name: 'Coordinar el único reintento permitido', exact: true })
      .click();
    const scheduled = new Date(Math.ceil((Date.now() + 6000) / 60000) * 60000);
    const local = new Date(scheduled.getTime() - 3 * 3600000).toISOString().slice(0, 16);
    await dialog.getByLabel('Fecha acordada de reintento').fill(local);
    await dialog
      .getByLabel('Coordinación con el comprador y el receptor')
      .fill(
        'El titular confirma por contacto directo que estará presente en el mismo domicilio a la hora acordada. Cadete y farmacia quedan informados.',
      );
    await dialog
      .getByLabel('Evaluación y acuerdo de resolución')
      .fill(
        'Paquete íntegro en custodia de la farmacia; receptor ausente confirma recepción. Se autoriza el único reintento coordinado.',
      );
    await dialog
      .getByLabel('Evidencia que respalda la resolución', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await confirm(pharmacy);
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    order = purchase.orders.find((o: { id: string }) => o.id === order.id);
    expect(order.fulfillment.retryCount).toBe(1);
    expect(new Date(order.fulfillment.retryScheduledAt).getTime()).toBe(scheduled.getTime());
    expect(order.fulfillment.status).toBe('ASSIGNED');
    await screenshots(pharmacy, 'farmacia-reintento-coordinado');
    await driver.reload();
    await deliveryCard().getByRole('button', { name: 'Aceptar tarea', exact: true }).click();
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    await expect
      .poll(() => Date.now() >= scheduled.getTime(), { timeout: 75000, intervals: [1000] })
      .toBeTruthy();
    await deliveryCard().getByRole('button', { name: 'Recibí el paquete de la farmacia' }).click();
    await driver
      .getByRole('dialog')
      .getByLabel('Condiciones del paquete recibido')
      .fill('Recepción física del mismo paquete íntegro a la hora del único reintento acordado.');
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    const receipt = await (await buyer.request.get(`/api/orders/${order.id}/receipt-code`)).json();
    const identity = await (await buyer.request.get('/api/auth/me')).json();
    await deliveryCard().getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
    dialog = driver.getByRole('dialog');
    await dialog.getByLabel('Nombre de la persona que recibe').fill(identity.name);
    await dialog.getByLabel('DNI comprobado de quien recibe').fill(identity.dni);
    await dialog.getByLabel('Código informado por el receptor').fill(receipt.code);
    await dialog
      .getByLabel('Constancia de entrega')
      .fill(
        'El titular recibe presencialmente el paquete íntegro durante el único reintento acordado. Identidad y unidades comprobadas.',
      );
    await dialog
      .getByRole('checkbox', { name: 'Verifiqué que recibe el titular o la persona autorizada' })
      .check();
    await dialog
      .getByRole('checkbox', {
        name: 'Medicamentos, cantidades y presentación coinciden con el pedido',
      })
      .check();
    await dialog.getByRole('button', { name: 'Confirmar', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect
      .poll(
        async () =>
          (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).status,
      )
      .toBe('COMPLETED');
    const completed = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    expect(completed.orders[0].fulfillment.retryCount).toBe(1);
    expect(completed.orders[0].fulfillment.status).toBe('DELIVERED');
    expect(completed.orders[0].fulfillment.custodyHolder).toBe('RECIPIENT');
    expect(failures).toEqual([]);
  } finally {
    await Promise.all(contexts.map((c) => c.close()));
  }
});

test('registro sin correo configurado: cuenta pendiente, mensaje explícito y sin inicio automático de sesión', async ({
  browser,
}) => {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const page = await context.newPage();
  const email = `martina.rios.qa.${Date.now()}@example.invalid`;
  const password = 'CuentaQaTemporal2026!';
  try {
    await page.goto('/');
    await page.getByRole('button', { name: 'Crear una cuenta de comprador', exact: true }).click();
    await page.getByRole('textbox', { name: 'Nombre completo', exact: true }).fill('Martina Ríos');
    await page.getByLabel(/^Fecha de nacimiento/).fill('1989-04-12');
    await page.getByRole('textbox', { name: 'DNI', exact: true }).fill('32874516');
    await page.getByLabel('Teléfono de contacto').fill('+54 261 555 0198');
    await page
      .getByRole('textbox', { name: 'Domicilio', exact: true })
      .fill('Chile 1143, Ciudad, Mendoza');
    await page.getByRole('textbox', { name: 'Correo electrónico', exact: true }).fill(email);
    await page.getByLabel(/^Contraseña/).fill(password);
    await page.getByLabel(/^Repetir contraseña/).fill(password);
    const registered = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/auth/register') && response.request().method() === 'POST',
    );
    await page
      .getByRole('button', { name: 'Crear cuenta y verificar correo', exact: true })
      .click();
    const response = await registered;
    expect(response.status()).toBe(202);
    const body = await response.json();
    expect(body).toMatchObject({
      status: 'EMAIL_CONFIGURATION_PENDING',
      emailVerificationRequired: true,
    });
    expect(body).not.toHaveProperty('id');
    await expect(
      page.getByRole('heading', { name: 'Confirmá tu correo', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(
        'Todavía no podemos enviarte el correo de confirmación. Tu cuenta quedará pendiente hasta que el servicio esté disponible. Podés solicitar otro envío más adelante.',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(page.getByText(/SMTP/i)).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Salir', exact: true })).not.toBeVisible();
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await screenshots(page, 'registro-correo-pendiente');
    await page.getByRole('button', { name: 'Volver a ingresar', exact: true }).click();
    await page.getByLabel(/^Contraseña/).fill(password);
    await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Confirmá tu correo', exact: true }),
    ).toBeVisible();
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await page.getByRole('button', { name: 'Volver a ingresar', exact: true }).click();
    await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
    const resetRequested = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/auth/password/request-reset') &&
        response.request().method() === 'POST',
    );
    await page
      .getByRole('button', { name: 'Solicitar enlace de recuperación', exact: true })
      .click();
    const resetResponse = await resetRequested;
    expect(resetResponse.status()).toBe(202);
    expect(await resetResponse.json()).toMatchObject({ status: 'EMAIL_CONFIGURATION_PENDING' });
    await expect(
      page.getByText(
        'Por el momento no podemos enviarte el correo para recuperar el acceso. Podés intentar de nuevo más adelante.',
        { exact: true },
      ),
    ).toBeVisible();
    await expect(page.getByText(/SMTP/i)).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Salir', exact: true })).not.toBeVisible();
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await screenshots(page, 'recuperacion-correo-pendiente');
  } finally {
    await context.close();
  }
});

test('sustitución documentada: conserva original, pausa revisión y necesita alternativa y presupuesto aceptados', async ({
  browser,
}) => {
  const contexts = await Promise.all(
    Array.from({ length: 3 }, () =>
      browser.newContext({ baseURL: test.info().project.use.baseURL }),
    ),
  );
  const [buyerContext, pharmacyContext, adminContext] = contexts;
  if (!buyerContext || !pharmacyContext || !adminContext)
    throw new Error('No se crearon las sesiones de sustitución.');
  const buyer = await buyerContext.newPage();
  const pharmacy = await pharmacyContext.newPage();
  const admin = await adminContext.newPage();
  try {
    await login(buyer);
    await login(pharmacy, 'Farmacéutico');
    await login(admin, 'Administrador');
    const catalog = await (await buyer.request.get('/api/catalog/products')).json();
    const original = catalog.find((x: { name: string }) => x.name === 'Paracetamol Nivalen 500 mg');
    const branches = await (await buyer.request.get('/api/catalog/branches')).json();
    const branch = branches.find((x: { name: string }) => x.name === 'Nivalis · Ciudad');
    const alternative = await send(admin.request, '/api/admin/products', {
      code: 'EMC-PAR-RIV-500-20',
      name: 'Paracetamol Rivadol 500 mg',
      presentation: original.presentation,
      requiresPrescription: false,
      coldChain: false,
      restricted: false,
      active: true,
      version: 0,
      metadata: {},
    });
    const csrf = await (await pharmacy.request.get('/api/auth/csrf')).json();
    const stocked = await pharmacy.request.put(
      `/api/branches/${branch.id}/stock/${alternative.id}`,
      {
        data: {
          quantity: 12,
          price: '3900.00',
          expectedVersion: 0,
          reason: 'Alta de existencias contadas de la marca alternativa en el escenario aislado.',
        },
        headers: { [csrf.headerName]: csrf.token },
      },
    );
    expect(stocked.ok(), await stocked.text()).toBeTruthy();
    let purchase = await send(buyer.request, '/api/purchases', {
      beneficiaryName: 'Lucía Fernández',
      beneficiaryBirthDate: '1990-01-01',
      thirdParty: false,
      mode: 'PICKUP',
      destinationLat: -32.889,
      destinationLon: -68.845,
      preferredBranchId: branch.id,
      items: [{ productId: original.id, quantity: 1 }],
    });
    await pharmacy.reload();
    await selectPurchase(pharmacy, purchase.id);
    let dialog = await dialogAction(pharmacy, 'Proponer sustitución');
    await dialog.getByRole('combobox', { name: 'Presentación alternativa' }).click();
    await pharmacy.getByRole('option', { name: new RegExp(`^${alternative.name}`) }).click();
    await dialog
      .getByLabel('Motivo de la alternativa propuesta')
      .fill(
        'Otra marca de la presentación solicitada disponible en la sucursal; el profesional documenta la propuesta y requiere aceptación expresa del comprador.',
      );
    await dialog
      .getByLabel('Evidencia de la propuesta de sustitución', { exact: true })
      .setInputFiles(evidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await dialog
      .getByRole('button', { name: 'Presentar alternativa al comprador', exact: true })
      .click();
    await expect(dialog).not.toBeVisible();
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    expect(purchase.substitutions).toHaveLength(1);
    expect(purchase.substitutions[0]).toMatchObject({
      status: 'PENDING',
      originalProductId: original.id,
      productId: alternative.id,
    });
    expect(
      purchase.orders.find((o: { status: string }) => o.status === 'PROPOSED').lines[0].productId,
    ).toBe(original.id);
    await expect(
      pharmacy.getByRole('button', { name: 'Revisar solicitud', exact: true }),
    ).not.toBeVisible();
    await buyer.reload();
    await selectPurchase(buyer, purchase.id);
    await expect(
      buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto', exact: true }),
    ).not.toBeVisible();
    await expect(
      buyer.getByRole('button', { name: 'Aceptar alternativa para cotizar', exact: true }),
    ).toBeVisible();
    await screenshots(buyer, 'sustitucion-decision-comprador');
    await buyer
      .getByRole('button', { name: 'Aceptar alternativa para cotizar', exact: true })
      .click();
    await expect(
      buyer.getByRole('button', { name: 'Aceptar alternativa para cotizar', exact: true }),
    ).not.toBeVisible();
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    expect(purchase.substitutions[0].status).toBe('ACCEPTED');
    expect(purchase.status).toBe('ROUTING');
    expect(
      purchase.orders.find((o: { status: string }) => o.status === 'PROPOSED').lines[0].productId,
    ).toBe(alternative.id);
    await pharmacy.reload();
    await selectPurchase(pharmacy, purchase.id);
    dialog = await dialogAction(pharmacy, 'Revisar solicitud');
    await dialog
      .getByLabel('Comprobaciones realizadas y motivo')
      .fill(
        'Alternativa aceptada por el comprador; presentación, cantidad, existencia física y precio comprobados.',
      );
    await dialog
      .getByRole('checkbox', {
        name: 'Verifiqué personalmente edad, identidad y autorización cuando corresponde',
      })
      .check();
    await dialog
      .getByRole('checkbox', {
        name: 'Confirmé existencias físicas, precio vigente y presentación solicitada',
      })
      .check();
    await dialog.getByLabel('Plazo estimado para estar listo (minutos)').fill('30');
    await confirm(pharmacy);
    await expect(buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' })).toBeVisible({
      timeout: 15000,
    });
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    expect(purchase.status).toBe('QUOTED');
    expect(purchase.total).toBe('3900.00');
    await buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto', exact: true }).click();
    dialog = buyer.getByRole('dialog');
    await expect(dialog.getByText(new RegExp(alternative.name))).toBeVisible();
    await dialog
      .getByRole('checkbox', {
        name: 'Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes, plazos y modalidad de esta propuesta',
      })
      .check();
    await dialog.getByRole('button', { name: 'Aceptar presupuesto', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    purchase = await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json();
    expect(purchase.status).toBe('ACCEPTED');
    expect(
      purchase.orders.find((o: { status: string }) => o.status === 'ACCEPTED').lines[0].productId,
    ).toBe(alternative.id);
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
  }
});

test('reposición por discrepancia: retorno documentado, aceptación explícita, nuevas unidades y retiro sin cobro', async ({
  browser,
}) => {
  const contexts = await Promise.all(
    Array.from({ length: 3 }, () =>
      browser.newContext({ baseURL: test.info().project.use.baseURL }),
    ),
  );
  const [buyerContext, pharmacyContext, driverContext] = contexts;
  if (!buyerContext || !pharmacyContext || !driverContext)
    throw new Error('No se crearon las sesiones de reposición.');
  const buyer = await buyerContext.newPage();
  const pharmacy = await pharmacyContext.newPage();
  const driver = await driverContext.newPage();
  const failures: string[] = [];
  for (const page of [buyer, pharmacy, driver])
    page.on('pageerror', (error) => failures.push(error.message));
  try {
    await login(buyer);
    await login(pharmacy, 'Farmacéutico');
    await login(driver, 'Cadete');
    const prepared = await readyDelivery(
      buyer,
      pharmacy,
      'REPOSICION',
      'Chile 1143, Ciudad, Mendoza',
    );
    const originalId = prepared.purchase.id;
    const originalOrderId = prepared.order.id;
    const productId = prepared.order.lines[0].productId;
    const readStock = async () =>
      (await (await pharmacy.request.get(`/api/branches/${prepared.branch.id}/stock`)).json()).find(
        (stock: { productId: string }) => stock.productId === productId,
      );
    const stockAfterOriginalDispatch = await readStock();
    const card = () =>
      driver.getByText(`Pedido ${originalOrderId.slice(0, 8).toUpperCase()}`).locator('..');
    await driver.reload();
    await card().getByRole('button', { name: 'Aceptar tarea', exact: true }).click();
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    await card().getByRole('button', { name: 'Recibí el paquete de la farmacia' }).click();
    await driver
      .getByRole('dialog')
      .getByLabel('Condiciones del paquete recibido')
      .fill('Paquete cerrado recibido físicamente de la farmacia; custodia registrada.');
    await driver
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirmar', exact: true })
      .click();
    await expect(driver.getByRole('dialog')).not.toBeVisible();
    await card().getByRole('button', { name: 'Informar incidencia', exact: true }).click();
    let dialog = driver.getByRole('dialog');
    await dialog.getByRole('combobox', { name: 'Tipo de incidencia' }).click();
    await driver
      .getByRole('option', { name: 'Diferencia en medicamentos o cantidades', exact: true })
      .click();
    await dialog
      .getByLabel('Qué ocurrió y qué necesita resolver la farmacia')
      .fill(
        'Antes de confirmar la recepción se detectó presentación distinta a la solicitada. Se retiene el paquete cerrado y se coordina retorno para evaluación profesional.',
      );
    await dialog.getByRole('button', { name: 'Confirmar', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await pharmacy.reload();
    await selectPurchase(pharmacy, originalId);
    dialog = await dialogAction(pharmacy, 'Registrar recepción física del retorno');
    await dialog
      .getByLabel('Constancia de custodia y estado del paquete retornado')
      .fill(
        'La farmacia recibe físicamente el paquete original e identifica la discrepancia de presentación. Producto segregado y sin reincorporación al stock.',
      );
    await dialog
      .getByLabel('Evidencia de recepción física del retorno', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await confirm(pharmacy);
    expect((await readStock()).quantity).toBe(stockAfterOriginalDispatch.quantity);
    dialog = await dialogAction(pharmacy, 'Proponer reposición sin cargo');
    await dialog.getByLabel('Unidades a reponer de Paracetamol Nivalen 500 mg').fill('1');
    await dialog
      .getByLabel('Motivo de la reposición')
      .fill(
        'Reposición de la presentación correcta por discrepancia comprobada, sin cobro adicional al comprador.',
      );
    await dialog
      .getByLabel('Evaluación física y fundamento de la disposición')
      .fill(
        'Paquete original retornado y segregado en farmacia. Se reservan unidades nuevas del stock disponible, sin reutilizar el producto original.',
      );
    await dialog.getByRole('combobox', { name: 'Modalidad de la reposición' }).click();
    await pharmacy.getByRole('option', { name: 'Retiro en farmacia', exact: true }).click();
    await dialog.getByLabel('Plazo de reposición (minutos)').fill('30');
    await dialog
      .getByLabel('Evidencia de la evaluación y propuesta de reposición', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await dialog
      .getByRole('button', { name: 'Presentar reposición al comprador', exact: true })
      .click();
    await expect(dialog).not.toBeVisible();
    let proposals = await (
      await buyer.request.get(`/api/orders/${originalOrderId}/replacements`)
    ).json();
    expect(proposals).toHaveLength(1);
    expect(proposals[0]).toMatchObject({
      status: 'PROPOSED',
      mode: 'PICKUP',
      originalDisposition: 'RETURNED',
      requiresPrescription: false,
      requiresAuthorization: false,
    });
    await buyer.reload();
    await selectPurchase(buyer, originalId);
    dialog = await dialogAction(buyer, 'Revisar y aceptar reposición');
    const identity = await (await buyer.request.get('/api/auth/me')).json();
    await dialog.getByLabel('Nombre de quien recibirá la reposición').fill(identity.name);
    await dialog.getByLabel('DNI de quien recibirá la reposición').fill(identity.dni);
    await dialog
      .getByRole('button', { name: 'Aceptar condiciones de reposición', exact: true })
      .click();
    await expect(dialog).not.toBeVisible();
    proposals = await (
      await buyer.request.get(`/api/orders/${originalOrderId}/replacements`)
    ).json();
    const replacementId = proposals[0].replacementPurchaseId;
    expect(proposals[0].status).toBe('ACCEPTED');
    expect(replacementId).toBeTruthy();
    let replacement = await (await buyer.request.get(`/api/purchases/${replacementId}`)).json();
    let replacementOrder = replacement.orders[0];
    expect(replacement.total).toBe('0.00');
    expect(replacementOrder.replacementReviewPending).toBe(true);
    expect(replacementOrder.originalPurchaseId).toBe(originalId);
    expect(replacementOrder.invoiceDocumentId).toBe(prepared.order.invoiceDocumentId);
    expect((await readStock()).quantity).toBe(stockAfterOriginalDispatch.quantity);
    expect((await readStock()).reservedQuantity).toBe(
      stockAfterOriginalDispatch.reservedQuantity + 1,
    );
    await pharmacy.reload();
    await selectPurchase(pharmacy, replacementId);
    await expect(pharmacy.getByRole('button', { name: 'Preparar', exact: true })).toBeDisabled();
    await expect(
      pharmacy.getByRole('button', { name: 'Verificar pago', exact: true }),
    ).not.toBeVisible();
    await pharmacy
      .getByRole('button', { name: 'Abrir compra original y revisión de reposición', exact: true })
      .click();
    dialog = await dialogAction(pharmacy, 'Revisar documentación de reposición');
    await dialog
      .getByLabel('Comprobaciones de la reposición')
      .fill(
        'Reposición propia sin receta ni nota delegada. Identidad, presentación correcta, cantidad y nuevas existencias comprobadas; pedido original conserva factura y custodia del retorno.',
      );
    await dialog
      .getByLabel('Evidencia de condiciones para la reposición', { exact: true })
      .setInputFiles(conservationEvidence);
    await expect(dialog.getByText('Archivo guardado')).toBeVisible();
    await dialog
      .getByRole('button', { name: 'Confirmar revisión de reposición', exact: true })
      .click();
    await expect(dialog).not.toBeVisible();
    proposals = await (
      await buyer.request.get(`/api/orders/${originalOrderId}/replacements`)
    ).json();
    expect(proposals[0].status).toBe('READY');
    await pharmacy.getByRole('button', { name: 'Abrir compra de reposición', exact: true }).click();
    await expect(pharmacy.getByRole('button', { name: 'Preparar', exact: true })).toBeEnabled();
    await expect(
      pharmacy.getByRole('button', { name: 'Reemplazar factura', exact: true }),
    ).not.toBeVisible();
    await screenshots(pharmacy, 'farmacia-reposicion-revisada');
    await dialogAction(pharmacy, 'Preparar');
    await confirm(pharmacy);
    dialog = await dialogAction(pharmacy, 'Registrar dispensación');
    await dialog
      .getByLabel('Condiciones comprobadas', { exact: true })
      .fill(
        'Nuevas unidades de la presentación correcta, envase íntegro, cantidad y lote comprobados. Producto retornado segregado.',
      );
    await dialog
      .getByRole('checkbox', {
        name: 'Comprobé presentación, cantidad y condiciones; verifiqué la receta cuando corresponde',
      })
      .check();
    await confirm(pharmacy);
    replacement = await (await buyer.request.get(`/api/purchases/${replacementId}`)).json();
    replacementOrder = replacement.orders[0];
    expect((await readStock()).quantity).toBe(stockAfterOriginalDispatch.quantity - 1);
    const receipt = await (
      await buyer.request.get(`/api/orders/${replacementOrder.id}/receipt-code`)
    ).json();
    dialog = await dialogAction(pharmacy, 'Confirmar retiro');
    await dialog.getByLabel('Nombre de quien retira').fill(identity.name);
    await dialog.getByLabel('DNI comprobado de quien retira').fill(identity.dni);
    await dialog.getByLabel('Código de recepción del comprador').fill(receipt.code);
    await dialog
      .getByLabel('Constancia de recepción')
      .fill(
        'El titular recibe las nuevas unidades correctas de la reposición. Documento, código, presentación y cantidad comprobados presencialmente.',
      );
    await dialog
      .getByRole('checkbox', {
        name: 'Medicamentos, cantidades y presentación coinciden con el pedido',
      })
      .check();
    await dialog
      .getByRole('checkbox', { name: 'Verifiqué presencialmente al receptor y su autorización' })
      .check();
    await confirm(pharmacy);
    await expect
      .poll(
        async () =>
          (await (await buyer.request.get(`/api/purchases/${replacementId}`)).json()).status,
      )
      .toBe('COMPLETED');
    proposals = await (
      await buyer.request.get(`/api/orders/${originalOrderId}/replacements`)
    ).json();
    expect(proposals[0].status).toBe('FULFILLED');
    expect(failures).toEqual([]);
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
  }
});
