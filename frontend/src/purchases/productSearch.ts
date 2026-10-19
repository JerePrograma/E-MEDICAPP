import type { Product } from '../catalog/types';

function normalized(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase('es-AR')
    .trim();
}

export function filterProducts(products: Product[], search: string): Product[] {
  const query = normalized(search);
  const words = query.split(/\s+/).filter(Boolean);
  return products.filter((product) => {
    const text = normalized(`${product.name} ${product.presentation}`);
    return (
      product.active &&
      (words.every((word) => text.includes(word)) || normalized(product.code).includes(query))
    );
  });
}
