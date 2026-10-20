import { test, expect, type Page } from '@playwright/test';
import type { Product } from '../src/catalog/types';
import type { Purchase } from '../src/purchases/types';

// The workflow runner owns a fresh local DEMO database; never create purchases in operation.
test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

async function openMedicines(page: Page) {
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({ latitude: -32.889, longitude: -68.845 });
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Comprador ·/ }).click();
  const loggedIn = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  expect((await loggedIn).ok()).toBeTruthy();
  await page.getByRole('button', { name: 'Nueva compra', exact: true }).click();
  await page.getByRole('combobox', { name: 'Cómo querés recibir la compra' }).click();
  await page.getByRole('option', { name: 'Retiro en farmacia', exact: true }).click();
  await page.getByRole('button', { name: 'Usar mi ubicación actual' }).click();
  await expect(page.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await page.getByRole('combobox', { name: 'Farmacia de preferencia' }).click();
  await page.getByRole('option', { name: /^Nivalis · Ciudad/ }).click();
  await page.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
}

test('Incidencias farmacia - medicamentos recupera la búsqueda, conserva envases y crea la compra completa', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await openMedicines(page);
  const search = page.getByRole('textbox', {
    name: 'Buscar por nombre o presentación',
    exact: true,
  });
  const basket = page.getByRole('region', { name: 'Productos seleccionados', exact: true });
  const productsResponse = await page.request.get('/api/catalog/products');
  expect(productsResponse.ok()).toBeTruthy();
  const products = (await productsResponse.json()) as Product[];
  const paracetamol = products.find((x) => x.name === 'Paracetamol Nivalen 500 mg')!;
  const ibuprofen = products.find((x) => x.name === 'Ibuprofeno Relivex 400 mg')!;
  expect(paracetamol).toBeTruthy();
  expect(ibuprofen).toBeTruthy();

  await expect(page.getByText(/Este catálogo de demostración usa marcas ficticias/)).toBeVisible();
  await search.fill('buscapina');
  await expect(page.getByText(/No hay presentaciones que coincidan con «buscapina»/)).toBeVisible();
  await page.getByRole('button', { name: 'Ver todas las presentaciones', exact: true }).click();
  await expect(search).toHaveValue('');
  await expect(page.getByRole('button', { name: /^Agregar / })).toHaveCount(products.length);
  await search.fill('  50   losartan  ');
  await expect(page.getByRole('group', { name: /^Losartán Losavia 50 mg ·/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Agregar / })).toHaveCount(1);
  await search.fill(paracetamol.code.toLowerCase());
  await page
    .getByRole('button', { name: `Agregar ${paracetamol.name} · ${paracetamol.presentation}` })
    .click();
  await page.getByLabel(`Cantidad de ${paracetamol.name}`, { exact: true }).fill('2');
  await expect(
    basket.getByText(`${paracetamol.presentation} · 2 envases`, { exact: true }),
  ).toBeVisible();
  await search.fill('400 ibuprofeno');
  await expect(basket.getByText(paracetamol.name, { exact: true })).toBeVisible();
  await page
    .getByRole('button', { name: `Agregar ${ibuprofen.name} · ${ibuprofen.presentation}` })
    .click();
  await expect(
    basket.getByText('Tu solicitud: 2 de 30 presentaciones', { exact: true }),
  ).toBeVisible();
  await search.fill('inexistente');
  await expect(
    page.getByText(/No hay presentaciones que coincidan con «inexistente»/),
  ).toBeVisible();
  await page.screenshot({
    path: 'test-results/incidencias-medicamentos-seleccion-sin-coincidencias.png',
    fullPage: true,
  });
  await basket
    .getByRole('button', { name: `Quitar ${ibuprofen.name} · ${ibuprofen.presentation}` })
    .click();
  await expect(
    basket.getByText('Tu solicitud: 1 de 30 presentaciones', { exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Ver todas las presentaciones', exact: true }).click();
  await page
    .getByRole('button', { name: `Agregar ${ibuprofen.name} · ${ibuprofen.presentation}` })
    .click();
  await page.getByLabel(`Cantidad de ${ibuprofen.name}`, { exact: true }).fill('3');
  await page.screenshot({
    path: 'test-results/incidencias-medicamentos-catalogo-y-seleccion.png',
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBeTruthy();
  await page.screenshot({
    path: 'test-results/incidencias-medicamentos-movil.png',
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await page.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
  await expect(
    page.getByText(`${paracetamol.presentation} · 2 unidades`, { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(`${ibuprofen.presentation} · 3 unidades`, { exact: true }),
  ).toBeVisible();
  const creating = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/purchases') && response.request().method() === 'POST',
  );
  await page
    .getByRole('button', { name: 'Solicitar propuesta a la farmacia', exact: true })
    .click();
  const createdResponse = await creating;
  expect(createdResponse.ok(), await createdResponse.text()).toBeTruthy();
  const created = (await createdResponse.json()) as Purchase;
  expect(created.status).toBe('ROUTING');
  const requested = created.orders
    .flatMap((order) => order.lines)
    .map(({ productId, quantity }) => ({ productId, quantity }));
  expect(requested).toEqual(
    expect.arrayContaining([
      { productId: paracetamol.id, quantity: 2 },
      { productId: ibuprofen.id, quantity: 3 },
    ]),
  );
  const persisted = await page.request.get(`/api/purchases/${created.id}`);
  expect(persisted.ok()).toBeTruthy();
  expect(
    (await persisted.json()).orders.flatMap((order: Purchase['orders'][number]) => order.lines),
  ).toHaveLength(2);
  await expect(page.getByText('1. La farmacia revisa tu solicitud', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('Incidencias farmacia - medicamentos distingue catálogo vacío de una falla y permite reintentar', async ({
  page,
}) => {
  let failing = true;
  let empty = false;
  await page.route('**/api/catalog/products', async (route) => {
    if (failing) {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ detail: 'Catálogo temporalmente no disponible.' }),
      });
    } else if (empty) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    } else await route.continue();
  });
  await openMedicines(page);
  await expect(page.getByText(/No se pudo cargar el catálogo de productos/)).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByText(/No hay presentaciones que coincidan/)).toHaveCount(0);
  failing = false;
  await page.getByRole('button', { name: 'Volver a intentar', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Agregar / }).first()).toBeVisible();
  empty = true;
  await page.reload();
  await page.getByRole('button', { name: 'Nueva compra', exact: true }).click();
  await page.getByRole('combobox', { name: 'Cómo querés recibir la compra' }).click();
  await page.getByRole('option', { name: 'Retiro en farmacia', exact: true }).click();
  await page.getByRole('button', { name: 'Usar mi ubicación actual' }).click();
  await expect(page.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  await expect(page.getByText(/Todavía no hay productos publicados/)).toBeVisible();
  await page.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await expect(
    page.getByText('Seleccioná entre una y treinta presentaciones diferentes.', { exact: true }),
  ).toBeVisible();
});
