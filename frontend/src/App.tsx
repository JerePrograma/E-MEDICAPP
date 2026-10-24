import { lazy, Suspense, useEffect, useState } from 'react';
import { IonContent, IonHeader, IonToolbar } from '@ionic/react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Container,
  Toolbar,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AuthenticationPage, authenticationIntent } from './components/AuthenticationPage';
import { Workspace } from './components/Workspace';
import { Actor, ApiError, api, Bootstrap, label } from './api';
import {
  backendUrl,
  canConfigureBackend,
  initializeBackendConnection,
  needsBackendConnection,
} from './connection';
import { ConnectionPage, ServiceUnavailable } from './components/ConnectionPage';
import { CartProvider } from './cart/CartContext';
import { clearNativeDocuments } from './native/documents';
const MarketplacePage = lazy(() =>
  import('./catalog/MarketplacePage').then((m) => ({ default: m.MarketplacePage })),
);
const CartPage = lazy(() => import('./cart/CartPage').then((m) => ({ default: m.CartPage })));
const CartCheckoutPage = lazy(() =>
  import('./cart/CartCheckoutPage').then((m) => ({ default: m.CartCheckoutPage })),
);
const PurchasePage = lazy(() =>
  import('./purchases/PurchasePage').then((m) => ({ default: m.PurchasePage })),
);
const AdminPage = lazy(() => import('./admin/AdminPage').then((m) => ({ default: m.AdminPage })));
const StockPage = lazy(() => import('./catalog/StockPage').then((m) => ({ default: m.StockPage })));
const DeliveryPage = lazy(() =>
  import('./deliveries/DeliveryPage').then((m) => ({ default: m.DeliveryPage })),
);
const SalesReportPage = lazy(() =>
  import('./reports/SalesReportPage').then((m) => ({ default: m.SalesReportPage })),
);
const PromotionsPage = lazy(() =>
  import('./promotions/PromotionsPage').then((m) => ({ default: m.PromotionsPage })),
);
const PromotionManagementPage = lazy(() =>
  import('./promotions/PromotionManagementPage').then((m) => ({
    default: m.PromotionManagementPage,
  })),
);

export function App() {
  const [connectionError] = useState(initializeBackendConnection);
  const configurableConnection = canConfigureBackend();
  const [connectionRequested, setConnectionRequested] = useState(
    () => canConfigureBackend() && (needsBackendConnection() || !!connectionError),
  );
  if (!configurableConnection && (connectionError || needsBackendConnection()))
    return (
      <IonContent>
        <ServiceUnavailable />
      </IonContent>
    );
  if (configurableConnection && connectionRequested)
    return (
      <IonContent>
        <ConnectionPage
          error={connectionError}
          onConnected={() => window.location.reload()}
          onCancel={
            needsBackendConnection() || !!connectionError
              ? undefined
              : () => setConnectionRequested(false)
          }
        />
      </IonContent>
    );
  return (
    <ConnectedApp
      onConnectionRequested={
        configurableConnection ? () => setConnectionRequested(true) : undefined
      }
    />
  );
}

