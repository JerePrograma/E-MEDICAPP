import { lazy, Suspense, useState } from 'react';
import {
  Alert,
  AppBar,
  Box,
  Button,
  Chip,
  CircularProgress,
  Container,
  Toolbar,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Route, Routes } from 'react-router-dom';
import { AuthenticationPage, authenticationIntent } from './components/AuthenticationPage';
import { Workspace } from './components/Workspace';
import { Actor, ApiError, api, Bootstrap, label } from './api';
const PurchasePage = lazy(() =>
  import('./purchases/PurchasePage').then((m) => ({ default: m.PurchasePage })),
);
const AdminPage = lazy(() => import('./admin/AdminPage').then((m) => ({ default: m.AdminPage })));
const StockPage = lazy(() => import('./catalog/StockPage').then((m) => ({ default: m.StockPage })));
const DeliveryPage = lazy(() =>
  import('./deliveries/DeliveryPage').then((m) => ({ default: m.DeliveryPage })),
);

export function App() {
  const cache = useQueryClient();
  const bootstrap = useQuery({
    queryKey: ['bootstrap'],
    queryFn: () => api<Bootstrap>('/api/bootstrap'),
    staleTime: 60_000,
  });
  const session = useQuery({
    queryKey: ['session'],
    queryFn: () => api<Actor>('/api/auth/me'),
    staleTime: 30_000,
  });
  const [logoutError, setLogoutError] = useState('');
  const [authenticationRequested, setAuthenticationRequested] = useState(
    () => !!authenticationIntent(),
  );
  const actor =
    session.error instanceof ApiError && session.error.status === 401 ? undefined : session.data;
  if (bootstrap.isPending || session.isPending)
    return (
      <Box sx={{ p: 8, textAlign: 'center' }}>
        <CircularProgress />
        <Typography sx={{ mt: 2 }}>Abriendo E-MEDICAPP…</Typography>
      </Box>
    );
  if (
    bootstrap.error ||
    (session.error && !(session.error instanceof ApiError && session.error.status === 401))
  )
    return (
      <Container maxWidth="sm" sx={{ py: 8 }}>
        <Alert severity="error">No se pudo conectar con E-MEDICAPP.</Alert>
        <Button
          sx={{ mt: 2 }}
          onClick={() => {
            void bootstrap.refetch();
            void session.refetch();
          }}
        >
          Volver a intentar
        </Button>
      </Container>
    );
  const demo = bootstrap.data?.mode === 'DEMO';
  const has = (...roles: string[]) => !!actor?.roles.some((x) => roles.includes(x));
  const home = actor ? (
    has('BUYER', 'PHARMACY', 'PHARMACIST') ? (
      <PurchasePage actor={actor} />
    ) : has('ADMIN', 'PHARMACY_ADMIN') ? (
      <AdminPage actor={actor} />
    ) : (
      <DeliveryPage actor={actor} />
    )
  ) : null;
  return (
    <>
      <AppBar
        position="sticky"
        color="inherit"
        elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider' }}
      >
        <Toolbar sx={{ gap: 2, flexWrap: 'wrap', py: 1 }}>
          <Typography variant="h6" color="primary" sx={{ fontWeight: 800, flexGrow: 1 }}>
            E-MEDICAPP
          </Typography>
          {actor && (
            <>
              <Typography variant="body2" sx={{ display: { xs: 'none', sm: 'block' } }}>
                {actor.name}
              </Typography>
              <Chip
                size="small"
                variant="outlined"
                label={actor.roles.map(label).join(' · ')}
                sx={{ maxWidth: 220 }}
              />
              <Button
                onClick={async () => {
                  try {
                    await api('/api/auth/logout', { method: 'POST' });
                    cache.clear();
                    window.location.assign('/');
                  } catch (e) {
                    setLogoutError(e instanceof Error ? e.message : 'No se pudo cerrar la sesión.');
                  }
                }}
              >
                Salir
              </Button>
            </>
          )}
        </Toolbar>
      </AppBar>
      {demo && (
        <Alert severity="info" sx={{ borderRadius: 0 }}>
          Demostración comercial. Personas, comercios, importes y comprobaciones de ejemplo.
        </Alert>
      )}
      {logoutError && <Alert severity="error">{logoutError}</Alert>}
      {!actor || authenticationRequested ? (
        <AuthenticationPage
          demo={demo}
          onLogin={() => {
            setAuthenticationRequested(false);
            cache.removeQueries({
              predicate: (q) => !['session', 'bootstrap'].includes(String(q.queryKey[0])),
            });
            void session.refetch();
            void bootstrap.refetch();
          }}
        />
      ) : (
        <Workspace actor={actor}>
          <Suspense fallback={<CircularProgress aria-label="Abriendo sección" />}>
            <Routes>
              <Route path="/" element={home} />
              <Route path="/app" element={home} />
              <Route
                path="/app/stock"
                element={
                  has('PHARMACY', 'PHARMACIST') ? (
                    <StockPage actor={actor} />
                  ) : (
                    <Alert severity="warning">Esta cuenta no tiene acceso a stock.</Alert>
                  )
                }
              />
              <Route
                path="/app/deliveries"
                element={
                  has('DRIVER', 'PHARMACY', 'PHARMACIST') ? (
                    <DeliveryPage actor={actor} />
                  ) : (
                    <Alert severity="warning">Esta cuenta no tiene entregas asignadas.</Alert>
                  )
                }
              />
              <Route
                path="/app/admin"
                element={
                  has('ADMIN', 'PHARMACY_ADMIN') ? (
                    <AdminPage actor={actor} />
                  ) : (
                    <Alert severity="warning">Esta cuenta no tiene acceso a administración.</Alert>
                  )
                }
              />
              <Route
                path="*"
                element={<Alert severity="info">La página no está disponible.</Alert>}
              />
            </Routes>
          </Suspense>
        </Workspace>
      )}
    </>
  );
}
