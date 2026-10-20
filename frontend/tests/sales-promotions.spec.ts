import { test, expect, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { money, type Actor } from '../src/api';
import type { Branch, Product } from '../src/catalog/types';
import type { Promotion } from '../src/promotions/types';
import { mendozaDateTime } from '../src/promotions/types';
import type { SalesReport } from '../src/reports/types';
import { businessDate } from '../src/reports/presentation';
import type { Purchase } from '../src/purchases/types';

const evidenceDirectory = fileURLToPath(new URL('../../.runtime/commerce-qa/', import.meta.url));

// The runner creates and owns a separate database and process. Never run mutations on the demo.
test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? 'http://127.0.0.1:8082').origin).toBe(
    'http://127.0.0.1:8083',
  );
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
  mkdirSync(evidenceDirectory, { recursive: true });
});

async function login(
  page: Page,
  profile: 'Administrador de farmacia' | 'Comprador' | 'Farmacéutico',
) {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  const response = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const authenticated = await response;
  expect(authenticated.ok(), await authenticated.text()).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  const actor = (await (await page.request.get('/api/auth/me')).json()) as Actor;
  expect(actor.roles).toContain(
    profile === 'Comprador'
      ? 'BUYER'
      : profile === 'Farmacéutico'
        ? 'PHARMACIST'
        : 'PHARMACY_ADMIN',
  );
  return actor;
}

async function send<T>(page: Page, path: string, body: unknown): Promise<T> {
  const token = await (await page.request.get('/api/auth/csrf')).json();
  const response = await page.request.post(path, {
    data: body,
    headers: { [token.headerName]: token.token, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
  return response.json();
}

function promotionCard(page: Page, title: string) {
  return page.locator('.MuiCard-root').filter({
    has: page.getByRole('heading', { name: title, exact: true }),
  });
}

async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: `${evidenceDirectory}/${name}.png`, fullPage: true });
}

async function waitForMapTiles(map: Locator) {
  await expect
    .poll(
      () =>
        map.evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          return [...element.querySelectorAll<HTMLImageElement>('img.leaflet-tile-loaded')].filter(
            (tile) => {
              const tileBounds = tile.getBoundingClientRect();
              return (
                tile.complete &&
                tile.naturalWidth > 0 &&
                tileBounds.right > bounds.left &&
                tileBounds.left < bounds.right &&
                tileBounds.bottom > bounds.top &&
                tileBounds.top < bounds.bottom
              );
            },
          ).length;
        }),
      { timeout: 15000, message: 'El mapa debe mostrar sus imágenes antes de guardar la captura.' },
    )
    .toBeGreaterThanOrEqual(4);
}

async function expectMapContentsContained(map: Locator, selector: string, count: number) {
  await expect(map.locator(selector)).toHaveCount(count);
  await expect
    .poll(
      () =>
        map.evaluate((element, selector) => {
          const bounds = element.getBoundingClientRect();
          return [...element.querySelectorAll(selector)].every((item) => {
            const itemBounds = item.getBoundingClientRect();
            return (
              itemBounds.left >= bounds.left - 1 &&
              itemBounds.right <= bounds.right + 1 &&
              itemBounds.top >= bounds.top - 1 &&
              itemBounds.bottom <= bounds.bottom + 1
            );
          });
        }, selector),
      { message: 'El contenido del mapa debe quedar completamente visible dentro de sus bordes.' },
    )
    .toBe(true);
}

