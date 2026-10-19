import { test, expect, type Page, type Route } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

type Chain = { id: string; name: string; active: boolean; version: number; logoUrl: string | null };
type Branch = { id: string; chainId: string | null; name: string; chainLogoUrl: string | null };

// The existing runner owns the fresh database, document directory and local QA process.
// Refuse the presentation database and any remote application before opening an editor.
test.beforeAll(async ({ request }) => {
  expect(
    process.env.EMEDICAPP_WORKFLOW_DB,
    'Ejecutá scripts/Test-Workflow.ps1 para crear un escenario aislado.',
  ).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  const url = new URL(process.env.EMEDICAPP_E2E_URL ?? 'http://127.0.0.1:8082');
  expect(url.origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
});

async function login(page: Page, profile: 'Administrador' | 'Comprador') {
  await page.goto('/');
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: new RegExp(`^${profile} ·`) }).click();
  const response = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  expect((await response).ok()).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  const actor = await (await page.request.get('/api/auth/me')).json();
  expect(actor.roles).toContain(profile === 'Administrador' ? 'ADMIN' : 'BUYER');
}

async function openChain(page: Page, name: string) {
  await page.getByRole('tab', { name: 'Cadenas', exact: true }).click();
  await page
    .getByText(name, { exact: true })
    .locator('../..')
    .getByRole('button', { name: 'Modificar', exact: true })
    .click();
  return page.getByRole('dialog');
}

