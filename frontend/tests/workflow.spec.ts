import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

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
async function login(page: Page, profile = 'Comprador') {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
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
  await buyer.getByLabel('Cantidad de Paracetamol Nivalen 500 mg', { exact: true }).fill('1');
  const created = buyer.waitForResponse(
    (r) => r.url().endsWith('/api/purchases') && r.request().method() === 'POST',
  );
  await buyer.getByRole('button', { name: 'Solicitar propuesta a la farmacia' }).click();
  const purchase = await (await created).json();
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
  await confirm(pharmacy);
  await expect(buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' })).toBeVisible({
    timeout: 15000,
  });
  await buyer.getByRole('button', { name: 'Revisar y aceptar presupuesto' }).click();
  await buyer
    .getByRole('checkbox', {
      name: 'Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes y modalidad de esta propuesta',
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
    .getByRole('checkbox', { name: 'Comprobé que la farmacia recibió el importe completo' })
    .check();
  await confirm(pharmacy);
  dialog = await dialogAction(pharmacy, 'Adjuntar factura');
  await dialog.getByLabel('Número/referencia de factura').fill('0003-00001847');
  await dialog
    .getByLabel('Factura emitida por la farmacia', { exact: true })
    .setInputFiles(invoiceEvidence);
  await expect(dialog.getByText('Archivo guardado')).toBeVisible();
  await confirm(pharmacy);
  await dialogAction(pharmacy, 'Preparar');
  await confirm(pharmacy);
  await expect(
    pharmacy.getByRole('button', { name: 'Registrar dispensación', exact: true }),
  ).toBeEnabled();
  await pharmacy.screenshot({ path: 'test-results/farmacia-preparacion.png', fullPage: true });
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
  await dialog
    .getByRole('checkbox', { name: 'Verifiqué presencialmente al receptor y su autorización' })
    .check();
  await dialog
    .getByLabel('Constancia de recepción')
    .fill('DEMO: retiro presencial ficticio comprobado.');
  await confirm(pharmacy);
  await expect
    .poll(
      async () => (await (await buyer.request.get(`/api/purchases/${purchase.id}`)).json()).status,
    )
    .toBe('COMPLETED');
  await buyer.reload();
  await selectPurchase(buyer, purchase.id);
  await buyer.screenshot({ path: 'test-results/compra-completada.png', fullPage: true });
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
    destinationAddress: '9 de Julio 1248, departamento 3, Ciudad, Mendoza',
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
    lines: [{ productId: product.id, coverageAmount: '0.00', authorizedQuantity: 1 }],
  });
  purchase = await send(buyer.request, `/api/purchases/${purchase.id}/accept`, {
    expectedVersion: purchase.version,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  const payment = await upload(buyer.request, 'EVIDENCIA');
  purchase = await send(buyer.request, `/api/orders/${order.id}/payment-report`, {
    expectedVersion: order.version,
    reference: 'NV-00874321-ENTREGA',
    evidenceDocumentId: payment,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/payment`, {
    expectedVersion: order.version,
    reference: 'NV-00874321-ENTREGA',
    evidenceDocumentId: payment,
    verifiedExternally: true,
  });
  order = purchase.orders.find((o: { id: string }) => o.id === order.id);
  const invoice = await upload(pharmacy.request, 'FACTURA', branch.id, invoiceEvidence);
  purchase = await send(pharmacy.request, `/api/orders/${order.id}/invoice`, {
    expectedVersion: order.version,
    reference: '0003-00001848',
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
  const drivers = await (await pharmacy.request.get(`/api/branches/${branch.id}/drivers`)).json();
  await send(pharmacy.request, `/api/orders/${order.id}/assign-driver`, {
    expectedVersion: order.version,
    driverId: drivers[0].id,
  });
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
  await driver.screenshot({ path: 'test-results/entrega-completada.png', fullPage: true });
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
  await page.screenshot({ path: 'test-results/panel-administracion.png', fullPage: true });
  for (const tab of [
    'Productos',
    'Cadenas',
    'Sucursales',
    'Planes',
    'Suscripciones',
    'Miembros',
    'Configuración',
    'Lotes',
  ]) {
    await page.getByRole('tab', { name: tab, exact: true }).click();
    await expect(page.getByRole('tab', { name: tab, exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  }
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
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
  await buyer.getByLabel('Buscar por nombre o presentación').fill('Immunavia');
  await buyer.getByLabel('Cantidad de Adalimumab Immunavia 40 mg', { exact: true }).fill('1');
  await buyer
    .getByLabel('Receta externa, si corresponde', { exact: true })
    .setInputFiles(fixture('receta-externa.pdf'));
  await expect(buyer.getByText('Archivo guardado', { exact: true })).toHaveCount(2);
  await buyer.getByRole('combobox', { name: 'Cómo querés cotizar los medicamentos' }).click();
  await buyer
    .getByRole('option', { name: 'Solicitar cobertura de obra social o prepaga', exact: true })
    .click();
  await buyer.getByLabel('Obra social o prepaga del beneficiario').fill('Previsión Andina');
  await buyer.getByLabel('Plan, si corresponde').fill('Integral');
  await buyer.getByLabel('Número de afiliado del beneficiario').fill('000458721');
  await buyer
    .getByLabel('Credencial de obra social o prepaga', { exact: true })
    .setInputFiles(fixture('credencial-cobertura.pdf'));
  await expect(buyer.getByText('Archivo guardado', { exact: true })).toHaveCount(3);
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
      name: 'Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes y modalidad de esta propuesta',
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
    .getByRole('checkbox', { name: 'Comprobé que la farmacia recibió el importe completo' })
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
  expect(purchase.orders[0].refunds[0].amount).toBe('1500000.00');
  expect(purchase.orders[0].refunds[0].status).toBe('REQUESTED');
  const released = await (await first.request.get(`/api/branches/${center.id}/stock`)).json();
  expect(
    released.find((row: { productId: string }) => row.productId === highValueProduct.id)
      .availableQuantity,
  ).toBe(availableBefore);
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