test('ventas y promociones: publicación por sucursales, destacado, mapa y pausa', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyerContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  const buyer = await buyerContext.newPage();
  const failures: string[] = [];
  admin.on('pageerror', (error) => failures.push(error.stack ?? error.message));
  buyer.on('pageerror', (error) => failures.push(error.stack ?? error.message));
  const title = `Beneficio Nivalis QA ${Date.now()}`;
  try {
    const actor = await login(admin, 'Administrador de farmacia');
    const branchesResponse = await admin.request.get('/api/pharmacy/promotion-branches');
    expect(branchesResponse.ok()).toBeTruthy();
    const branches = (await branchesResponse.json()) as Branch[];
    expect(branches.map((branch) => branch.name).sort()).toEqual([
      'Nivalis · Ciudad',
      'Nivalis · Dorrego',
    ]);
    expect(branches.every((branch) => actor.branchIds.includes(branch.id))).toBeTruthy();
    await admin.getByRole('link', { name: 'Promociones', exact: false }).click();
    await expect(
      admin.getByRole('heading', { name: 'Gestionar promociones', exact: true }),
    ).toBeVisible();
    await admin.getByRole('button', { name: 'Nueva promoción', exact: true }).click();
    let editor = admin.getByRole('dialog');
    await editor.getByRole('textbox', { name: 'Título de la promoción', exact: true }).fill(title);
    await editor
      .getByRole('textbox', { name: 'Beneficio y condiciones', exact: true })
      .fill(
        '10% en productos de cuidado personal seleccionados. Pago en efectivo, sujeto a stock.',
      );
    await editor
      .getByLabel(/^Inicio \(hora de Mendoza\)/)
      .fill(mendozaDateTime(new Date(Date.now() - 86400000)));
    await editor
      .getByLabel(/^Finalización \(hora de Mendoza\)/)
      .fill(mendozaDateTime(new Date(Date.now() + 7 * 86400000)));
    for (const branch of branches) {
      await editor
        .getByRole('checkbox', { name: `${branch.name} · ${branch.address}`, exact: true })
        .check();
    }
    await editor.getByRole('checkbox', { name: 'Mostrar como destacada', exact: true }).check();
    await editor
      .getByRole('checkbox', { name: 'Publicada (visible durante su vigencia)', exact: true })
      .check();
    await editor
      .getByRole('textbox', { name: 'Motivo del cambio', exact: true })
      .fill('Campaña de ejemplo para ambas sucursales Nivalis.');
    const created = admin.waitForResponse(
      (response) =>
        response.url().endsWith('/api/pharmacy/promotions') &&
        response.request().method() === 'POST',
    );
    await editor.getByRole('button', { name: 'Guardar promoción', exact: true }).click();
    const createdResponse = await created;
    expect(createdResponse.ok(), await createdResponse.text()).toBeTruthy();
    const promotion = (await createdResponse.json()) as Promotion;
    expect(promotion.featured).toBe(true);
    expect(promotion.branchIds.sort()).toEqual(branches.map((branch) => branch.id).sort());
    await expect(editor).not.toBeVisible();
    await expect(promotionCard(admin, title).getByText('Vigente', { exact: true })).toBeVisible();
    await screenshot(admin, 'promociones-gestion-desktop');
    await admin.reload();
    await expect(promotionCard(admin, title)).toBeVisible();

    await login(buyer, 'Comprador');
    await expect(
      buyer.getByRole('heading', { name: 'Promociones destacadas', exact: true }),
    ).toBeVisible();
    const card = promotionCard(buyer, title);
    await expect(card).toBeVisible();
    await expect(card.getByText(promotion.chainName, { exact: true })).toBeVisible();
    for (const branch of branches) {
      await expect(
        card.getByText(`${branch.name} · ${branch.address}`, { exact: true }),
      ).toBeVisible();
    }
    await screenshot(buyer, 'promocion-destacada-desktop');
    await card.getByRole('button', { name: 'Ver promoción y mapa', exact: true }).click();
    const detail = buyer.getByRole('dialog');
    await expect(detail.getByText(`Cadena / farmacia: ${promotion.chainName}`)).toBeVisible();
    const map = detail.getByRole('region', { name: 'Mapa de sucursales participantes' });
    await map.scrollIntoViewIfNeeded();
    await expect(map).toBeVisible();
    await expect(detail.getByRole('button', { name: 'Usar mi ubicación actual' })).toHaveCount(0);
    await expect(
      detail.getByText('Todavía no confirmaste un punto.', { exact: false }),
    ).toHaveCount(0);
    for (const branch of branches) {
      await expect(detail.getByText(branch.address, { exact: true })).toBeVisible();
      await expect(
        map.getByRole('button', { name: `Sucursal ${branch.name}`, exact: true }),
      ).toBeVisible();
    }
    await expect(map.locator('.leaflet-marker-pane .emedicapp-branch-marker')).toHaveCount(2);
    await map.getByRole('button', { name: `Sucursal ${branches[0]!.name}`, exact: true }).click();
    await expect(
      map.locator('.leaflet-popup-content').getByText(branches[0]!.address, { exact: true }),
    ).toBeVisible();
    await waitForMapTiles(map);
    await screenshot(buyer, 'promocion-mapa-desktop');
    await detail
      .getByRole('button', { name: `Ubicar ${branches[0]!.name} en el mapa`, exact: true })
      .click();
    await map.scrollIntoViewIfNeeded();
    await expect(map.locator('.leaflet-marker-pane .emedicapp-branch-marker')).toHaveCount(1);
    await expect(
      map.getByRole('button', { name: `Sucursal ${branches[0]!.name}`, exact: true }),
    ).toBeVisible();
    await detail
      .getByRole('button', { name: 'Mostrar todas las sucursales participantes', exact: true })
      .click();
    await expect(map.locator('.leaflet-marker-pane .emedicapp-branch-marker')).toHaveCount(2);
    const keyboardMarker = map.getByRole('button', {
      name: `Sucursal ${branches[1]!.name}`,
      exact: true,
    });
    await keyboardMarker.focus();
    await keyboardMarker.press('Enter');
    await expect(
      map.locator('.leaflet-popup-content').getByText(branches[1]!.address, { exact: true }),
    ).toBeVisible();
    await map.locator('.leaflet-popup-close-button').click();
    await expect(map.locator('.leaflet-popup')).toHaveCount(0);
    await buyer.setViewportSize({ width: 390, height: 844 });
    await map.scrollIntoViewIfNeeded();
    await expect(map).toBeVisible();
    await expectMapContentsContained(map, '.leaflet-marker-pane .emedicapp-branch-marker', 2);
    await waitForMapTiles(map);
    await screenshot(buyer, 'promocion-mapa-mobile');
    await keyboardMarker.focus();
    await keyboardMarker.press('Enter');
    await expect(
      map.locator('.leaflet-popup-content').getByText(branches[1]!.address, { exact: true }),
    ).toBeVisible();
    await expectMapContentsContained(map, '.leaflet-popup', 1);
    await waitForMapTiles(map);
    await screenshot(buyer, 'promocion-popup-mobile');
    await detail.getByRole('button', { name: 'Cerrar', exact: true }).click();
    // Keep the detail open on the full promotions page while the pharmacy pauses it.
    // The full page remains mounted when its collection becomes empty.
    await buyer.getByRole('link', { name: 'Promociones', exact: false }).click();
    await expect(buyer.getByRole('heading', { name: 'Promociones', exact: true })).toBeVisible();
    await promotionCard(buyer, title)
      .getByRole('button', { name: 'Ver promoción y mapa', exact: true })
      .click();
    await expect(detail).toBeVisible();

    expect((await buyer.request.get('/api/pharmacy/promotions')).status()).toBe(403);
    expect((await buyer.request.get('/api/pharmacy/promotion-branches')).status()).toBe(403);
    expect((await buyer.request.get('/api/reports/sales')).status()).toBe(403);
    const csrf = await (await buyer.request.get('/api/auth/csrf')).json();
    const rejected = await buyer.request.post('/api/pharmacy/promotions', {
      headers: { [csrf.headerName]: csrf.token },
      data: {
        title: `${title} no autorizada`,
        description: promotion.description,
        startsAt: promotion.startsAt,
        endsAt: promotion.endsAt,
        featured: true,
        active: true,
        branchIds: promotion.branchIds,
        version: 0,
        reason: 'Intento con cuenta compradora.',
      },
    });
    expect(rejected.status()).toBe(403);

    await promotionCard(admin, title)
      .getByRole('button', { name: 'Modificar promoción', exact: true })
      .click();
    editor = admin.getByRole('dialog');
    await editor
      .getByRole('checkbox', { name: 'Publicada (visible durante su vigencia)', exact: true })
      .uncheck();
    await editor
      .getByRole('textbox', { name: 'Motivo del cambio', exact: true })
      .fill('Pausa de la campaña de ejemplo.');
    const paused = admin.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/pharmacy/promotions/${promotion.id}`) &&
        response.request().method() === 'PUT',
    );
    await editor.getByRole('button', { name: 'Guardar promoción', exact: true }).click();
    const pausedResponse = await paused;
    expect(pausedResponse.ok(), await pausedResponse.text()).toBeTruthy();
    await expect(editor).not.toBeVisible();
    await expect(promotionCard(admin, title).getByText('Pausada', { exact: true })).toBeVisible();
    const refreshed = buyer.waitForResponse((response) =>
      response.url().endsWith('/api/catalog/promotions'),
    );
    await buyer.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
    expect((await refreshed).ok()).toBe(true);
    await expect(detail).not.toBeVisible();
    await expect(
      buyer.getByText('Todavía no hay promociones vigentes', { exact: true }),
    ).toBeVisible();
    await expect(promotionCard(buyer, title)).toHaveCount(0);
    const current = (await (
      await buyer.request.get('/api/catalog/promotions')
    ).json()) as Promotion[];
    expect(current.some((entry) => entry.id === promotion.id)).toBe(false);
    expect(failures).toEqual([]);
  } finally {
    await adminContext.close();
    await buyerContext.close();
  }
});

test('ventas y promociones: reportes diarios, semanales, mensuales y anuales con alcance', async ({
  page,
}) => {
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.stack ?? error.message));
  const actor = await login(page, 'Administrador de farmacia');
  await page.getByRole('link', { name: 'Ventas y cobros', exact: false }).click();
  await expect(
    page.getByRole('heading', { name: 'Reportes de ventas', exact: true }),
  ).toBeVisible();
  await page.getByLabel('Fecha de referencia', { exact: true }).fill('2001-02-15');
  await expect(
    page.getByText('Sin movimientos verificados en este período', { exact: true }),
  ).toBeVisible();
  const periods = [
    { label: 'Diario', value: 'DAY', buckets: 24 },
    { label: 'Semanal', value: 'WEEK', buckets: 7 },
    { label: 'Mensual', value: 'MONTH', buckets: 28 },
    { label: 'Anual', value: 'YEAR', buckets: 12 },
  ];
  let lastReport: SalesReport | undefined;
  for (const period of periods) {
    const loaded = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/reports/sales' &&
        url.searchParams.get('period') === period.value &&
        url.searchParams.get('date') === '2001-02-15'
      );
    });
    await page.getByRole('combobox', { name: 'Período', exact: true }).click();
    await page.getByRole('option', { name: period.label, exact: true }).click();
    const response = await loaded;
    expect(response.ok(), await response.text()).toBeTruthy();
    lastReport = (await response.json()) as SalesReport;
    expect(lastReport.period).toBe(period.value);
    expect(lastReport.date).toBe('2001-02-15');
    expect(lastReport.timeZone).toBe('America/Argentina/Mendoza');
    expect(lastReport.branches.map((branch) => branch.name).sort()).toEqual([
      'Nivalis · Ciudad',
      'Nivalis · Dorrego',
    ]);
    expect(lastReport.branches.every((branch) => actor.branchIds.includes(branch.id))).toBe(true);
    expect(lastReport.buckets).toHaveLength(period.buckets);
    expect(lastReport.totals).toEqual([]);
    expect(lastReport.movementCount).toBe(0);
    await expect(
      page.getByText('Sin movimientos verificados en este período', { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Período', exact: true })).toHaveText(
      period.label,
    );
  }
  const branch = lastReport!.branches.find((branch) => branch.name === 'Nivalis · Ciudad')!;
  const filtered = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === '/api/reports/sales' && url.searchParams.get('branchId') === branch.id;
  });
  await page.getByRole('combobox', { name: 'Sucursal', exact: true }).click();
  await page
    .getByRole('option', { name: new RegExp(`${branch.name} · ${branch.address}`) })
    .click();
  const filteredResponse = await filtered;
  expect(filteredResponse.ok(), await filteredResponse.text()).toBeTruthy();
  const scoped = (await filteredResponse.json()) as SalesReport;
  expect(scoped.byBranch.every((row) => row.branchId === branch.id)).toBe(true);
  expect(scoped.branches).toHaveLength(2);
  await expect(
    page.getByText('Sin movimientos verificados en este período', { exact: true }),
  ).toBeVisible();
  await screenshot(page, 'ventas-reporte-desktop');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('combobox', { name: 'Sucursal', exact: true })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await screenshot(page, 'ventas-reporte-mobile');

  const publicBranches = (await (
    await page.request.get('/api/catalog/branches')
  ).json()) as Branch[];
  const foreign = publicBranches.find((candidate) => !actor.branchIds.includes(candidate.id))!;
  expect(foreign).toBeTruthy();
  expect(
    (
      await page.request.get(
        `/api/reports/sales?period=MONTH&date=2001-02-15&branchId=${foreign.id}`,
      )
    ).status(),
  ).toBe(403);
  expect(failures).toEqual([]);
});

test('ventas y promociones: un cobro real verificado aparece en resúmenes y movimientos', async ({
  browser,
}) => {
  const contexts = await Promise.all(
    ['buyer', 'pharmacist', 'manager'].map(() =>
      browser.newContext({ baseURL: test.info().project.use.baseURL }),
    ),
  );
  const buyer = await contexts[0]!.newPage();
  const pharmacist = await contexts[1]!.newPage();
  const manager = await contexts[2]!.newPage();
  const failures: string[] = [];
  for (const page of [buyer, pharmacist, manager]) {
    page.on('pageerror', (error) => failures.push(error.stack ?? error.message));
  }
  try {
    const customer = await login(buyer, 'Comprador');
    await login(pharmacist, 'Farmacéutico');
    await login(manager, 'Administrador de farmacia');
    const products = (await (await buyer.request.get('/api/catalog/products')).json()) as Product[];
    const branches = (await (await buyer.request.get('/api/catalog/branches')).json()) as Branch[];
    const product = products.find((item) => item.name === 'Paracetamol Nivalen 500 mg')!;
    const branch = branches.find((item) => item.name === 'Nivalis · Ciudad')!;
    expect(product.requiresPrescription).toBe(false);
    const date = businessDate();
    const reportUrl = `/api/reports/sales?period=DAY&date=${date}&branchId=${branch.id}`;
    const beforeResponse = await manager.request.get(reportUrl);
    expect(beforeResponse.ok()).toBe(true);
    const before = (await beforeResponse.json()) as SalesReport;
    let purchase = await send<Purchase>(buyer, '/api/purchases', {
      beneficiaryName: customer.name,
      beneficiaryBirthDate: customer.birthDate,
      thirdParty: false,
      mode: 'PICKUP',
      destinationLat: branch.latitude,
      destinationLon: branch.longitude,
      preferredBranchId: branch.id,
      items: [{ productId: product.id, quantity: 1 }],
    });
    let order = purchase.orders.find((candidate) => candidate.status === 'PROPOSED')!;
    expect(order.branchId).toBe(branch.id);
    expect(order.highValueRequired).toBe(false);
    const orderId = order.id;
    purchase = await send<Purchase>(pharmacist, `/api/orders/${orderId}/review`, {
      expectedVersion: order.version,
      decision: 'APPROVE',
      reason: 'DEMO: revisión de un producto de venta libre para verificar reportes.',
      authorizationChecked: true,
      originVerified: false,
      lines: [{ productId: product.id, coverageAmount: '0.00', authorizedQuantity: 1 }],
    });
    expect(purchase.status).toBe('QUOTED');
    purchase = await send<Purchase>(buyer, `/api/purchases/${purchase.id}/accept`, {
      expectedVersion: purchase.version,
    });
    order = purchase.orders.find((candidate) => candidate.id === orderId)!;
    const reference = `QA-REPORTE-${Date.now()}`;
    const csrf = await (await buyer.request.get('/api/auth/csrf')).json();
    const uploaded = await buyer.request.post('/api/documents', {
      multipart: {
        kind: 'EVIDENCIA',
        file: {
          name: 'comprobante-pago.pdf',
          mimeType: 'application/pdf',
          buffer: readFileSync(new URL('./fixtures/comprobante-pago.pdf', import.meta.url)),
        },
      },
      headers: { [csrf.headerName]: csrf.token },
    });
    expect(uploaded.ok(), await uploaded.text()).toBe(true);
    const evidenceDocumentId = (await uploaded.json()).id as string;
    purchase = await send<Purchase>(buyer, `/api/orders/${orderId}/payment-report`, {
      expectedVersion: order.version,
      reference,
      evidenceDocumentId,
    });
    order = purchase.orders.find((candidate) => candidate.id === orderId)!;
    const pending = (await (await manager.request.get(reportUrl)).json()) as SalesReport;
    // An uploaded receipt does not count as money until the pharmacy verifies funds.
    expect(pending.totals).toEqual(before.totals);
    expect(pending.movements.some((movement) => movement.reference === reference)).toBe(false);
    const amount = order.outstandingAmount!;
    expect(amount).toBeTruthy();
    purchase = await send<Purchase>(pharmacist, `/api/orders/${orderId}/payment`, {
      expectedVersion: order.version,
      reference,
      evidenceDocumentId,
      verifiedExternally: true,
      amount,
      currency: order.currency,
      method: 'TRANSFER',
    });
    expect(purchase.status).toBe('PAID');
    const afterResponse = await manager.request.get(reportUrl);
    expect(afterResponse.ok(), await afterResponse.text()).toBe(true);
    const after = (await afterResponse.json()) as SalesReport;
    const movement = after.movements.find((candidate) => candidate.reference === reference)!;
    expect(movement).toMatchObject({ kind: 'PAYMENT', orderId, branchId: branch.id, amount });
    const total = after.totals.find((candidate) => candidate.currency === order.currency)!;
    const previous = before.totals.find((candidate) => candidate.currency === order.currency);
    const cents = (value: string) => {
      const [whole = '0', fraction = ''] = value.split('.');
      return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    };
    expect(cents(total.gross) - cents(previous?.gross ?? '0')).toBe(cents(amount));
    expect(total.paymentCount).toBe((previous?.paymentCount ?? 0) + 1);
    expect(total.paidOrders).toBe((previous?.paidOrders ?? 0) + 1);

    await manager.getByRole('link', { name: 'Ventas y cobros', exact: false }).click();
    await manager.getByLabel('Fecha de referencia', { exact: true }).fill(date);
    await manager.getByRole('combobox', { name: 'Sucursal', exact: true }).click();
    await manager.getByRole('option', { name: new RegExp(branch.name) }).click();
    const daily = manager.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/reports/sales' &&
        url.searchParams.get('period') === 'DAY' &&
        url.searchParams.get('branchId') === branch.id
      );
    });
    await manager.getByRole('combobox', { name: 'Período', exact: true }).click();
    await manager.getByRole('option', { name: 'Diario', exact: true }).click();
    expect((await daily).ok()).toBe(true);
    await expect(
      manager
        .getByText('Cobrado', { exact: true })
        .first()
        .locator('..')
        .getByText(money(total.gross, total.currency), { exact: true }),
    ).toBeVisible();
    const movements = manager.getByRole('table', { name: 'Movimientos verificados de ventas' });
    const row = movements.getByRole('row').filter({ hasText: reference });
    await expect(row).toContainText(orderId);
    await expect(row).toContainText(money(amount, order.currency));
    await expect(row).toContainText('Cobro');
    const hourly = manager.getByRole('table', { name: 'Ventas por fecha' });
    await expect(hourly.getByRole('columnheader', { name: 'Hora', exact: true })).toBeVisible();
    await expect(hourly.getByRole('row')).toHaveCount(25);
    await expect(
      manager
        .getByRole('table', { name: 'Ventas por sucursal' })
        .getByText(branch.name, { exact: true }),
    ).toBeVisible();
    await screenshot(manager, 'ventas-con-cobro-desktop');
    await manager.setViewportSize({ width: 390, height: 844 });
    await expect
      .poll(() => manager.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await screenshot(manager, 'ventas-con-cobro-mobile');
    await manager.setViewportSize({ width: 1440, height: 1000 });
    const annual = manager.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === '/api/reports/sales' && url.searchParams.get('period') === 'YEAR';
    });
    await manager.getByRole('combobox', { name: 'Período', exact: true }).click();
    await manager.getByRole('option', { name: 'Anual', exact: true }).click();
    const annualResponse = await annual;
    expect(annualResponse.ok(), await annualResponse.text()).toBe(true);
    const annualReport = (await annualResponse.json()) as SalesReport;
    expect(annualReport.buckets).toHaveLength(12);
    expect(annualReport.movements.some((entry) => entry.reference === reference)).toBe(true);
    await expect(
      manager.getByRole('heading', { name: 'Detalle por mes', exact: true }),
    ).toBeVisible();
    await expect(
      manager.getByRole('table', { name: 'Ventas por fecha' }).getByRole('row'),
    ).toHaveCount(13);
    await expect(movements.getByRole('row').filter({ hasText: reference })).toBeVisible();
    expect(failures).toEqual([]);
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
  }
});
