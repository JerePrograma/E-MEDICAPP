import { describe, expect, it } from 'vitest';
import { cartStorageKey, remainingCart, restoreCart } from './state';

const first = '00000000-0000-0000-0000-000000000001';
const second = '00000000-0000-0000-0000-000000000002';
const third = '00000000-0000-0000-0000-000000000003';

describe('carrito guardado por cuenta y servidor', () => {
  it('separa cuentas y servidores aunque los identificadores de producto coincidan', () => {
    expect(cartStorageKey(first, 'https://a.example')).not.toBe(
      cartStorageKey(second, 'https://a.example'),
    );
    expect(cartStorageKey(first, 'https://a.example')).not.toBe(
      cartStorageKey(first, 'https://b.example'),
    );
  });
  it('recupera cantidades válidas y excluye datos ajenos al carrito', () => {
    expect(
      restoreCart(
        JSON.stringify({
          items: { [first]: 2, [second]: 0, [third]: 1.5, invalid: 4 },
          preferredBranchId: first,
          prescription: 'private',
          patient: 'private',
        }),
      ),
    ).toEqual({ items: { [first]: 2 }, preferredBranchId: first });
    expect(restoreCart('{invalid')).toEqual({ items: {}, preferredBranchId: '' });
    expect(
      restoreCart(
        JSON.stringify({ items: { [first]: 101, [second]: '2' }, preferredBranchId: 'invalid' }),
      ),
    ).toEqual({ items: {}, preferredBranchId: '' });
  });
  it('limita la recuperación a treinta productos y cien unidades por producto', () => {
    const items = Object.fromEntries(
      Array.from({ length: 35 }, (_, i) => [
        `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`,
        100,
      ]),
    );
    const restored = restoreCart(JSON.stringify({ items }));
    expect(Object.keys(restored.items)).toHaveLength(30);
    expect(Object.values(restored.items).every((quantity) => quantity === 100)).toBe(true);
  });
  it('descuenta sólo las cantidades enviadas y conserva cambios posteriores', () => {
    const current = { [first]: 5, [second]: 1, [third]: 3 };
    expect(remainingCart(current, { [first]: 2, [second]: 2 })).toEqual({ [first]: 3, [third]: 3 });
    expect(current).toEqual({ [first]: 5, [second]: 1, [third]: 3 });
    expect(remainingCart(current, { [first]: -1 })).toEqual(current);
  });
});