async function saveChain(page: Page, chainId: string) {
  const response = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/admin/chains/${chainId}`) &&
      response.request().method() === 'PUT',
  );
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Guardar cambios', exact: true })
    .click();
  const saved = await response;
  expect(saved.ok(), await saved.text()).toBeTruthy();
  await expect(page.getByRole('dialog')).not.toBeVisible();
}

async function readChain(page: Page, chainId: string): Promise<Chain> {
  const response = await page.request.get('/api/admin/overview');
  expect(response.ok()).toBeTruthy();
  const data = await response.json();
  const chain = data.chains.find((chain: Chain) => chain.id === chainId);
  expect(chain).toBeTruthy();
  return chain;
}

async function raster(page: Page, color: string, mimeType = 'image/png') {
  // Real, browser-decoded raster fixtures without an extra image dependency.
  const dataUrl = await page.evaluate(
    ({ color, mimeType }) => {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 64;
      const context = canvas.getContext('2d')!;
      context.fillStyle = color;
      context.fillRect(0, 0, 64, 64);
      context.fillStyle = '#fff';
      context.font = 'bold 44px sans-serif';
      context.textAlign = 'center';
      context.fillText('N', 32, 48);
      return canvas.toDataURL(mimeType);
    },
    { color, mimeType },
  );
  return {
    name: mimeType === 'image/png' ? 'logo-cadena.png' : 'logo-cadena.jpg',
    mimeType,
    buffer: Buffer.from(dataUrl.split(',')[1], 'base64'),
  };
}

const evidenceDirectory = fileURLToPath(new URL('../../.runtime/logo-qa/', import.meta.url));

async function screenshot(page: Page, name: string) {
  mkdirSync(evidenceDirectory, { recursive: true });
  await page.screenshot({ path: `${evidenceDirectory}/${name}.png`, fullPage: true });
}

async function openMap(page: Page) {
  await page.goto('/app');
  await page.getByRole('button', { name: 'Nueva compra', exact: true }).click();
  await page.getByRole('button', { name: 'Usar mi ubicación actual', exact: true }).click();
  await expect(page.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
}

function mapLogo(page: Page, chainId: string) {
  return page.locator(`.leaflet-marker-pane img[src*="/api/catalog/chains/${chainId}/logo"]`);
}

async function expectLogoContained(page: Page, chainId: string) {
  const bounds = await mapLogo(page, chainId)
    .first()
    .evaluate((image) => {
      const face = image.closest('.emedicapp-branch-marker__face')!;
      const imageBounds = image.getBoundingClientRect();
      const faceBounds = face.getBoundingClientRect();
      return {
        left: imageBounds.left - faceBounds.left,
        top: imageBounds.top - faceBounds.top,
        right: faceBounds.right - imageBounds.right,
        bottom: faceBounds.bottom - imageBounds.bottom,
      };
    });
  for (const [edge, margin] of Object.entries(bounds)) {
    expect(
      margin,
      `El logo queda recortado por el borde ${edge} del marcador.`,
    ).toBeGreaterThanOrEqual(-0.5);
  }
}

async function waitForMapTiles(page: Page) {
  await expect
    .poll(
      () =>
        page.locator('img.leaflet-tile-loaded').evaluateAll(
          (tiles) =>
            tiles.filter((tile) => {
              const image = tile as HTMLImageElement;
              return image.complete && image.naturalWidth > 0;
            }).length,
        ),
      { timeout: 15000, message: 'El mapa debe mostrar sus imágenes antes de guardar la captura.' },
    )
    .toBeGreaterThanOrEqual(4);
}

test('logo de cadena: carga, persistencia, mapa, reemplazo y eliminación con validaciones', async ({
  browser,
}) => {
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const buyerContext = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    geolocation: { latitude: -32.889, longitude: -68.845 },
    permissions: ['geolocation'],
  });
  const admin = await adminContext.newPage();
  const buyer = await buyerContext.newPage();
  admin.setDefaultTimeout(15000);
  buyer.setDefaultTimeout(15000);
  const failures: string[] = [];
  admin.on('pageerror', (error) => failures.push(error.message));
  buyer.on('pageerror', (error) => failures.push(error.message));

  try {
    await login(admin, 'Administrador');
    const branchesResponse = await admin.request.get('/api/catalog/branches');
    expect(branchesResponse.ok()).toBeTruthy();
    const branches: Branch[] = await branchesResponse.json();
    const branch = branches.find((branch) => branch.name === 'Nivalis · Ciudad');
    expect(branch?.chainId).toBeTruthy();
    const chainId = branch!.chainId!;
    const initialChain = await readChain(admin, chainId);
    expect(initialChain.logoUrl).toBeNull();
    let editor = await openChain(admin, initialChain.name);
    const input = editor.getByLabel('Logo de la cadena', { exact: true });

    await input.setInputFiles({
      name: 'logo.svg',
      mimeType: 'image/svg+xml',
      buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'),
    });
    await expect(
      editor.getByText('Elegí una imagen PNG o JPG para el logo.', { exact: true }),
    ).toBeVisible();
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveCount(0);
    await input.setInputFiles({
      name: 'logo-grande.png',
      mimeType: 'image/png',
      buffer: Buffer.alloc(2 * 1024 * 1024 + 1),
    });
    await expect(
      editor.getByText('El logo supera los 2 MB. Elegí una imagen más liviana.', { exact: true }),
    ).toBeVisible();
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveCount(0);
    await input.setInputFiles({
      name: 'logo-invalido.png',
      mimeType: 'image/png',
      buffer: Buffer.from('Este archivo no contiene una imagen.'),
    });
    await expect(
      editor.getByText('El archivo no contiene una imagen PNG o JPG válida.', { exact: true }),
    ).toBeVisible();
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveCount(0);

    const firstLogo = await raster(admin, '#0c6780');
    const fileChooser = admin.waitForEvent('filechooser');
    await editor.getByRole('button', { name: 'Seleccionar imagen', exact: true }).click();
    await (await fileChooser).setFiles(firstLogo);
    const preview = editor.getByRole('img', { name: 'Vista previa del logo de la cadena' });
    await expect(preview).toBeVisible();
    await expect
      .poll(() =>
        preview.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth === 64),
      )
      .toBeTruthy();
    await screenshot(admin, 'logo-formulario-desktop');
    await saveChain(admin, chainId);

    await admin.reload();
    let savedChain = await readChain(admin, chainId);
    expect(savedChain.logoUrl).toMatch(new RegExp(`^/api/catalog/chains/${chainId}/logo\\?v=`));
    const firstUrl = savedChain.logoUrl!;
    const imageResponse = await admin.request.get(firstUrl);
    expect(imageResponse.ok()).toBeTruthy();
    expect(imageResponse.headers()['content-type']).toMatch(/^image\/png/);
    const firstBytes = await imageResponse.body();
    expect(firstBytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(firstBytes.byteLength).toBeGreaterThan(32);

    editor = await openChain(admin, initialChain.name);
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveAttribute('src', firstUrl);
    // An ordinary chain edit preserves the logo without requiring another upload.
    const updatedName = `${initialChain.name} QA logo`;
    await test.step('Guardar una edición de nombre conservando el logo', async () => {
      await editor
        .getByRole('textbox', { name: 'Nombre de la cadena', exact: true })
        .fill(updatedName);
      await saveChain(admin, chainId);
    });
    savedChain = await readChain(admin, chainId);
    expect(savedChain.logoUrl).toBeTruthy();
    const preservedResponse = await admin.request.get(savedChain.logoUrl!);
    expect(preservedResponse.ok()).toBeTruthy();
    expect((await preservedResponse.body()).equals(firstBytes)).toBeTruthy();

    await login(buyer, 'Comprador');
    const inherited: Branch[] = await (await buyer.request.get('/api/catalog/branches')).json();
    expect(inherited.filter((candidate) => candidate.chainId === chainId).length).toBeGreaterThan(
      0,
    );
    for (const candidate of inherited.filter((candidate) => candidate.chainId === chainId)) {
      expect(candidate.chainLogoUrl).toBe(savedChain.logoUrl);
    }
    await openMap(buyer);
    await expect(mapLogo(buyer, chainId).first()).toBeVisible();
    await expect
      .poll(() =>
        mapLogo(buyer, chainId)
          .first()
          .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0),
      )
      .toBeTruthy();
    await expectLogoContained(buyer, chainId);
    const branchMarker = buyer.getByRole('button', {
      name: `Sucursal ${branch!.name}`,
      exact: true,
    });
    await branchMarker.click();
    await expect(
      buyer.locator('.leaflet-popup-content').getByText(branch!.name, { exact: true }),
    ).toBeVisible();
    await expect(buyer.getByText('Punto confirmado en el mapa.', { exact: false })).toBeVisible();
    await waitForMapTiles(buyer);
    await screenshot(buyer, 'logo-mapa-desktop');
    await buyer.getByRole('region', { name: 'Mapa para confirmar ubicación' }).screenshot({
      path: `${evidenceDirectory}/logo-marcadores-detalle.png`,
    });
    await buyer.setViewportSize({ width: 390, height: 844 });
    await expect
      .poll(() => buyer.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBeTruthy();
    await expect(mapLogo(buyer, chainId).first()).toBeVisible();
    await expectLogoContained(buyer, chainId);
    await waitForMapTiles(buyer);
    await screenshot(buyer, 'logo-mapa-movil');
    await buyer.getByRole('region', { name: 'Mapa para confirmar ubicación' }).screenshot({
      path: `${evidenceDirectory}/logo-marcadores-movil.png`,
    });

    // Check the coordinates actually submitted after opening a branch popup. The isolated
    // browser intercepts this one POST so this check never creates or reserves a purchase.
    await buyer
      .getByRole('textbox', { name: 'Dirección completa de entrega', exact: true })
      .fill('QA logo 123, Mendoza');
    await buyer.getByRole('button', { name: 'Continuar a medicamentos', exact: true }).click();
    await buyer.getByLabel('Cantidad de Paracetamol Nivalen 500 mg', { exact: true }).fill('1');
    await buyer.getByRole('button', { name: 'Continuar a cobertura', exact: true }).click();
    await buyer.getByRole('button', { name: 'Revisar solicitud', exact: true }).click();
    const purchasePath = '**/api/purchases';
    const stoppedPurchase = 'Verificación aislada: la compra no se envió.';
    const preventPurchase = async (route: Route) => {
      if (route.request().method() !== 'POST') return route.continue();
      await route.fulfill({
        status: 400,
        json: { code: 'QA_POINT_ONLY', detail: stoppedPurchase },
      });
    };
    await buyer.route(purchasePath, preventPurchase);
    try {
      const submitted = buyer.waitForRequest(
        (request) =>
          new URL(request.url()).pathname === '/api/purchases' && request.method() === 'POST',
      );
      await buyer
        .getByRole('button', { name: 'Solicitar propuesta a la farmacia', exact: true })
        .click();
      expect((await submitted).postDataJSON()).toMatchObject({
        destinationLat: -32.889,
        destinationLon: -68.845,
      });
      await expect(buyer.getByText(stoppedPurchase, { exact: true })).toBeVisible();
    } finally {
      await buyer.unroute(purchasePath, preventPurchase);
    }

    editor = await openChain(admin, updatedName);
    const replacement = await raster(admin, '#8b3c65', 'image/jpeg');
    await editor.getByLabel('Logo de la cadena', { exact: true }).setInputFiles(replacement);
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveAttribute('src', /^data:image\/jpeg;base64,/);
    await saveChain(admin, chainId);
    const replaced = await readChain(admin, chainId);
    expect(replaced.logoUrl).not.toBe(savedChain.logoUrl);
    const replacedResponse = await admin.request.get(replaced.logoUrl!);
    expect(replacedResponse.ok()).toBeTruthy();
    // The server normalizes accepted PNG/JPEG uploads to PNG before persistence.
    expect(replacedResponse.headers()['content-type']).toMatch(/^image\/png/);
    const replacedBytes = await replacedResponse.body();
    expect(replacedBytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(replacedBytes.equals(firstBytes)).toBeFalsy();
    await openMap(buyer);
    await expect(mapLogo(buyer, chainId).first()).toHaveAttribute('src', replaced.logoUrl!);
    await expect
      .poll(() =>
        mapLogo(buyer, chainId)
          .first()
          .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0),
      )
      .toBeTruthy();

    editor = await openChain(admin, updatedName);
    await editor.getByRole('button', { name: 'Quitar logo', exact: true }).click();
    await expect(
      editor.getByRole('img', { name: 'Vista previa del logo de la cadena' }),
    ).toHaveCount(0);
    await expect(
      editor.getByText('Sin logo. Se usará el marcador común.', { exact: true }),
    ).toBeVisible();
    await editor
      .getByRole('textbox', { name: 'Nombre de la cadena', exact: true })
      .fill(initialChain.name);
    await saveChain(admin, chainId);
    await admin.reload();
    expect((await readChain(admin, chainId)).logoUrl).toBeNull();
    const withoutLogo: Branch[] = await (await buyer.request.get('/api/catalog/branches')).json();
    expect(withoutLogo.find((candidate) => candidate.id === branch!.id)?.chainLogoUrl).toBeNull();
    expect((await buyer.request.get(replaced.logoUrl!)).status()).toBe(404);
    await openMap(buyer);
    await expect(mapLogo(buyer, chainId)).toHaveCount(0);
    const fallbackMarker = buyer.getByRole('button', {
      name: `Sucursal ${branch!.name}`,
      exact: true,
    });
    await expect(fallbackMarker).toBeVisible();
    await expect(fallbackMarker.locator('.emedicapp-branch-marker__fallback')).toBeVisible();
    await screenshot(buyer, 'logo-mapa-sin-logo-movil');
    expect(failures).toEqual([]);
  } finally {
    await Promise.allSettled([adminContext.close(), buyerContext.close()]);
  }
});