function ConnectedApp({ onConnectionRequested }: { onConnectionRequested?: () => void }) {
  const cache = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const bootstrap = useQuery({
    queryKey: ['bootstrap'],
    queryFn: () => api<Bootstrap>('/api/bootstrap'),
    staleTime: 60_000,
  });
  const session = useQuery({
    queryKey: ['session'],
    queryFn: async () => {
      try {
        return await api<Actor>('/api/auth/me');
      } catch (error) {
        // An anonymous session is cached as data, so focus refreshes never remount the login form.
        if (error instanceof ApiError && error.status === 401) return null;
        throw error;
      }
    },
    staleTime: 30_000,
  });
  const [logoutError, setLogoutError] = useState('');
  const [authenticationRequested, setAuthenticationRequested] = useState(
    () => !!authenticationIntent(),
  );
  useEffect(() => {
    if (authenticationIntent()) setAuthenticationRequested(true);
  }, [location]);
  const actor = session.data;
  if (bootstrap.isPending || session.isPending)
    return (
      <IonContent>
        <Box sx={{ p: 8, textAlign: 'center' }}>
          <CircularProgress />
          <Typography sx={{ mt: 2 }}>Abriendo E-MEDICAPP…</Typography>
        </Box>
      </IonContent>
    );
  if ((bootstrap.error && !bootstrap.data) || (session.error && session.data === undefined))
    return (
      <IonContent>
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
          {onConnectionRequested && (
            <Button sx={{ mt: 2 }} onClick={onConnectionRequested}>
              Cambiar servidor
            </Button>
          )}
        </Container>
      </IonContent>
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
      <IonHeader className="ion-no-border">
        <IonToolbar className="application-toolbar">
          <Toolbar
            sx={{
              gap: { xs: 1, sm: 2 },
              minHeight: { xs: 56, sm: 64 },
              px: { xs: 2, sm: 3 },
              py: { xs: 0, sm: 1 },
            }}
          >
            <Typography
              variant="h6"
              color="primary"
              noWrap
              sx={{ fontWeight: 800, flexGrow: 1, fontSize: { xs: '1rem', sm: '1.08rem' } }}
            >
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
                  title={actor.roles.map(label).join(' · ')}
                  sx={{
                    maxWidth: { xs: 132, sm: 220 },
                    height: { xs: 24, sm: 26 },
                    fontSize: { xs: '0.65rem', sm: '0.7rem' },
                    bgcolor: 'primary.light',
                    borderColor: 'transparent',
                  }}
                />
                <Button
                  sx={{ minWidth: 48, px: 1 }}
                  onClick={async () => {
                    try {
                      await api('/api/auth/logout', { method: 'POST' });
                      await clearNativeDocuments();
                      cache.setQueryData(['session'], null);
                      cache.removeQueries({
                        predicate: (query) =>
                          !['bootstrap', 'session'].includes(String(query.queryKey[0])),
                      });
                      navigate('/', { replace: true });
                    } catch (e) {
                      setLogoutError(
                        e instanceof Error ? e.message : 'No se pudo cerrar la sesión.',
                      );
                    }
                  }}
                >
                  Salir
                </Button>
              </>
            )}
            {!actor && onConnectionRequested && (
              <Button onClick={onConnectionRequested}>Conexión</Button>
            )}
          </Toolbar>
        </IonToolbar>
      </IonHeader>
      <IonContent id="application-content">
        {demo && (
          <Alert
            severity="info"
            sx={{
              borderRadius: 0,
              px: { xs: 2, sm: 2 },
              py: { xs: 0.25, sm: 0.75 },
              fontSize: { xs: '0.73rem', sm: '0.875rem' },
              '& .MuiAlert-icon': { fontSize: { xs: 18, sm: 22 }, mr: 1, py: 0.75 },
            }}
          >
            <Box component="span" sx={{ display: { xs: 'inline', sm: 'none' } }}>
              Demostración · Personas y datos de ejemplo.
            </Box>
            <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>
              Demostración comercial. Personas, comercios, importes y comprobaciones de ejemplo.
            </Box>
          </Alert>
        )}
        {logoutError && <Alert severity="error">{logoutError}</Alert>}
        {(bootstrap.error || session.error) && (
          <Alert severity="warning">
            Se interrumpió la conexión. Tus datos en pantalla se conservan; podés volver a intentar
            cuando el servidor esté disponible.
            <Button
              onClick={() => {
                void bootstrap.refetch();
                void session.refetch();
              }}
            >
              Reintentar
            </Button>
          </Alert>
        )}
        {!actor || authenticationRequested ? (
          <AuthenticationPage
            demo={demo}
            onLogin={() => {
              setAuthenticationRequested(false);
              navigate('/', { replace: true });
              cache.removeQueries({
                predicate: (q) => !['session', 'bootstrap'].includes(String(q.queryKey[0])),
              });
              void session.refetch();
              void bootstrap.refetch();
            }}
          />
        ) : (
          <CartProvider
            key={`${actor.id}:${backendUrl() || window.location.origin}`}
            actorId={actor.id}
          >
            <Workspace actor={actor}>
              <Suspense fallback={<CircularProgress aria-label="Abriendo sección" />}>
                <Routes>
                  <Route
                    path="/app/catalog"
                    element={
                      has('BUYER') ? (
                        <MarketplacePage actor={actor} />
                      ) : (
                        <Alert severity="warning">
                          El catálogo de compras está disponible para cuentas de comprador.
                        </Alert>
                      )
                    }
                  />
                  <Route
                    path="/app/cart"
                    element={
                      has('BUYER') ? (
                        <CartPage />
                      ) : (
                        <Alert severity="warning">
                          El carrito está disponible para cuentas de comprador.
                        </Alert>
                      )
                    }
                  />
                  <Route
                    path="/app/checkout"
                    element={
                      has('BUYER') ? (
                        <CartCheckoutPage actor={actor} />
                      ) : (
                        <Alert severity="warning">
                          La compra está disponible para cuentas de comprador.
                        </Alert>
                      )
                    }
                  />
                  <Route path="/app/promotions" element={<PromotionsPage />} />
                  <Route
                    path="/app/pharmacy-promotions"
                    element={
                      has('ADMIN', 'PHARMACY_ADMIN', 'PHARMACY', 'PHARMACIST') ? (
                        <PromotionManagementPage actor={actor} />
                      ) : (
                        <Alert severity="warning">
                          Esta cuenta no tiene acceso a la gestión de promociones.
                        </Alert>
                      )
                    }
                  />
                  <Route
                    path="/app/reports"
                    element={
                      has('ADMIN', 'PHARMACY_ADMIN', 'PHARMACY', 'PHARMACIST') ? (
                        <SalesReportPage actor={actor} />
                      ) : (
                        <Alert severity="warning">
                          Esta cuenta no tiene acceso a reportes de ventas.
                        </Alert>
                      )
                    }
                  />
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
                        <Alert severity="warning">
                          Esta cuenta no tiene acceso a administración.
                        </Alert>
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
          </CartProvider>
        )}
      </IonContent>
    </>
  );
}
