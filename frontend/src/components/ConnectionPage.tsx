import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Container,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import {
  backendUrl,
  canConfigureBackend,
  normalizeBackendUrl,
  saveBackendUrl,
} from '../connection';

export function ServiceUnavailable() {
  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Card>
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={3}>
            <Typography component="h1" variant="h5">
              E-MEDICAPP no está disponible
            </Typography>
            <Typography color="text.secondary">
              No pudimos abrir el servicio en este momento. Volvé a intentarlo en unos minutos.
            </Typography>
            <Button variant="contained" onClick={() => window.location.reload()}>
              Reintentar
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Container>
  );
}

export function ConnectionPage({
  onConnected,
  error: initialError = '',
  onCancel,
}: {
  onConnected: () => void;
  error?: string;
  onCancel?: () => void;
}) {
  const [url, setUrl] = useState(() => (canConfigureBackend() ? backendUrl() : ''));
  const [error, setError] = useState(initialError);
  if (!canConfigureBackend()) return <ServiceUnavailable />;
  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Card>
        <CardContent sx={{ p: 4 }}>
          <Stack
            component="form"
            spacing={3}
            onSubmit={(event) => {
              event.preventDefault();
              if (!canConfigureBackend()) return;
              try {
                if (!url.trim())
                  throw new Error('Ingresá la dirección del servidor para continuar.');
                const normalized = normalizeBackendUrl(url);
                saveBackendUrl(normalized);
                const params = new URLSearchParams(window.location.search);
                params.set('api', normalized);
                window.history.replaceState(
                  null,
                  '',
                  `${window.location.pathname}?${params}${window.location.hash}`,
                );
                onConnected();
              } catch (cause) {
                setError(
                  cause instanceof Error ? cause.message : 'No se pudo guardar la conexión.',
                );
              }
            }}
          >
            <Typography component="h1" variant="h5">
              Conectar E-MEDICAPP
            </Typography>
            <Typography color="text.secondary">
              La aplicación necesita conectarse al servidor de la demostración. Usá el enlace de
              acceso compartido por el presentador o ingresá su dirección HTTPS.
            </Typography>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField
              label="Dirección del servidor"
              type="url"
              required
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://servidor.example.com"
              helperText="La PC del presentador y el servidor deben permanecer encendidos."
            />
            <Button type="submit" variant="contained">
              Conectar
            </Button>
            {onCancel && <Button onClick={onCancel}>Volver</Button>}
          </Stack>
        </CardContent>
      </Card>
    </Container>
  );
}
