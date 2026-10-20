import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';

test('Ionic: encabezado fijo, navegación y catálogo en escritorio y celular', async ({
  page,
  request,
}) => {
  expect(process.env.EMEDICAPP_WORKFLOW_DB).toMatch(/^emedicapp_workflow_[a-z0-9_]+_demo$/);
  expect(new URL(process.env.EMEDICAPP_E2E_URL ?? '').origin).toBe('http://127.0.0.1:8083');
  expect((await (await request.get('/api/bootstrap')).json()).mode).toBe('DEMO');
  mkdirSync('../.runtime/ionic-qa', { recursive: true });
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/');
  await expect(page.locator('ion-app')).toBeVisible();
  await expect(page.locator('ion-page, .ion-page').first()).toBeVisible();
  await page.getByRole('combobox', { name: 'Perfil de demostración' }).click();
  await page.getByRole('option', { name: /^Comprador ·/ }).click();
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await page.getByRole('link', { name: 'Catálogo', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Catálogo', level: 1, exact: true }),
  ).toBeVisible();
  const content = page.locator('ion-content#application-content');
  await expect(content).toBeVisible();
  for (const viewport of [
    { width: 1440, height: 1000 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await content.evaluate(async (element) => {
      await (
        element as HTMLElement & { scrollToBottom: (duration: number) => Promise<void> }
      ).scrollToBottom(0);
    });
    await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeInViewport();
    const header = await page.locator('ion-header').boundingBox();
    expect(header!.y).toBe(0);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({ path: `../.runtime/ionic-qa/shell-${viewport.width}.png` });
  }
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ingresar', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
