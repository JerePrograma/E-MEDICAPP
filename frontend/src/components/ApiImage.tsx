import { useEffect, useState } from 'react';
import { Box, type SxProps, type Theme } from '@mui/material';
import { apiFetch, apiUrl } from '../api';
import { crossOriginBackend } from '../connection';

export function useApiImage(path: string | null): string | null {
  const [image, setImage] = useState<{ path: string; url: string } | null>(null);
  const remote = crossOriginBackend();
  useEffect(() => {
    if (!path || !remote || !path.startsWith('/api/')) return;
    let active = true;
    let objectUrl: string | undefined;
    void apiFetch(path)
      .then(async (response) => {
        if (!response.ok) throw new Error('No se pudo cargar la imagen.');
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setImage({ path, url: objectUrl });
      })
      .catch(() => {
        if (active) setImage(null);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [path, remote]);
  if (!path) return null;
  if (!remote || !path.startsWith('/api/')) return apiUrl(path);
  return image?.path === path ? image.url : null;
}

export function ApiImage({ src, alt, sx }: { src: string; alt: string; sx?: SxProps<Theme> }) {
  const image = useApiImage(src);
  return image ? <Box component="img" src={image} alt={alt} sx={sx} /> : null;
}
