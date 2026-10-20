import { useState, type ReactNode } from 'react';
import { Box, type SxProps, type Theme } from '@mui/material';
import { useApiImage } from '../components/ApiImage';
import type { Product } from './types';

export function ProductImage({
  product,
  fallback = null,
  sx,
}: {
  product: Product;
  fallback?: ReactNode;
  sx?: SxProps<Theme>;
}) {
  const source = useApiImage(product.imageUrl ?? null);
  const [failed, setFailed] = useState<string | null>(null);
  if (!source || source === failed) return fallback;
  return (
    <Box
      component="img"
      src={source}
      alt={`Foto de ${product.name}`}
      loading="lazy"
      onError={() => setFailed(source)}
      sx={sx}
    />
  );
}
