import { useEffect, useState } from 'react';
import { Box, type SxProps, type Theme } from '@mui/material';
import { apiFetch, apiUrl } from '../api';
import { usesHeaderSessions } from '../connection';

export function useApiImage(path: string | null): string | null {
  const [image, setImage] = useState<{ path: string; url: string } | null>(null);
  // A proxy with a base path also needs the session header on the frontend's own origin.
  const headerSession = usesHeaderSessions();
  useEffect(() => {
    if (!path || !headerSession || !path.startsWith('/api/')) return;
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
  }, [path, headerSession]);
  if (!path) return null;
  if (!headerSession || !path.startsWith('/api/')) return apiUrl(path);
  return image?.path === path ? image.url : null;
}

export function ApiImage({ src, alt, sx }: { src: string; alt: string; sx?: SxProps<Theme> }) {
  const image = useApiImage(src);
  return image ? <Box component="img" src={image} alt={alt} sx={sx} /> : null;
}
