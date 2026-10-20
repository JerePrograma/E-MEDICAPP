import type { Product } from './types';
import { filterProducts } from '../purchases/productSearch';

export const catalogCategories = [
  { value: 'PRESCRIPTION', label: 'Medicamentos con receta', color: '#4d689a', tint: '#edf2fb' },
  {
    value: 'NON_PRESCRIPTION',
    label: 'Medicamentos sin receta',
    color: '#16756d',
    tint: '#e6f5f0',
  },
  { value: 'SEXUAL_HEALTH', label: 'Salud Sexual', color: '#a65b7c', tint: '#fbeef3' },
  { value: 'SKIN_CARE', label: 'Cuidado de Piel', color: '#ab724c', tint: '#fff2e8' },
  { value: 'BEAUTY', label: 'Belleza y Estética', color: '#8861a2', tint: '#f4edf9' },
  {
    value: 'VITAMINS_SUPPLEMENTS',
    label: 'Vitaminas y Suplementos',
    color: '#8b762c',
    tint: '#faf5dc',
  },
  { value: 'FIRST_AID', label: 'Primeros auxilios', color: '#b65d59', tint: '#fff0ed' },
  { value: 'BABY', label: 'Bebés', color: '#6485b1', tint: '#edf4fd' },
  { value: 'PERSONAL_CARE', label: 'Cuidado personal', color: '#5f8b80', tint: '#eaf6ef' },
  { value: 'MEDICAL_SUPPLIES', label: 'Insumos médicos', color: '#537e9a', tint: '#edf6fb' },
  { value: 'FOOD_DRINK', label: 'Alimentos y Bebidas', color: '#a78642', tint: '#faf4e7' },
  { value: 'HOUSEHOLD', label: 'Limpieza y Hogar', color: '#678f92', tint: '#edf8f7' },
] as const;

export type CatalogCategory = (typeof catalogCategories)[number]['value'];
export type CatalogSelection = CatalogCategory | 'ALL' | 'PROMOTIONS' | 'MEDICINES';

export const featuredCategories: {
  value: Exclude<CatalogSelection, 'ALL' | 'PRESCRIPTION' | 'NON_PRESCRIPTION'>;
  label: string;
  image: string | null;
}[] = [
  { value: 'PROMOTIONS', label: 'Promociones', image: null },
  { value: 'MEDICINES', label: 'Medicamentos', image: 'medicines' },
  { value: 'SEXUAL_HEALTH', label: 'Salud Sexual', image: 'sexual-health' },
  { value: 'SKIN_CARE', label: 'Cuidado de Piel', image: 'skin-care' },
  { value: 'BEAUTY', label: 'Belleza y Estética', image: 'beauty' },
  { value: 'VITAMINS_SUPPLEMENTS', label: 'Vitaminas y Suplementos', image: 'vitamins' },
  { value: 'FIRST_AID', label: 'Primeros auxilios', image: 'first-aid' },
  { value: 'BABY', label: 'Bebés', image: 'baby' },
  { value: 'PERSONAL_CARE', label: 'Cuidado personal', image: 'personal-care' },
  { value: 'MEDICAL_SUPPLIES', label: 'Insumos médicos', image: 'medical-supplies' },
  { value: 'FOOD_DRINK', label: 'Alimentos y Bebidas', image: 'food-drink' },
  { value: 'HOUSEHOLD', label: 'Limpieza y Hogar', image: 'household' },
];

export function categoryFor(product: Product): CatalogCategory {
  // A prescription requirement always wins over merchandising metadata.
  if (product.requiresPrescription) return 'PRESCRIPTION';
  const value = product.metadata?.catalogCategory;
  return catalogCategories.some((category) => category.value === value)
    ? (value as CatalogCategory)
    : 'NON_PRESCRIPTION';
}

export function categoryDetails(product: Product) {
  return catalogCategories.find((category) => category.value === categoryFor(product))!;
}

export function catalogProducts(products: Product[], selection: CatalogSelection, search: string) {
  if (selection === 'PROMOTIONS') return [];
  return filterProducts(products, search).filter(
    (product) =>
      selection === 'ALL' ||
      (selection === 'MEDICINES'
        ? ['PRESCRIPTION', 'NON_PRESCRIPTION'].includes(categoryFor(product))
        : categoryFor(product) === selection),
  );
}
