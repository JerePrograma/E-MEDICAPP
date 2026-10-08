import { lazy, Suspense, useState } from 'react';
import {
  Alert,
  AppBar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Container,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Toolbar,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { NavLink, Route, Routes } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Actor, ApiError, api, Bootstrap, label } from './api';
const PurchasePage = lazy(() =>
  import('./purchases/PurchasePage').then((m) => ({ default: m.PurchasePage })),
);
const AdminPage = lazy(() => import('./admin/AdminPage').then((m) => ({ default: m.AdminPage })));
const StockPage = lazy(() => import('./catalog/StockPage').then((m) => ({ default: m.StockPage })));
const DeliveryPage = lazy(() =>
  import('./deliveries/DeliveryPage').then((m) => ({ default: m.DeliveryPage })),
);

const loginSchema = z.object({
  email: z.email('Ingresá un correo válido.'),
  password: z.string().min(1, 'Ingresá tu contraseña.'),
});
type LoginInput = z.infer<typeof loginSchema>;

function Login({ demo, onLogin }: { demo: boolean; onLogin: () => void }) {
  const [registering, setRegistering] = useState(false);
  const [credentials, setCredentials] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [name, setName] = useState('');
  const [birthDate, setBirthDate] = useState('');
  const form = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: demo ? 'comprador@demo.invalid' : '',
      password: demo ? 'DemoMedicapp2026!' : '',
    },
  });
  async function submit(values: LoginInput) {
    setBusy(true);
    setNotice('');
    try {
      if (registering)
        await api('/api/auth/register', { method: 'POST', body: { ...values, name, birthDate } });
      await api('/api/auth/login', { method: 'POST', body: values, form: true });
      onLogin();
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'No se pudo ingresar.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 4, md: 8 } }}>
      <Typography variant="overline" color="primary">
        Farmacias conectadas
      </Typography>
      <Typography variant="h4" sx={{ mb: 2 }}>
        Tu farmacia, más cerca.
      </Typography>
      <Typography color="text.secondary" sx={{ mb: 4 }}>
        Comprá, recibí una propuesta de la farmacia y seguí tu pedido hasta la entrega o el retiro.
      </Typography>
      <Card>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Typography variant="h5" sx={{ mb: 3 }}>
            {registering ? 'Crear mi cuenta' : 'Ingresar'}
          </Typography>
          <Box component="form" onSubmit={form.handleSubmit(submit)}>
            <Stack spacing={2.5}>
              {notice && <Alert severity="error">{notice}</Alert>}
              {demo && !registering && !credentials && (
                <TextField
                  select
                  label="Perfil de demostración"
                  value={form.watch('email')}
                  onChange={(e) => form.setValue('email', e.target.value)}
                >
                  {[
                    'comprador',
                    'farmacia',
                    'farmaceutico',
                    'farmaceutico2',
                    'cadete',
                    'admin',
                  ].map((x) => (
                    <MenuItem key={x} value={`${x}@demo.invalid`}>
                      {
                        (
                          {
                            comprador: 'Comprador · Lucía Fernández',
                            farmacia: 'Farmacia · Paula Benítez',
                            farmaceutico: 'Farmacéutico · Valentina Ríos',
                            farmaceutico2: 'Segundo farmacéutico · Gabriel Ponce',
                            cadete: 'Cadete · Santiago Molina',
                            admin: 'Administrador · Marina Suárez',
                          } as Record<string, string>
                        )[x]
                      }
                    </MenuItem>
                  ))}
                </TextField>
              )}
              {(!demo || registering || credentials) && (
                <>
                  <TextField
                    label="Correo electrónico"
                    autoComplete="username"
                    {...form.register('email')}
                    error={!!form.formState.errors.email}
                    helperText={form.formState.errors.email?.message}
                  />
                  <TextField
                    label="Contraseña"
                    type="password"
                    autoComplete={registering ? 'new-password' : 'current-password'}
                    {...form.register('password')}
                    error={!!form.formState.errors.password}
                    helperText={form.formState.errors.password?.message}
                  />
                </>
              )}
              {registering && (
                <>
                  <TextField
                    label="Nombre completo"
                    required
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                  />
                  <TextField
                    label="Fecha de nacimiento"
                    type="date"
                    required
                    value={birthDate}
                    onChange={(e) => setBirthDate(e.target.value)}
                    slotProps={{ inputLabel: { shrink: true } }}
                    helperText="La cuenta y las compras están disponibles para personas adultas."
                  />
                </>
              )}
              <Button variant="contained" type="submit" size="large" disabled={busy}>
                {busy ? 'Ingresando…' : registering ? 'Crear cuenta e ingresar' : 'Ingresar'}
              </Button>
              {demo && !registering && (
                <Button
                  onClick={() => {
                    setCredentials(!credentials);
                    if (credentials)
                      form.reset({
                        email: 'comprador@demo.invalid',
                        password: 'DemoMedicapp2026!',
                      });
                    setNotice('');
                  }}
                >
                  {credentials ? 'Elegir una persona del escenario' : 'Ingresar con otra cuenta'}
                </Button>
              )}
              <Button
                onClick={() => {
                  setRegistering(!registering);
                  setNotice('');
                }}
              >
                {registering ? 'Ya tengo una cuenta' : 'Crear una cuenta de comprador'}
              </Button>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Container>
  );
}

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
  const actor = session.data;
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
    ) : has('ADMIN') ? (
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
        sx={{ borderBottom: '1px solid #dfe8e7' }}
      >
        <Toolbar sx={{ gap: 2, flexWrap: 'wrap', py: 1 }}>
          <Typography variant="h6" color="primary" sx={{ fontWeight: 800, flexGrow: 1 }}>
            E-MEDICAPP
          </Typography>
          {actor && (
            <>
              <Typography variant="body2">{actor.name}</Typography>
              <Chip size="small" label={actor.roles.map(label).join(' · ')} />
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
      {!actor ? (
        <Login
          demo={demo}
          onLogin={() => {
            cache.removeQueries({
              predicate: (q) => !['session', 'bootstrap'].includes(String(q.queryKey[0])),
            });
            void session.refetch();
            void bootstrap.refetch();
          }}
        />
      ) : (
        <Container maxWidth="lg" sx={{ py: 3 }}>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mb: 3 }}>
            {has('BUYER', 'PHARMACY', 'PHARMACIST') && (
              <Button component={NavLink} to="/app" end>
                {has('PHARMACY', 'PHARMACIST') ? 'Solicitudes y pedidos' : 'Mis compras'}
              </Button>
            )}
            {has('PHARMACY', 'PHARMACIST') && (
              <Button component={NavLink} to="/app/stock">
                Stock y precios
              </Button>
            )}
            {has('DRIVER', 'PHARMACY', 'PHARMACIST') && (
              <Button component={NavLink} to="/app/deliveries">
                Entregas
              </Button>
            )}
            {has('ADMIN') && (
              <Button component={NavLink} to="/app/admin">
                Administración
              </Button>
            )}
          </Stack>
          <Divider sx={{ mb: 3 }} />
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
                  has('ADMIN') ? (
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
        </Container>
      )}
    </>
  );
}
