import { test, expect, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Actor } from '../src/api';
import type { Overview } from '../src/admin/types';
import type { Product } from '../src/catalog/types';

// Only static assets come from Vite or the workflow runner's isolated QA JAR.
// Every API response is mocked; the suite must never reach operational data.
const frontendUrl = new URL(process.env.EMEDICAPP_E2E_URL ?? 'http://127.0.0.1:5174');
if (
  frontendUrl.protocol !== 'http:' ||
  frontendUrl.hostname !== '127.0.0.1' ||
  !['5174', '8083'].includes(frontendUrl.port)
) {
  throw new Error('QA responsive sólo permite los servidores locales 127.0.0.1:5174 o :8083.');
}
const frontend = frontendUrl.origin;
const evidence = fileURLToPath(new URL('../../.runtime/mobile-layout-20261024/', import.meta.url));
const productPhoto = readFileSync(
  fileURLToPath(new URL('../public/catalog/category-medicines.webp', import.meta.url)),
);

test.use({ baseURL: frontend });
test.beforeAll(() => mkdirSync(evidence, { recursive: true }));

const buyer: Actor = {
  id: 'qa-mobile-buyer',
  email: 'buyer@qa.invalid',
  name: 'Comprador de prueba',
  roles: ['BUYER'],
  branchIds: [],
};
const admin: Actor = {
  ...buyer,
  id: 'qa-mobile-admin',
  name: 'Administración QA',
  roles: ['ADMIN'],
};
const sampleProduct: Product = {
  id: 'qa-mobile-product',
  code: 'MOBILE-01',
  name: 'Paracetamol de prueba 500 mg',
  presentation: 'Caja de 30 comprimidos',
  requiresPrescription: false,
  coldChain: false,
  restricted: false,
  active: true,
  version: 1,
  metadata: { catalogCategory: 'NON_PRESCRIPTION' },
  imageUrl: '/api/catalog/products/qa-mobile-product/image?v=1',
};
const products: Product[] = [
  sampleProduct,
  {
    ...sampleProduct,
    id: 'qa-mobile-long-name',
    code: 'MOBILE-02',
    name: 'Protector solar facial de prueba para piel sensible FPS 50+',
    presentation: 'Frasco con dosificador de 50 ml',
    requiresPrescription: false,
    coldChain: false,
    restricted: false,
    active: true,
    version: 1,
    metadata: { catalogCategory: 'SKIN_CARE' },
    imageUrl: null,
  },
  {
    ...sampleProduct,
    id: 'qa-mobile-prescription',
    code: 'MOBILE-03',
    name: 'Amoxicilina de prueba 500 mg',
    presentation: 'Caja de 21 cápsulas',
    requiresPrescription: true,
    metadata: { catalogCategory: 'PRESCRIPTION' },
    imageUrl: null,
  },
];

const overview: Overview = {
  products,
  chains: [],
  branches: [],
  plans: [],
  subscriptions: [],
  memberships: [],
  users: [],
  config: {
    version: 1,
    currency: 'ARS',
    quoteMinutes: 30,
    reservationMinutes: 30,
    graceDays: 5,
    highValueThreshold: '100000',
    maxUploadMb: 2,
    deliveryEnabled: true,
    pickupEnabled: true,
    fallbackEnabled: false,
    messages: {},
  },
};

async function mockApi(page: Page, actor: Actor | null, failCatalog = false) {
  const unexpected: string[] = [];
  const mutations: string[] = [];
  const errors: string[] = [];
  let catalogFailed = false;
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.origin !== frontend || !['GET', 'HEAD'].includes(request.method())) {
      mutations.push(`${request.method()} ${url.pathname}`);
      await route.fulfill({ status: 405, json: { detail: 'QA sólo permite lecturas simuladas.' } });
      return;
    }
    const path = url.pathname;
    if (path === '/api/bootstrap') {
      await route.fulfill({ json: { mode: 'DEMO' } });
    } else if (path === '/api/auth/me') {
      await route.fulfill({
        status: actor ? 200 : 401,
        json: actor ?? { code: 'UNAUTHENTICATED' },
      });
    } else if (path === '/api/catalog/products') {
      if (failCatalog && !catalogFailed) {
        catalogFailed = true;
        await route.fulfill({
          status: 503,
          json: { detail: 'Catálogo temporalmente no disponible.' },
        });
      } else await route.fulfill({ json: products });
    } else if (path.endsWith('/image')) {
      await route.fulfill({ contentType: 'image/webp', body: productPhoto });
    } else if (path.endsWith('/availability')) {
      await route.fulfill({ json: { productId: products[0].id, offers: [] } });
    } else if (path === '/api/admin/overview') {
      await route.fulfill({ json: overview });
    } else if (
      [
        '/api/catalog/promotions',
        '/api/catalog/branches',
        '/api/purchases',
        '/api/admin/invoice-issues',
      ].includes(path)
    ) {
      await route.fulfill({ json: [] });
    } else {
      unexpected.push(path);
      await route.fulfill({ status: 404, json: { detail: 'Ruta no preparada en QA.' } });
    }
  });
  return { unexpected, mutations, errors };
}

