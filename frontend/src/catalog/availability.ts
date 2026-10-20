import type { Branch } from './types';

export type ProductOffer = {
  branch: Branch;
  chainName: string;
  price: string;
  currency: string;
  availableQuantity: number;
  observedAt: string;
};

export type ProductAvailability = {
  productId: string;
  offers: ProductOffer[];
};
