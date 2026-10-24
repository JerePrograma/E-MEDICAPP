import { test, expect, type Locator, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

type Product = {
  id: string;
  name: string;
  presentation: string;
  imageUrl: string | null;
};

const evidenceDirectory = fileURLToPath(
  new URL('../../.runtime/product-image-qa/', import.meta.url),
);

// Only the workflow runner's isolated database/process may be changed by this test.
test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
  mkdirSync(evidenceDirectory, { recursive: true });
});

async function login(page: Page, profile: 'Administrador' | 'Comprador') {
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
  const actor = await (await page.request.get('/api/auth/me')).json();
  expect(actor.roles).toContain(profile === 'Administrador' ? 'ADMIN' : 'BUYER');
}

async function readProduct(page: Page, id?: string): Promise<Product> {
  const response = await page.request.get('/api/admin/overview');
  expect(response.ok()).toBeTruthy();
  const products: Product[] = (await response.json()).products;
  const product = id
    ? products.find((candidate) => candidate.id === id)
    : products.find((candidate) => candidate.name === 'Paracetamol Nivalen 500 mg');
  expect(product).toBeTruthy();
  return product!;
}

async function openProduct(page: Page, product: Product) {
  await page.getByRole('tab', { name: 'Productos', exact: true }).click();
  await page
    .locator('.MuiCard-root')
    .filter({ has: page.getByText(`${product.name} · ${product.presentation}`, { exact: true }) })
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  return page.getByRole('dialog');
}

