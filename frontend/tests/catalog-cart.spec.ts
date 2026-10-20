import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { money, type Actor } from '../src/api';
import type { Product } from '../src/catalog/types';
import type { ProductAvailability } from '../src/catalog/availability';
import { catalogCategories } from '../src/catalog/categories';
import { cartStorageKey, type CartState } from '../src/cart/state';
import type { UserOption } from '../src/admin/types';
import type { Purchase } from '../src/purchases/types';

const evidenceDirectory = fileURLToPath(
  new URL('../../.runtime/catalog-cart-qa/', import.meta.url),
);

// Mutations are allowed only in the separate process/database owned by the workflow runner.
test.beforeAll(async ({ request }) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
  mkdirSync(evidenceDirectory, { recursive: true });
});

async function login(page: Page, profile = 'Comprador'): Promise<Actor> {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  const authenticated = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const response = await authenticated;
  expect(response.ok(), await response.text()).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  const actor = await page.request.get('/api/auth/me');
  expect(actor.ok()).toBeTruthy();
  return actor.json();
}

async function mutate<T>(
  request: APIRequestContext,
  path: string,
  body: unknown,
  method = 'POST',
): Promise<T> {
  const csrf = await (await request.get('/api/auth/csrf')).json();
  const response = await request.fetch(path, {
    method,
    data: body,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
  return response.json();
}

function productCard(page: Page, product: Product) {
  return page.locator('.MuiCard-root').filter({
    has: page.getByRole('heading', { name: product.name, exact: true, level: 3 }),
  });
}

function offerCard(detail: Locator, name: string) {
  return detail.getByRole('group', { name, exact: true });
}

async function catalog(page: Page) {
  await page.goto('/#/app/catalog');
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();
}

async function products(page: Page) {
  const response = await page.request.get('/api/catalog/products');
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as Product[];
}

async function snapshot(page: Page, actor: Actor): Promise<CartState | null> {
  const key = cartStorageKey(actor.id, new URL(test.info().project.use.baseURL!).origin);
  return page.evaluate((key) => {
    const stored = window.localStorage.getItem(key);
    return stored ? JSON.parse(stored) : null;
  }, key);
}

async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: `${evidenceDirectory}/${name}.png`, fullPage: true });
}

async function expectCategoryImages(tiles: Locator, count: number) {
  const images = tiles.locator('img');
  await expect(images).toHaveCount(count);
  await expect
    .poll(
      () =>
        images.evaluateAll(
          (elements) =>
            elements.filter((element) => {
              const image = element as HTMLImageElement;
              return image.complete && image.naturalWidth > 0 && image.currentSrc.endsWith('.webp');
            }).length,
        ),
      {
        timeout: 15000,
        message: 'Todas las fotografías de categorías deben cargar antes de capturar.',
      },
    )
    .toBe(count);
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
      { message: 'Los marcadores y el detalle deben quedar dentro de los bordes del mapa.' },
    )
    .toBe(true);
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
      {
        timeout: 15000,
        message: 'El mapa debe tener imágenes visibles y cargadas antes de capturar.',
      },
    )
    .toBeGreaterThanOrEqual(4);
}

async function expectNoHorizontalOverflow(page: Page) {
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
}

async function addFromCatalog(page: Page, product: Product, count = 1) {
  const search = page.getByRole('textbox', { name: 'Buscar productos', exact: true });
  await search.fill(product.code);
  const add = productCard(page, product).getByRole('button', {
    name: `Agregar ${product.name} al carrito`,
    exact: true,
  });
  for (let i = 0; i < count; i++) await add.click();
}

