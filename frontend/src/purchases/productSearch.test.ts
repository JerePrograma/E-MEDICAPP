import { describe, expect, it } from 'vitest';
import type { Product } from '../catalog/types';
import { filterProducts } from './productSearch';

const catalog: Product[] = [
  {
    id: 'losartan',
    code: 'EMC-I-2026',
    name: 'Losartán Losavia 50 mg',
    presentation: 'Caja de 30 comprimidos recubiertos',
    active: true,
  },
  {
    id: 'paracetamol16',
    code: 'EMC-A-2026',
    name: 'Paracetamol Nivalen 500 mg',
    presentation: 'Caja de 16 comprimidos',
    active: true,
  },
  {
    id: 'paracetamol20',
    code: 'P-20',
    name: 'Paracetamol Nivalen 500 mg',
    presentation: 'Caja de 20 comprimidos',
    active: true,
  },
  { id: 'inactive', code: 'INACTIVE', name: 'Inactivo', presentation: 'Caja', active: false },
] as Product[];

describe('búsqueda de presentaciones de compra', () => {
  it('encuentra nombres con o sin tildes, palabras en distinto orden y espacios adicionales', () => {
    expect(filterProducts(catalog, '  50   LOSARTAN  ').map((x) => x.id)).toEqual(['losartan']);
    expect(filterProducts(catalog, 'LOSARTÁN').map((x) => x.id)).toEqual(['losartan']);
  });

  it('distingue envases y permite buscar el código de catálogo', () => {
    expect(filterProducts(catalog, 'paracetamol 20').map((x) => x.id)).toEqual(['paracetamol20']);
    expect(filterProducts(catalog, 'emc-a-2026').map((x) => x.id)).toEqual(['paracetamol16']);
  });

  it('devuelve el catálogo activo al limpiar y no inventa coincidencias para marcas ausentes', () => {
    expect(filterProducts(catalog, '').map((x) => x.id)).toEqual([
      'losartan',
      'paracetamol16',
      'paracetamol20',
    ]);
    expect(filterProducts(catalog, 'buscapina')).toEqual([]);
  });
});