async function expectNoOverflow(page: Page, dialog?: Locator) {
  await expect
    .poll(() =>
      page.evaluate(() => {
        const scroll = document
          .querySelector('ion-content')
          ?.shadowRoot?.querySelector('[part="scroll"]');
        return (
          document.documentElement.scrollWidth <= window.innerWidth + 1 &&
          (!scroll || scroll.scrollWidth <= scroll.clientWidth + 1)
        );
      }),
    )
    .toBe(true);
  if (dialog) {
    expect(
      await dialog
        .locator('.MuiDialogContent-root')
        .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
    ).toBe(true);
  }
}

async function expectInViewport(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  const bounds = await target.boundingBox();
  expect(bounds).not.toBeNull();
  const viewport = page.viewportSize()!;
  expect(bounds!.x).toBeGreaterThanOrEqual(-1);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(bounds!.y).toBeGreaterThanOrEqual(-1);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(viewport.height + 1);
  return bounds!;
}

async function capture(page: Page, name: string) {
  await page.screenshot({ path: `${evidence}/${name}.png` });
}

for (const width of [360, 390, 430]) {
  test(`móvil ${width}: ingreso accesible en la primera pantalla`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const calls = await mockApi(page, null);
    await page.goto('/');
    const login = page.getByRole('button', { name: 'Ingresar', exact: true });
    const bounds = await expectInViewport(page, login);
    expect(bounds.height).toBeGreaterThanOrEqual(44);
    await expectInViewport(page, page.getByRole('combobox', { name: 'Perfil de demostración' }));
    await expectNoOverflow(page);
    await capture(page, `ingreso-${width}`);
    expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
  });

  test(`móvil ${width}: catálogo, navegación persistente y carrito utilizables`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const calls = await mockApi(page, buyer);
    await page.goto('/#/app/catalog');
    const navigation = page.getByRole('navigation', { name: 'Navegación principal' });
    await expect(navigation.getByRole('link')).toHaveCount(4);
    for (const link of await navigation.getByRole('link').all()) {
      const bounds = await expectInViewport(page, link);
      expect(bounds.height).toBeGreaterThanOrEqual(44);
    }
    const navBeforeScroll = await navigation.boundingBox();
    const card = page.locator('.MuiCard-root').filter({
      has: page.getByRole('heading', { name: products[0].name, exact: true, level: 3 }),
    });
    const availability = card.getByRole('button', { name: 'Ver disponibilidad', exact: true });
    const firstAction = await expectInViewport(page, availability);
    expect(firstAction.y + firstAction.height).toBeLessThanOrEqual(navBeforeScroll!.y + 1);
    const photo = card.getByRole('img', { name: `Foto de ${products[0].name}` });
    await expect
      .poll(() =>
        photo.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0),
      )
      .toBe(true);
    await expectNoOverflow(page);
    await capture(page, `catalogo-${width}`);

    await availability.click();
    const detail = page.getByRole('dialog');
    await expect(
      detail.getByRole('heading', { name: products[0].name, exact: true }),
    ).toBeVisible();
    await expectNoOverflow(page, detail);
    const close = detail.getByRole('button', { name: 'Cerrar', exact: true });
    await expectInViewport(page, close);
    await capture(page, `detalle-${width}`);
    await close.click();

    await page.getByRole('button', { name: 'Más categorías', exact: true }).click();
    const categories = page.getByRole('region', { name: 'Categorías del catálogo', exact: true });
    await expect(categories.getByRole('button')).toHaveCount(12);
    await expectNoOverflow(page);
    await page.getByRole('button', { name: 'Menos categorías', exact: true }).click();

    await page.locator('ion-content').evaluate((element) => {
      const scroll = element.shadowRoot?.querySelector('[part="scroll"]');
      if (scroll) scroll.scrollTop = scroll.scrollHeight;
    });
    const navAfterScroll = await navigation.boundingBox();
    expect(Math.abs(navAfterScroll!.y - navBeforeScroll!.y)).toBeLessThanOrEqual(2);
    await expectNoOverflow(page);
    await navigation.getByRole('link', { name: /^Carrito/ }).click();
    await expect(
      page.getByRole('heading', { name: 'Carrito de compras', exact: true }),
    ).toBeVisible();
    await navigation.getByRole('link', { name: 'Mis compras', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Mis compras', exact: true, level: 1 }),
    ).toBeVisible();
    await navigation.getByRole('link', { name: 'Promociones', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Promociones', exact: true, level: 1 }),
    ).toBeVisible();
    await navigation.getByRole('link', { name: 'Catálogo', exact: true }).click();
    await expectNoOverflow(page);
    expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
  });

  test(`móvil ${width}: administración y editor de producto sin cortes ni escrituras`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const calls = await mockApi(page, admin);
    await page.goto('/#/app/admin');
    const navigation = page.getByRole('navigation', { name: 'Navegación principal' });
    await expect(navigation.getByRole('link')).toHaveCount(3);
    for (const link of await navigation.getByRole('link').all()) await expectInViewport(page, link);
    await expectNoOverflow(page);
    await page.getByRole('button', { name: 'Agregar producto', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Agregar producto' });
    await expect(dialog).toBeVisible();
    await expectNoOverflow(page, dialog);
    await dialog.getByRole('textbox', { name: 'Código comercial', exact: false }).fill('SOLO-QA');
    await dialog.getByRole('textbox', { name: 'Nombre', exact: true }).fill('Producto sin guardar');
    const chooseImage = dialog.getByRole('button', { name: 'Seleccionar imagen', exact: true });
    await chooseImage.scrollIntoViewIfNeeded();
    const chooseBounds = await expectInViewport(page, chooseImage);
    expect(chooseBounds.height).toBeGreaterThanOrEqual(44);
    await capture(page, `editor-producto-${width}`);
    const save = dialog.getByRole('button', { name: 'Guardar cambios', exact: true });
    await save.scrollIntoViewIfNeeded();
    await expectInViewport(page, save);
    const close = dialog.getByRole('button', { name: 'Cerrar', exact: true });
    await close.scrollIntoViewIfNeeded();
    await expectInViewport(page, close);
    expect(
      await close.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return element.contains(
          document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2),
        );
      }),
    ).toBe(true);
    await close.click();
    await expect(dialog).not.toBeVisible();
    await expectInViewport(page, navigation);
    expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
  });
}

