export type CartState = { items: Record<string, number>; preferredBranchId: string };
export const MAX_CART_LINES = 30;
export const MAX_CART_QUANTITY = 100;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function cartStorageKey(actorId: string, backend: string) {
  return `emedicapp.cart.v1:${encodeURIComponent(backend)}:${actorId}`;
}

export function restoreCart(value: string | null): CartState {
  const empty: CartState = { items: {}, preferredBranchId: '' };
  try {
    const data: unknown = JSON.parse(value ?? 'null');
    if (
      !data ||
      typeof data !== 'object' ||
      !('items' in data) ||
      !data.items ||
      typeof data.items !== 'object' ||
      Array.isArray(data.items)
    )
      return empty;
    const items: Record<string, number> = {};
    for (const [id, quantity] of Object.entries(data.items)) {
      if (Object.keys(items).length >= MAX_CART_LINES) break;
      if (
        ID.test(id) &&
        typeof quantity === 'number' &&
        Number.isInteger(quantity) &&
        quantity > 0 &&
        quantity <= MAX_CART_QUANTITY
      )
        items[id] = quantity;
    }
    return {
      items,
      preferredBranchId:
        'preferredBranchId' in data &&
        typeof data.preferredBranchId === 'string' &&
        ID.test(data.preferredBranchId)
          ? data.preferredBranchId
          : '',
    };
  } catch {
    return empty;
  }
}

export function remainingCart(items: Record<string, number>, submitted: Record<string, number>) {
  const remaining: Record<string, number> = {};
  for (const [id, quantity] of Object.entries(items)) {
    const sent = submitted[id];
    const next =
      quantity - (typeof sent === 'number' && Number.isInteger(sent) && sent > 0 ? sent : 0);
    if (next > 0) remaining[id] = next;
  }
  return remaining;
}

export function validCartId(id: string) {
  return ID.test(id);
}
