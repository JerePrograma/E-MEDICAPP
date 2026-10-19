import { useId, useRef, useState } from 'react';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { useApiImage } from '../components/ApiImage';

const MAX_LOGO_BYTES = 2 * 1024 * 1024;

async function readLogo(file: File): Promise<string> {
  if (!['image/png', 'image/jpeg'].includes(file.type))
    throw new Error('Elegí una imagen PNG o JPG para el logo.');
  if (file.size > MAX_LOGO_BYTES)
    throw new Error('El logo supera los 2 MB. Elegí una imagen más liviana.');
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('No se pudo leer la imagen. Volvé a seleccionarla.'));
    reader.readAsDataURL(file);
  });
  await new Promise<void>((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      if (image.naturalWidth > 4096 || image.naturalHeight > 4096)
        reject(new Error('El logo puede tener hasta 4096 × 4096 píxeles.'));
      else resolve();
    };
    image.onerror = () => reject(new Error('El archivo no contiene una imagen PNG o JPG válida.'));
    image.src = dataUrl;
  });
  return dataUrl;
}

export function ChainLogoInput({
  savedUrl,
  logoDataUrl,
  removeLogo,
  disabled,
  onReading,
  onChange,
}: {
  savedUrl: string | null;
  logoDataUrl: string | null;
  removeLogo: boolean;
  disabled: boolean;
  onReading: (reading: boolean) => void;
  onChange: (image: string | null, remove: boolean) => void;
}) {
  const inputId = useId();
  const helpId = useId();
  const request = useRef(0);
  const input = useRef<HTMLInputElement>(null);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState('');
  const [failedPreview, setFailedPreview] = useState<string | null>(null);
  const savedImage = useApiImage(savedUrl);
  const preview = logoDataUrl ?? (removeLogo ? null : savedImage);
  const changeReading = (value: boolean) => {
    setReading(value);
    onReading(value);
  };
  return (
    <Stack spacing={1.5}>
      <Typography component="label" htmlFor={inputId} sx={{ fontWeight: 650 }}>
        Logo de la cadena
      </Typography>
      <Typography id={helpId} variant="body2" color="text.secondary">
        Se muestra en el mapa de todas las sucursales de esta cadena. Usá una imagen PNG o JPG de
        hasta 2 MB y 4096 × 4096 píxeles.
      </Typography>
      {preview && preview !== failedPreview ? (
        <Box
          component="img"
          src={preview}
          alt="Vista previa del logo de la cadena"
          onError={() => setFailedPreview(preview)}
          sx={{
            width: 112,
            height: 112,
            objectFit: 'contain',
            p: 1,
            bgcolor: 'white',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 2,
          }}
        />
      ) : (
        <Typography variant="body2" color="text.secondary">
          {preview
            ? 'No se pudo mostrar la vista previa.'
            : 'Sin logo. Se usará el marcador común.'}
        </Typography>
      )}
      <Button
        type="button"
        variant="outlined"
        disabled={disabled || reading}
        sx={{ alignSelf: 'flex-start' }}
        onClick={() => input.current?.click()}
      >
        {preview ? 'Reemplazar imagen' : 'Seleccionar imagen'}
      </Button>
      <Box
        component="input"
        id={inputId}
        ref={input}
        type="file"
        accept="image/png,image/jpeg"
        aria-describedby={helpId}
        disabled={disabled}
        sx={{ display: 'none' }}
        onChange={async (event) => {
          const file = event.target.files?.[0];
          if (!file) return;
          const current = ++request.current;
          setError('');
          changeReading(true);
          try {
            const image = await readLogo(file);
            if (current === request.current) {
              setFailedPreview(null);
              onChange(image, false);
            }
          } catch (failure) {
            if (current === request.current)
              setError(failure instanceof Error ? failure.message : 'No se pudo leer la imagen.');
          } finally {
            if (current === request.current) {
              changeReading(false);
              if (input.current) input.current.value = '';
            }
          }
        }}
      />
      {reading && <Typography variant="body2">Preparando imagen…</Typography>}
      {error && <Alert severity="error">{error}</Alert>}
      {preview && (
        <Button
          type="button"
          disabled={disabled || reading}
          sx={{ alignSelf: 'flex-start' }}
          onClick={() => {
            setError('');
            onChange(null, true);
            if (input.current) input.current.value = '';
          }}
        >
          Quitar logo
        </Button>
      )}
    </Stack>
  );
}
