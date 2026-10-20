import { describe, expect, it } from 'vitest';
import { catalogProducts, categoryFor } from './categories';
import type { Product } from './types';

function product(
  id: string,
  metadata: Record<string, unknown>,
  requiresPrescription = false,
): Product {
  return {
    id,
    code: `SKU-${id}`,
    name: id === 'skin' ? 'Protector solar FPS 50' : id,
    presentation: 'Envase de 50 ml',
    active: true,
    requiresPrescription,
    coldChain: false,
    restricted: false,
    version: 0,
    metadata,
  };
}

describe('categorías del catálogo', () => {
  it('conserva el requisito de receta aunque la categoría comercial sea otra', () => {
    expect(categoryFor(product('rx', { catalogCategory: 'BEAUTY' }, true))).toBe('PRESCRIPTION');
  });

  it('acepta categorías explícitas y usa venta libre para datos anteriores o desconocidos', () => {
    expect(categoryFor(product('skin', { catalogCategory: 'SKIN_CARE' }))).toBe('SKIN_CARE');
    expect(categoryFor(product('legacy', {}))).toBe('NON_PRESCRIPTION');
    expect(categoryFor(product('first-aid', { catalogCategory: 'FIRST_AID' }))).toBe('FIRST_AID');
    expect(categoryFor(product('household', { catalogCategory: 'HOUSEHOLD' }))).toBe('HOUSEHOLD');
    expect(categoryFor(product('invalid', { catalogCategory: 'UNKNOWN' }))).toBe(
      'NON_PRESCRIPTION',
    );
  });

  it('combina categoría y búsqueda sin incluir productos inactivos ni mezclar promociones', () => {
    const skin = product('skin', { catalogCategory: 'SKIN_CARE' });
    const hidden = { ...skin, id: 'hidden', active: false };
    const rx = product('rx', {}, true);
    expect(
      catalogProducts([skin, hidden, rx], 'SKIN_CARE', 'solar 50').map((item) => item.id),
    ).toEqual(['skin']);
    expect(catalogProducts([skin, rx], 'PRESCRIPTION', '').map((item) => item.id)).toEqual(['rx']);
    expect(catalogProducts([skin, rx], 'PROMOTIONS', '')).toEqual([]);
  });
  it('reúne medicamentos con y sin receta sin incorporar cuidado personal', () => {
    const rx = product('rx', {}, true);
    const otc = product('otc', {});
    const care = product('care', { catalogCategory: 'PERSONAL_CARE' });
    expect(catalogProducts([rx, otc, care], 'MEDICINES', '').map((item) => item.id)).toEqual([
      'rx',
      'otc',
    ]);
  });
});