async function saveProduct(page: Page, id: string) {
  const saving = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/admin/products/${id}`) && response.request().method() === 'PUT',
  );
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Guardar cambios', exact: true })
    .click();
  const response = await saving;
  expect(response.ok(), await response.text()).toBeTruthy();
  await expect(page.getByRole('dialog')).not.toBeVisible();
}

async function raster(page: Page, color: string, mimeType = 'image/png') {
  const dataUrl = await page.evaluate(
    ({ color, mimeType }) => {
      const canvas = document.createElement('canvas');
      canvas.width = 240;
      canvas.height = 120;
      const context = canvas.getContext('2d')!;
      context.fillStyle = color;
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.fillStyle = '#fff';
      context.font = 'bold 25px sans-serif';
      context.textAlign = 'center';
      context.fillText('PRODUCTO QA', 120, 62);
      return canvas.toDataURL(mimeType);
    },
    { color, mimeType },
  );
  return {
    name: mimeType === 'image/png' ? 'producto.png' : 'producto.jpg',
    mimeType,
    buffer: Buffer.from(dataUrl.split(',')[1], 'base64'),
  };
}

async function expectLoadedPhoto(photo: Locator) {
  await expect(photo).toBeVisible();
  await expect
    .poll(() =>
      photo.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0),
    )
    .toBeTruthy();
  await expect(photo).toHaveCSS('object-fit', 'contain');
}

async function openCatalog(page: Page, product: Product) {
  await page.goto('/#/app/catalog');
  // Refresh changes saved in the other account even when this hash route is already open.
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
  ).toBeVisible();
  await page.getByRole('textbox', { name: 'Buscar productos', exact: true }).fill(product.name);
  return page.locator('.MuiCard-root').filter({
    has: page.getByRole('heading', { name: product.name, exact: true, level: 3 }),
  });
}

test('foto del producto: carga, persistencia, catálogo, reemplazo y eliminación con validaciones', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyerContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  const buyer = await buyerContext.newPage();
  admin.setDefaultTimeout(15000);
  buyer.setDefaultTimeout(15000);
  const failures: string[] = [];
  admin.on('pageerror', (error) => failures.push(error.message));
  buyer.on('pageerror', (error) => failures.push(error.message));

  try {
    await login(admin, 'Administrador');
    await admin.goto('/#/app/admin');
    const initial = await readProduct(admin);
    expect(initial.imageUrl).toBeNull();
    let editor = await openProduct(admin, initial);
    const input = editor.getByLabel('Foto del producto', { exact: true });
    const preview = () =>
      editor.getByRole('img', { name: 'Vista previa de la foto del producto', exact: true });

    await input.setInputFiles({
      name: 'producto.svg',
      mimeType: 'image/svg+xml',
      buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'),
    });
    await expect(editor.getByRole('alert')).toContainText(/PNG o JPG/);
    await expect(preview()).toHaveCount(0);
    await input.setInputFiles({
      name: 'producto-grande.png',
      mimeType: 'image/png',
      buffer: Buffer.alloc(2 * 1024 * 1024 + 1),
    });
    await expect(editor.getByRole('alert')).toContainText(/2 MB/);
    await expect(preview()).toHaveCount(0);
    await input.setInputFiles({
      name: 'producto-invalido.png',
      mimeType: 'image/png',
      buffer: Buffer.from('El contenido no es una imagen.'),
    });
    await expect(editor.getByRole('alert')).toContainText(/imagen.*válida/);
    await expect(preview()).toHaveCount(0);

    const chooser = admin.waitForEvent('filechooser');
    await editor.getByRole('button', { name: 'Seleccionar imagen', exact: true }).click();
    await (await chooser).setFiles(await raster(admin, '#0c6780'));
    await expectLoadedPhoto(preview());
    await admin.screenshot({
      path: `${evidenceDirectory}/foto-formulario-escritorio.png`,
    });
    await saveProduct(admin, initial.id);

    await admin.reload();
    let saved = await readProduct(admin, initial.id);
    expect(saved.imageUrl).toMatch(new RegExp(`^/api/catalog/products/${initial.id}/image\\?v=`));
    const firstResponse = await admin.request.get(saved.imageUrl!);
    expect(firstResponse.ok()).toBeTruthy();
    expect(firstResponse.headers()['content-type']).toMatch(/^image\/png/);
    const firstBytes = await firstResponse.body();
    expect(firstBytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    editor = await openProduct(admin, saved);
    await expect(preview()).toHaveAttribute('src', saved.imageUrl!);
    await expectLoadedPhoto(preview());

    const updatedName = `${initial.name} QA foto`;
    await editor.getByRole('textbox', { name: 'Nombre', exact: true }).fill(updatedName);
    await saveProduct(admin, initial.id);
    saved = await readProduct(admin, initial.id);
    expect(saved.name).toBe(updatedName);
    expect(saved.imageUrl).toBeTruthy();
    const preserved = await admin.request.get(saved.imageUrl!);
    expect(preserved.ok()).toBeTruthy();
    expect((await preserved.body()).equals(firstBytes)).toBeTruthy();

    await login(buyer, 'Comprador');
    let card = await openCatalog(buyer, saved);
    const photoName = `Foto de ${saved.name}`;
    await expectLoadedPhoto(card.getByRole('img', { name: photoName, exact: true }));
    await card.scrollIntoViewIfNeeded();
    const photoBounds = await card.getByRole('img', { name: photoName, exact: true }).boundingBox();
    const photoPadding = await card
      .getByRole('img', { name: photoName, exact: true })
      .evaluate((image) => parseFloat(getComputedStyle(image).paddingTop));
    const categoryBounds = await card
      .getByText('Medicamentos sin receta', { exact: true })
      .boundingBox();
    expect(photoBounds!.y + photoPadding).toBeGreaterThanOrEqual(
      categoryBounds!.y + categoryBounds!.height,
    );
    await buyer.screenshot({
      path: `${evidenceDirectory}/foto-catalogo-escritorio.png`,
      fullPage: true,
    });
    await card.getByRole('button', { name: 'Ver disponibilidad', exact: true }).click();
    await expectLoadedPhoto(
      buyer.getByRole('dialog').getByRole('img', { name: photoName, exact: true }),
    );
    await buyer.screenshot({ path: `${evidenceDirectory}/foto-detalle-escritorio.png` });
    await buyer.setViewportSize({ width: 390, height: 844 });
    await expectLoadedPhoto(
      buyer.getByRole('dialog').getByRole('img', { name: photoName, exact: true }),
    );
    await expect
      .poll(() => buyer.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBeTruthy();
    await buyer.screenshot({ path: `${evidenceDirectory}/foto-detalle-movil.png` });
    await buyer.getByRole('dialog').getByRole('button', { name: 'Cerrar', exact: true }).click();
    await expect(buyer.getByRole('dialog')).not.toBeVisible();
    card = await openCatalog(buyer, saved);
    await expectLoadedPhoto(card.getByRole('img', { name: photoName, exact: true }));
    await card.scrollIntoViewIfNeeded();
    await buyer.screenshot({
      path: `${evidenceDirectory}/foto-catalogo-movil.png`,
      fullPage: true,
    });

    editor = await openProduct(admin, saved);
    await editor
      .getByLabel('Foto del producto', { exact: true })
      .setInputFiles(await raster(admin, '#8b3c65', 'image/jpeg'));
    await expect(preview()).toHaveAttribute('src', /^data:image\/jpeg;base64,/);
    await saveProduct(admin, initial.id);
    const replaced = await readProduct(admin, initial.id);
    expect(replaced.imageUrl).not.toBe(saved.imageUrl);
    const replacedResponse = await admin.request.get(replaced.imageUrl!);
    expect(replacedResponse.ok()).toBeTruthy();
    expect(replacedResponse.headers()['content-type']).toMatch(/^image\/png/);
    const replacedBytes = await replacedResponse.body();
    expect(replacedBytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(replacedBytes.equals(firstBytes)).toBeFalsy();
    card = await openCatalog(buyer, replaced);
    await expect(card.getByRole('img', { name: photoName, exact: true })).toHaveAttribute(
      'src',
      replaced.imageUrl!,
    );
    await expectLoadedPhoto(card.getByRole('img', { name: photoName, exact: true }));

    editor = await openProduct(admin, replaced);
    await editor.getByRole('button', { name: 'Quitar foto', exact: true }).click();
    await expect(preview()).toHaveCount(0);
    await editor.getByRole('textbox', { name: 'Nombre', exact: true }).fill(initial.name);
    await saveProduct(admin, initial.id);
    await admin.reload();
    const removed = await readProduct(admin, initial.id);
    expect(removed.imageUrl).toBeNull();
    expect((await buyer.request.get(replaced.imageUrl!)).status()).toBe(404);
    card = await openCatalog(buyer, removed);
    await expect(
      card.getByRole('img', { name: `Foto de ${removed.name}`, exact: true }),
    ).toHaveCount(0);
    await expect(
      card.getByRole('button', { name: 'Ver disponibilidad', exact: true }),
    ).toBeVisible();
    expect(failures).toEqual([]);
  } finally {
    await Promise.allSettled([adminContext.close(), buyerContext.close()]);
  }
});

for (const scenario of [
  { name: 'otro origen', backend: 'http://127.0.0.1:8083' },
  { name: 'proxy del mismo origen', backend: 'http://localhost:4175/e-medicapp' },
]) {
  test(`foto del producto en ${scenario.name}: usa la sesión y muestra la ilustración si falla la imagen`, async ({
    page,
    request,
  }) => {
    const frontendOrigin = 'http://localhost:4175';
    const backendOrigin = scenario.backend;
    const backendBasePath = new URL(backendOrigin).pathname.replace(/\/$/, '');
    const session = 'qa-product-image-cross-origin';
    const errors: string[] = [];
    const imageRequests: { session?: string; cookie?: string }[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.context().grantPermissions(['local-network-access'], { origin: frontendOrigin });
    await page
      .context()
      .addCookies([{ name: 'surrounding-site', value: 'private-cookie', url: frontendOrigin }]);

    // Read only static assets from the QA JAR; API requests use the configured session endpoint.
    await page.route(`${frontendOrigin}/**`, async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.startsWith('/api/')) {
        await route.fulfill({ status: 404, body: 'Static frontend only' });
        return;
      }
      const asset = await request.get(`http://127.0.0.1:8083${url.pathname}`);
      await route.fulfill({ response: asset });
    });
    await page.addInitScript(
      ({ backendOrigin, session }) => {
        window.localStorage.setItem('emedicapp.backend-url', backendOrigin);
        window.sessionStorage.setItem(`emedicapp.session:${backendOrigin}`, session);
      },
      { backendOrigin, session },
    );

    const makeProduct = (id: string, name: string) => ({
      id,
      code: `QA-${id}`,
      name,
      presentation: 'Caja de prueba',
      requiresPrescription: false,
      coldChain: false,
      restricted: false,
      active: true,
      version: 1,
      metadata: { catalogCategory: 'NON_PRESCRIPTION' },
      imageUrl: `/api/catalog/products/${id}/image?v=1`,
    });
    const products = [
      makeProduct('qa-photo', 'Producto con foto remota'),
      makeProduct('qa-missing', 'Producto con foto no disponible'),
      makeProduct('qa-invalid', 'Producto con foto dañada'),
    ];
    const png = (await raster(page, '#0c6780')).buffer;
    await page.route(`${backendOrigin}/api/**`, async (route) => {
      const current = route.request();
      const path = new URL(current.url()).pathname.slice(backendBasePath.length);
      const headers = {
        'Access-Control-Allow-Origin': frontendOrigin,
        'Access-Control-Allow-Headers': 'Accept, X-Auth-Session',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
      };
      if (current.method() === 'OPTIONS') {
        await route.fulfill({ status: 204, headers });
        return;
      }
      if (path === '/api/bootstrap') {
        await route.fulfill({ headers, json: { mode: 'DEMO' } });
        return;
      }
      if (path === '/api/auth/me') {
        await route.fulfill({
          headers,
          json: {
            id: 'qa-buyer',
            email: 'buyer@qa.invalid',
            name: 'Comprador QA',
            roles: ['BUYER'],
            branchIds: [],
          },
        });
        return;
      }
      if (path === '/api/catalog/products') {
        await route.fulfill({ headers, json: products });
        return;
      }
      if (path === '/api/catalog/promotions') {
        await route.fulfill({ headers, json: [] });
        return;
      }
      if (path.endsWith('/image')) {
        imageRequests.push({
          session: current.headers()['x-auth-session'],
          cookie: current.headers().cookie,
        });
        if (current.headers()['x-auth-session'] !== session) {
          await route.fulfill({ status: 401, headers, json: { detail: 'Sesión requerida.' } });
          return;
        }
        if (path.includes('/qa-missing/')) {
          await route.fulfill({ status: 404, headers, json: { detail: 'Foto no disponible.' } });
        } else {
          await route.fulfill({
            headers,
            contentType: 'image/png',
            body: path.includes('/qa-invalid/') ? Buffer.from('PNG inválido') : png,
          });
        }
        return;
      }
      await route.fulfill({ status: 404, headers });
    });

    await page.goto(`${frontendOrigin}/#/app/catalog`);
    await expect(
      page.getByRole('heading', { name: 'Catálogo', exact: true, level: 1 }),
    ).toBeVisible();
    const card = (product: Product) =>
      page.locator('.MuiCard-root').filter({
        has: page.getByRole('heading', { name: product.name, exact: true, level: 3 }),
      });
    const photo = card(products[0]).getByRole('img', {
      name: `Foto de ${products[0].name}`,
      exact: true,
    });
    await expectLoadedPhoto(photo);
    await expect(photo).toHaveAttribute('src', /^blob:http:\/\/localhost:4175\//);
    for (const product of products.slice(1)) {
      const fallback = card(product);
      await fallback.scrollIntoViewIfNeeded();
      await expect(fallback.getByRole('img', { name: `Foto de ${product.name}` })).toHaveCount(0);
      await expect(fallback.locator('svg[aria-hidden="true"]')).toBeVisible();
      await expect(fallback.getByRole('button', { name: 'Ver disponibilidad' })).toBeVisible();
    }
    await expect.poll(() => imageRequests.length).toBeGreaterThanOrEqual(3);
    expect(imageRequests.every((image) => image.session === session && !image.cookie)).toBe(true);
    const beforeReload = imageRequests.length;
    await page.reload();
    await expectLoadedPhoto(photo);
    await expect.poll(() => imageRequests.length).toBeGreaterThan(beforeReload);
    expect(imageRequests.every((image) => image.session === session && !image.cookie)).toBe(true);
    expect(errors).toEqual([]);
    await page.screenshot({
      path: `${evidenceDirectory}/foto-catalogo-${scenario.name.replaceAll(' ', '-')}.png`,
      fullPage: true,
    });
  });
}