test('móvil 360: una cuenta con varios roles alcanza todas sus secciones', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 844 });
  const calls = await mockApi(page, {
    ...buyer,
    id: 'qa-mobile-multiple-roles',
    roles: ['BUYER', 'ADMIN', 'PHARMACY'],
  });
  await page.goto('/#/app/catalog');
  const navigation = page.getByRole('navigation', { name: 'Navegación principal' });
  await expect(navigation.getByRole('link')).toHaveCount(4);
  const more = navigation.getByRole('button', { name: 'Más secciones', exact: true });
  for (const target of [...(await navigation.getByRole('link').all()), more]) {
    const bounds = await expectInViewport(page, target);
    expect(bounds.height).toBeGreaterThanOrEqual(44);
  }
  await expectNoOverflow(page);
  await more.click();
  const menu = page.getByRole('menu');
  await expect(menu).toBeVisible();
  await expect(
    menu.getByRole('menuitem', { name: 'Gestionar promociones', exact: true }),
  ).toBeVisible();
  for (const item of await menu.getByRole('menuitem').all()) {
    // MUI scales the menu while opening; measure its final touch targets.
    await expect
      .poll(async () => (await item.boundingBox())?.height ?? 0)
      .toBeGreaterThanOrEqual(44);
    const bounds = await expectInViewport(page, item);
    expect(bounds.height).toBeGreaterThanOrEqual(44);
  }
  await capture(page, 'navegacion-varios-roles-360');
  await menu.getByRole('menuitem', { name: 'Administración', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Administración', exact: true, level: 1 }),
  ).toBeVisible();
  await expect(menu).not.toBeVisible();
  await more.click();
  await expect(menu).toBeVisible();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(menu).not.toBeVisible();
  const catalogLink = navigation.getByRole('link', { name: 'Catálogo', exact: true });
  await expectInViewport(page, catalogLink);
  await catalogLink.click();
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();
  await page.setViewportSize({ width: 360, height: 844 });
  await expect(menu).not.toBeVisible();
  await expect(more).toHaveAttribute('aria-expanded', 'false');
  await expect(navigation.getByRole('link')).toHaveCount(4);
  await expectNoOverflow(page);
  expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
});

test('móvil: una falla del catálogo mantiene navegación y reintento accesibles', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const calls = await mockApi(page, buyer, true);
  await page.goto('/#/app/catalog');
  const error = page
    .getByRole('alert')
    .filter({ hasText: 'Catálogo temporalmente no disponible.' });
  await expect(error).toBeVisible();
  await expectNoOverflow(page);
  await error.getByRole('button', { name: 'Reintentar', exact: true }).click();
  await expect(page.getByRole('heading', { name: products[0].name, exact: true })).toBeVisible();
  await expect(error).not.toBeVisible();
  expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
});

test('escritorio: conserva navegación lateral y catálogo completo', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const calls = await mockApi(page, buyer);
  await page.goto('/#/app/catalog');
  const navigation = page.getByRole('navigation', { name: 'Navegación principal' });
  const main = page.locator('#main-content');
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();
  const navBounds = await navigation.boundingBox();
  const mainBounds = await main.boundingBox();
  expect(navBounds!.x + navBounds!.width).toBeLessThanOrEqual(mainBounds!.x + 1);
  await expect(
    page.getByRole('region', { name: 'Categorías del catálogo' }).getByRole('button'),
  ).toHaveCount(6);
  await expectNoOverflow(page);
  await capture(page, 'catalogo-desktop');
  expect(calls).toEqual({ unexpected: [], mutations: [], errors: [] });
});