test('catálogo y carrito: categorías exactas, disponibilidad real y sucursales en el mapa', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyerContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  const buyer = await buyerContext.newPage();
  const failures: string[] = [];
  buyer.on('pageerror', (error) => failures.push(error.message));
  try {
    await login(admin, 'Administrador');
    const fixtures: Product[] = [];
    const prefix = `CAT-QA-${Date.now()}`;
    const fixtureCategories = catalogCategories.slice(0, 6);
    for (const category of fixtureCategories) {
      fixtures.push(
        await mutate<Product>(admin.request, '/api/admin/products', {
          code: `${prefix}-${category.value}`,
          name: `Producto QA ${category.label} ${prefix}`,
          presentation: 'Envase sintético para comprobar el catálogo',
          requiresPrescription: category.value === 'PRESCRIPTION',
          coldChain: false,
          restricted: false,
          active: true,
          version: 0,
          metadata: { catalogCategory: category.value },
        }),
      );
    }
    await login(buyer);
    await catalog(buyer);
    const tiles = buyer.getByRole('region', { name: 'Categorías del catálogo', exact: true });
    await expect(tiles.getByRole('button')).toHaveCount(6);
    for (const label of [
      'PROMOCIONES',
      'Medicamentos',
      'Salud Sexual',
      'Cuidado de Piel',
      'Belleza y Estética',
      'Vitaminas y Suplementos',
    ])
      await expect(tiles.getByRole('button', { name: label, exact: true })).toBeVisible();
    await buyer.getByRole('button', { name: 'Más categorías', exact: true }).click();
    await expect(tiles.getByRole('button')).toHaveCount(12);
    for (const label of [
      'Primeros auxilios',
      'Bebés',
      'Cuidado personal',
      'Insumos médicos',
      'Alimentos y Bebidas',
      'Limpieza y Hogar',
    ])
      await expect(tiles.getByRole('button', { name: label, exact: true })).toBeVisible();
    await expectCategoryImages(tiles, 11);
    await screenshot(buyer, 'catalogo-categorias-ampliadas-desktop');
    await tiles
      .locator('..')
      .screenshot({ path: `${evidenceDirectory}/catalogo-referencia-desktop.png` });
    await buyer.setViewportSize({ width: 390, height: 844 });
    await tiles.scrollIntoViewIfNeeded();
    await expectNoHorizontalOverflow(buyer);
    await expectCategoryImages(tiles, 11);
    await expect
      .poll(() =>
        tiles.evaluate(
          (element) => getComputedStyle(element).gridTemplateColumns.split(' ').length,
        ),
      )
      .toBe(3);
    await screenshot(buyer, 'catalogo-categorias-ampliadas-mobile');
    await tiles
      .locator('..')
      .screenshot({ path: `${evidenceDirectory}/catalogo-referencia-mobile.png` });
    await buyer.getByRole('button', { name: 'Menos categorías', exact: true }).click();
    await expect(tiles.getByRole('button')).toHaveCount(6);
    await expectCategoryImages(tiles, 5);
    await screenshot(buyer, 'catalogo-categorias-mobile');
    await buyer.setViewportSize({ width: 1440, height: 1000 });
    await screenshot(buyer, 'catalogo-categorias-desktop');
    for (const [index, category] of fixtureCategories.entries()) {
      if (category.value === 'PRESCRIPTION' || category.value === 'NON_PRESCRIPTION') {
        await tiles.getByRole('button', { name: 'Medicamentos', exact: true }).click();
        await buyer.getByRole('button', { name: category.label, exact: true }).click();
      } else {
        await tiles.getByRole('button', { name: category.label, exact: true }).click();
      }
      await expect(
        buyer.getByRole('heading', { name: category.label, exact: true, level: 2 }),
      ).toBeVisible();
      await expect(productCard(buyer, fixtures[index]!)).toBeVisible();
      for (const other of fixtures.filter((_, otherIndex) => otherIndex !== index))
        await expect(productCard(buyer, other)).toHaveCount(0);
    }
    await tiles.getByRole('button', { name: 'PROMOCIONES', exact: true }).click();
    await expect(buyer.getByRole('heading', { name: 'Promociones', exact: true })).toBeVisible();
    await buyer.getByRole('button', { name: 'Todos', exact: true }).click();
    const para = (await products(buyer)).find(
      (product) => product.name === 'Paracetamol Nivalen 500 mg',
    )!;
    expect(para).toBeTruthy();
    await buyer.getByRole('textbox', { name: 'Buscar productos', exact: true }).fill(para.code);
    await productCard(buyer, para)
      .getByRole('button', { name: 'Ver disponibilidad', exact: true })
      .click();
    const detail = buyer.getByRole('dialog');
    await expect(detail.getByRole('heading', { name: para.name, exact: true })).toBeVisible();
    const response = await buyer.request.get(`/api/catalog/products/${para.id}/availability`);
    expect(response.ok()).toBeTruthy();
    const availability = (await response.json()) as ProductAvailability;
    expect(availability.productId).toBe(para.id);
    expect(availability.offers.length).toBeGreaterThan(1);
    for (const offer of availability.offers) {
      const card = offerCard(detail, offer.branch.name);
      await expect(card.getByText(offer.chainName, { exact: true })).toBeVisible();
      await expect(card.getByText(offer.branch.address, { exact: true })).toBeVisible();
      await expect(
        card.getByText(money(offer.price, offer.currency), { exact: true }),
      ).toBeVisible();
      await expect(
        card.getByText(`${offer.availableQuantity} unidades informadas`, { exact: true }),
      ).toBeVisible();
    }
    let map = detail.getByRole('region', {
      name: 'Mapa de disponibilidad del producto',
      exact: true,
    });
    await map.scrollIntoViewIfNeeded();
    await expect(map.locator('.leaflet-marker-pane .emedicapp-branch-marker')).toHaveCount(
      availability.offers.length,
    );
    await expect(
      detail.getByRole('button', { name: 'Usar mi ubicación actual', exact: true }),
    ).toHaveCount(0);
    const chosen = availability.offers[0]!;
    await offerCard(detail, chosen.branch.name)
      .getByRole('button', { name: 'Ver sucursal en el mapa', exact: true })
      .click();
    map = detail.getByRole('region', { name: 'Mapa de disponibilidad del producto', exact: true });
    await map.scrollIntoViewIfNeeded();
    await expect(map.locator('.leaflet-marker-pane .emedicapp-branch-marker')).toHaveCount(1);
    await map.getByRole('button', { name: `Sucursal ${chosen.branch.name}`, exact: true }).click();
    await expect(
      map.locator('.leaflet-popup-content').getByText(chosen.branch.address, { exact: true }),
    ).toBeVisible();
    await waitForMapTiles(map);
    await screenshot(buyer, 'catalogo-disponibilidad-mapa-desktop');
    await buyer.setViewportSize({ width: 390, height: 844 });
    await map.scrollIntoViewIfNeeded();
    await expectNoHorizontalOverflow(buyer);
    await expect(
      map.getByRole('button', { name: `Sucursal ${chosen.branch.name}`, exact: true }),
    ).toBeVisible();
    await expectMapContentsContained(map, '.leaflet-marker-pane .emedicapp-branch-marker', 1);
    await map.getByRole('button', { name: `Sucursal ${chosen.branch.name}`, exact: true }).click();
    await expect(
      map.locator('.leaflet-popup-content').getByText(chosen.branch.address, { exact: true }),
    ).toBeVisible();
    await expectMapContentsContained(map, '.leaflet-popup', 1);
    await waitForMapTiles(map);
    await screenshot(buyer, 'catalogo-disponibilidad-mapa-mobile');
    await detail.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await expect(detail).not.toBeVisible();
    await screenshot(buyer, 'catalogo-mobile');
    await buyer.reload();
    await expect(
      buyer.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
    ).toBeVisible();
    expect(failures).toEqual([]);
  } finally {
    await Promise.all([adminContext.close(), buyerContext.close()]);
  }
});

