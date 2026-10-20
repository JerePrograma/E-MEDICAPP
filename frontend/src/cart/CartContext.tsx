import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { backendUrl } from '../connection';
import {
  cartStorageKey,
  MAX_CART_LINES,
  MAX_CART_QUANTITY,
  remainingCart,
  restoreCart,
  validCartId,
  type CartState,
} from './state';

type CartContextValue = CartState & {
  totalUnits: number;
  lineCount: number;
  notice: string;
  storageWarning: boolean;
  addItem: (id: string, quantity?: number) => boolean;
  updateQuantity: (id: string, quantity: number) => boolean;
  removeItem: (id: string) => void;
  clearCart: () => void;
  removeSubmitted: (items: Record<string, number>) => void;
  setPreferredBranch: (id: string) => void;
};
const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ actorId, children }: { actorId: string; children: ReactNode }) {
  const key = cartStorageKey(actorId, backendUrl() || window.location.origin);
  const [storageWarning, setStorageWarning] = useState(false);
  const [notice, setNotice] = useState('');
  const [state, setState] = useState(() => {
    try {
      return restoreCart(window.localStorage.getItem(key));
    } catch {
      return restoreCart(null);
    }
  });
  const current = useRef(state);
  function commit(next: CartState) {
    current.current = next;
    setState(next);
    try {
      window.localStorage.setItem(key, JSON.stringify(next));
      setStorageWarning(false);
    } catch {
      setStorageWarning(true);
    }
  }
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      if (event.key !== key || event.storageArea !== window.localStorage) return;
      const next = restoreCart(event.newValue);
      current.current = next;
      setState(next);
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, [key]);
  function updateQuantity(id: string, quantity: number) {
    if (
      !validCartId(id) ||
      !Number.isInteger(quantity) ||
      quantity < 0 ||
      quantity > MAX_CART_QUANTITY
    ) {
      setNotice('Elegí una cantidad entera entre 1 y 100 unidades.');
      return false;
    }
    const previous = current.current;
    if (
      quantity > 0 &&
      !previous.items[id] &&
      Object.keys(previous.items).length >= MAX_CART_LINES
    ) {
      setNotice(
        'El carrito admite hasta treinta productos diferentes. Quitá uno para agregar otro.',
      );
      return false;
    }
    const items = { ...previous.items };
    if (quantity === 0) delete items[id];
    else items[id] = quantity;
    commit({ ...previous, items });
    setNotice('');
    return true;
  }
  const value: CartContextValue = {
    ...state,
    totalUnits: Object.values(state.items).reduce((total, quantity) => total + quantity, 0),
    lineCount: Object.keys(state.items).length,
    notice,
    storageWarning,
    updateQuantity,
    addItem: (id, quantity = 1) => {
      if (!Number.isInteger(quantity) || quantity < 1) {
        setNotice('Elegí al menos una unidad.');
        return false;
      }
      return updateQuantity(id, (current.current.items[id] ?? 0) + quantity);
    },
    removeItem: (id) => {
      updateQuantity(id, 0);
    },
    clearCart: () => {
      commit({ items: {}, preferredBranchId: '' });
      setNotice('');
    },
    setPreferredBranch: (id) => {
      if (!id || validCartId(id)) commit({ ...current.current, preferredBranchId: id });
    },
    removeSubmitted: (submitted) => {
      let latest = current.current;
      try {
        const stored = window.localStorage.getItem(key);
        if (!storageWarning && stored !== null) latest = restoreCart(stored);
      } catch {
        /* Keep the in-memory cart when storage is unavailable. */
      }
      const items = remainingCart(latest.items, submitted);
      commit({
        items,
        preferredBranchId: Object.keys(items).length ? latest.preferredBranchId : '',
      });
      setNotice('');
    },
  };
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart() {
  const cart = useContext(CartContext);
  if (!cart) throw new Error('El carrito debe abrirse dentro de la sesión de compra.');
  return cart;
}