test('catálogo y carrito: conserva cantidades al actualizar y separa las cuentas en el mismo navegador', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  const page = await context.newPage();
  let originalDriver: UserOption | undefined;
  let changedDriver: UserOption | undefined;
  try {
    await login(admin, 'Administrador');
    const driverResponse = await admin.request.get(
      '/api/admin/users/lookup?email=cadete%40demo.invalid',
    );
    expect(driverResponse.ok()).toBeTruthy();
    originalDriver = (await driverResponse.json()) as UserOption;
    changedDriver = await mutate<UserOption>(
      admin.request,
      `/api/admin/users/${originalDriver.id}/access`,
      {
        active: originalDriver.active,
        roles: [...new Set([...originalDriver.roles, 'BUYER'])],
        expectedVersion: originalDriver.version,
      },
      'PUT',
    );
    const actor = await login(page);
    await catalog(page);
    const available = await products(page);
    const para = available.find((product) => product.name === 'Paracetamol Nivalen 500 mg')!;
    const ibu = available.find((product) => product.name === 'Ibuprofeno Relivex 400 mg')!;
    await addFromCatalog(page, para, 2);
    await addFromCatalog(page, ibu);
    await page.goto('/#/app/cart');
    await expect(
      page.getByRole('heading', { name: 'Carrito de compras', exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel(`Cantidad de ${para.name}`, { exact: true })).toHaveText('2');
    await page
      .getByRole('button', { name: `Aumentar cantidad de ${para.name}`, exact: true })
      .click();
    await expect(page.getByLabel(`Cantidad de ${para.name}`, { exact: true })).toHaveText('3');
    await page
      .getByRole('button', { name: `Reducir cantidad de ${para.name}`, exact: true })
      .click();
    await page.getByRole('button', { name: `Quitar ${ibu.name}`, exact: true }).click();
    await expect(page.getByRole('heading', { name: ibu.name, exact: true })).toHaveCount(0);
    await page.reload();
    await expect(page.getByLabel(`Cantidad de ${para.name}`, { exact: true })).toHaveText('2');
    expect(await snapshot(page, actor)).toEqual({ items: { [para.id]: 2 }, preferredBranchId: '' });
    await page.setViewportSize({ width: 390, height: 844 });
    await expectNoHorizontalOverflow(page);
    await screenshot(page, 'carrito-cantidades-mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('button', { name: 'Salir', exact: true }).click();
    const secondActor = await login(page, 'Cadete');
    expect(secondActor.id).not.toBe(actor.id);
    expect(secondActor.roles).toContain('BUYER');
    await page.goto('/#/app/cart');
    await expect(
      page.getByRole('heading', { name: 'Tu carrito está vacío', exact: true }),
    ).toBeVisible();
    await catalog(page);
    await addFromCatalog(page, ibu);
    expect(await snapshot(page, secondActor)).toEqual({
      items: { [ibu.id]: 1 },
      preferredBranchId: '',
    });
    expect(await snapshot(page, actor)).toEqual({ items: { [para.id]: 2 }, preferredBranchId: '' });
    await page.getByRole('button', { name: 'Salir', exact: true }).click();
    await login(page);
    await page.goto('/#/app/cart');
    await expect(page.getByLabel(`Cantidad de ${para.name}`, { exact: true })).toHaveText('2');
    await expect(page.getByRole('heading', { name: ibu.name, exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: `Quitar ${para.name}`, exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Tu carrito está vacío', exact: true }),
    ).toBeVisible();
    await page.reload();
    await expect(
      page.getByRole('heading', { name: 'Tu carrito está vacío', exact: true }),
    ).toBeVisible();
  } finally {
    try {
      if (originalDriver && changedDriver) {
        await mutate(
          admin.request,
          `/api/admin/users/${originalDriver.id}/access`,
          {
            active: originalDriver.active,
            roles: originalDriver.roles,
            expectedVersion: changedDriver.version,
          },
          'PUT',
        );
      }
    } finally {
      await Promise.all([adminContext.close(), context.close()]);
    }
  }
});

test('catálogo y carrito: retiro, receta y reintento conservan el carrito hasta crear la solicitud', async ({
  page,
}) => {
  const actor = await login(page);
  await catalog(page);
  const available = await products(page);
  const para = available.find((product) => product.name === 'Paracetamol Nivalen 500 mg')!;
  const losartan = available.find((product) => product.name === 'Losartán Losavia 50 mg')!;
  expect(losartan.requiresPrescription).toBe(true);
  await addFromCatalog(page, para, 2);
  await page.getByRole('textbox', { name: 'Buscar productos', exact: true }).fill(losartan.code);
  await productCard(page, losartan)
    .getByRole('button', { name: 'Ver disponibilidad', exact: true })
    .click();
  const detail = page.getByRole('dialog');
  const availability = (await (
    await page.request.get(`/api/catalog/products/${losartan.id}/availability`)
  ).json()) as ProductAvailability;
  const pickup = availability.offers.find(
    (offer) =>
      offer.branch.name === 'Nivalis · Ciudad' &&
      offer.branch.pickupEnabled &&
      offer.availableQuantity > 0,
  )!;
  expect(pickup).toBeTruthy();
  await offerCard(detail, pickup.branch.name)
    .getByRole('button', { name: 'Retirar en esta sucursal', exact: true })
    .click();
  await expect(detail).not.toBeVisible();
  await page.goto('/#/app/cart');
  await expect(page.getByText('Preferencia de retiro', { exact: true })).toBeVisible();
  await expect(page.getByText(pickup.branch.address, { exact: true })).toBeVisible();
  const originalCart = {
    items: { [para.id]: 2, [losartan.id]: 1 },
    preferredBranchId: pickup.branch.id,
  };
  expect(await snapshot(page, actor)).toEqual(originalCart);
  await page.getByRole('link', { name: 'Continuar con la compra', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nueva compra', exact: true })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Cómo querés recibir la compra' })).toHaveText(
    'Retiro en farmacia',
  );
  await expect(page.getByRole('combobox', { name: 'Farmacia de preferencia' })).toContainText(
    pickup.branch.name,
  );
  await page.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  await expect(page.getByText('Confirmá la ubicación en el mapa.', { exact: true })).toBeVisible();
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({
    latitude: Number(pickup.branch.latitude),
    longitude: Number(pickup.branch.longitude),
  });
  await page.getByRole('button', { name: 'Usar mi ubicación actual', exact: true }).click();
  await expect(page.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  const basket = page.getByRole('region', { name: 'Productos seleccionados', exact: true });
  await expect(basket.getByText(para.name, { exact: true })).toBeVisible();
  await expect(basket.getByText(losartan.name, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await expect(
    page.getByText('Adjuntá la receta original para los medicamentos seleccionados.', {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByLabel(`Cantidad de ${para.name}`, { exact: true }).fill('1');
  const uploaded = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/documents') && response.request().method() === 'POST',
  );
  await page.getByLabel('Receta externa, si corresponde', { exact: true }).setInputFiles({
    name: 'receta-externa.pdf',
    mimeType: 'application/pdf',
    buffer: readFileSync(new URL('./fixtures/receta-externa.pdf', import.meta.url)),
  });
  const uploadResponse = await uploaded;
  expect(uploadResponse.ok(), await uploadResponse.text()).toBeTruthy();
  const document = await uploadResponse.json();
  await expect(page.getByText('Archivo guardado', { exact: true })).toBeVisible();
  expect(await snapshot(page, actor)).toEqual(originalCart);
  expect(JSON.stringify(await snapshot(page, actor))).not.toContain(document.id);
  await page.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await page.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
  await screenshot(page, 'carrito-revision-con-receta');
  let rejectNext = true;
  await page.route('**/api/purchases', async (route) => {
    if (route.request().method() === 'POST' && rejectNext) {
      rejectNext = false;
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'QA_RETRY',
          detail: 'No se pudo enviar la solicitud de prueba. Intentá nuevamente.',
        }),
      });
    } else await route.continue();
  });
  const submit = page.getByRole('button', {
    name: 'Solicitar propuesta a la farmacia',
    exact: true,
  });
  await submit.click();
  await expect(
    page.getByText('No se pudo enviar la solicitud de prueba. Intentá nuevamente.', {
      exact: true,
    }),
  ).toBeVisible();
  expect(await snapshot(page, actor)).toEqual(originalCart);
  const creating = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/purchases') && response.request().method() === 'POST',
  );
  await submit.click();
  const response = await creating;
  expect(response.ok(), await response.text()).toBeTruthy();
  const requestBody = response.request().postDataJSON();
  expect(requestBody).toMatchObject({
    mode: 'PICKUP',
    preferredBranchId: pickup.branch.id,
    prescriptionDocumentId: document.id,
  });
  expect(requestBody.items).toEqual(
    expect.arrayContaining([
      { productId: para.id, quantity: 1 },
      { productId: losartan.id, quantity: 1 },
    ]),
  );
  const created = (await response.json()) as Purchase;
  await expect(page.getByText('1. La farmacia revisa tu solicitud', { exact: true })).toBeVisible();
  const persisted = await page.request.get(`/api/purchases/${created.id}`);
  expect(persisted.ok()).toBeTruthy();
  expect(
    (await persisted.json()).orders.flatMap((order: Purchase['orders'][number]) => order.lines),
  ).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ productId: para.id, quantity: 1 }),
      expect.objectContaining({ productId: losartan.id, quantity: 1 }),
    ]),
  );
  expect(await snapshot(page, actor)).toEqual({
    items: { [para.id]: 1 },
    preferredBranchId: pickup.branch.id,
  });
  await page.goto('/#/app/cart');
  await expect(page.getByLabel(`Cantidad de ${para.name}`, { exact: true })).toHaveText('1');
  await expect(page.getByRole('heading', { name: losartan.name, exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Continuar con la compra', exact: true }).click();
  await page.getByRole('button', { name: 'Usar mi ubicación actual', exact: true }).click();
  await expect(page.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Continuar a productos', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
  await page.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
  const lastCreating = page.waitForResponse(
    (result) => result.url().endsWith('/api/purchases') && result.request().method() === 'POST',
  );
  await page
    .getByRole('button', { name: 'Solicitar propuesta a la farmacia', exact: true })
    .click();
  const lastResponse = await lastCreating;
  expect(lastResponse.ok(), await lastResponse.text()).toBeTruthy();
  await expect(page.getByText('1. La farmacia revisa tu solicitud', { exact: true })).toBeVisible();
  expect(await snapshot(page, actor)).toEqual({ items: {}, preferredBranchId: '' });
  await page.goto('/#/app/cart');
  await expect(
    page.getByRole('heading', { name: 'Tu carrito está vacío', exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Tu carrito está vacío', exact: true }),
  ).toBeVisible();
});
