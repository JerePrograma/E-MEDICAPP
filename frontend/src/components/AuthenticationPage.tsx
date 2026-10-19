import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { api, ApiError } from '../api';
import { useLocation } from 'react-router-dom';

type Mode = 'LOGIN' | 'REGISTER' | 'VERIFY' | 'EMAIL_PENDING' | 'RESET_REQUEST' | 'RESET';
type Response = { status: string; message: string };
const demoProfiles = {
  comprador: 'Comprador · Lucía Fernández',
  farmacia: 'Farmacia · Paula Benítez',
  farmaceutico: 'Farmacéutico · Valentina Ríos',
  farmaceutico2: 'Segundo farmacéutico · Gabriel Ponce',
  cadete: 'Cadete · Santiago Molina',
  adminfarmacia: 'Administrador de farmacia · Sofía Méndez',
  admin: 'Administrador · Marina Suárez',
};
const demoEmails = new Set(Object.keys(demoProfiles).map((key) => `${key}@demo.invalid`));

export function authenticationIntent() {
  const fragment = window.location.hash.slice(1);
  const hash = new URLSearchParams(fragment.includes('?') ? fragment.split('?')[1] : fragment);
  if (hash.has('verify-email'))
    return { mode: 'VERIFY' as const, token: hash.get('verify-email') ?? '' };
  if (hash.has('reset-password'))
    return { mode: 'RESET' as const, token: hash.get('reset-password') ?? '' };
  return null;
}

export function AuthenticationPage({ demo, onLogin }: { demo: boolean; onLogin: () => void }) {
  const location = useLocation();
  const [intent, setIntent] = useState(authenticationIntent);
  const [mode, setMode] = useState<Mode>(intent?.mode ?? 'LOGIN');
  const [email, setEmail] = useState(() => {
    try {
      return (
        sessionStorage.getItem('emedicapp.login-email') ??
        (demo && !intent ? 'comprador@demo.invalid' : '')
      );
    } catch {
      return demo && !intent ? 'comprador@demo.invalid' : '';
    }
  });
  const [password, setPassword] = useState(
    demo && !intent && demoEmails.has(email) ? 'DemoMedicapp2026!' : '',
  );
  const [confirmation, setConfirmation] = useState('');
  const [credentials, setCredentials] = useState(!demo || !!intent || !demoEmails.has(email));
  const [name, setName] = useState('');
  const [birthDate, setBirthDate] = useState('');
  const [dni, setDni] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [noticeKind, setNoticeKind] = useState<'error' | 'info' | 'success'>('info');
  useEffect(() => {
    const next = authenticationIntent();
    if (next && (next.mode !== intent?.mode || next.token !== intent.token)) {
      setIntent(next);
      setMode(next.mode);
      setCredentials(true);
      setPassword('');
      setConfirmation('');
      setNotice('');
    }
  }, [location, intent]);
  useEffect(() => {
    try {
      if (email) sessionStorage.setItem('emedicapp.login-email', email);
      else sessionStorage.removeItem('emedicapp.login-email');
    } catch {
      // Editing remains available when browser storage is restricted.
    }
  }, [email]);
  function changeMode(next: Mode) {
    setMode(next);
    setNotice('');
    setPassword('');
    setConfirmation('');
    setCredentials(true);
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setNotice('');
    if ((mode === 'REGISTER' || mode === 'RESET') && password !== confirmation) {
      setNoticeKind('error');
      setNotice('Las contraseñas deben coincidir.');
      return;
    }
    if (
      (mode === 'REGISTER' || mode === 'RESET') &&
      new TextEncoder().encode(password).length > 72
    ) {
      setNoticeKind('error');
      setNotice('La contraseña supera el límite de 72 bytes. Usá una más corta.');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'LOGIN') {
        await api('/api/auth/login', { method: 'POST', body: { email, password }, form: true });
        onLogin();
        return;
      }
      let response: Response;
      if (mode === 'REGISTER') {
        response = await api<Response>('/api/auth/register', {
          method: 'POST',
          body: {
            email,
            password,
            name: name.trim(),
            birthDate,
            dni: dni.trim(),
            phone: phone.trim(),
            address: address.trim(),
          },
        });
        setMode('EMAIL_PENDING');
      } else if (mode === 'VERIFY') {
        response = await api<Response>('/api/auth/verification/confirm', {
          method: 'POST',
          body: { token: intent?.token },
        });
        setMode('LOGIN');
        setPassword('');
        setCredentials(true);
        window.history.replaceState(
          null,
          '',
          window.location.pathname + window.location.search + '#/',
        );
      } else if (mode === 'RESET') {
        response = await api<Response>('/api/auth/password/reset', {
          method: 'POST',
          body: { token: intent?.token, password },
        });
        setMode('LOGIN');
        setPassword('');
        setCredentials(true);
        window.history.replaceState(
          null,
          '',
          window.location.pathname + window.location.search + '#/',
        );
      } else {
        response = await api<Response>(
          mode === 'EMAIL_PENDING'
            ? '/api/auth/verification/resend'
            : '/api/auth/password/request-reset',
          { method: 'POST', body: { email } },
        );
      }
      setNotice(
        response.status === 'EMAIL_CONFIGURATION_PENDING' &&
          (mode === 'REGISTER' || mode === 'EMAIL_PENDING')
          ? 'Todavía no podemos enviarte el correo de confirmación. Tu cuenta quedará pendiente hasta que el servicio esté disponible. Podés solicitar otro envío más adelante.'
          : response.status === 'EMAIL_CONFIGURATION_PENDING' && mode === 'RESET_REQUEST'
            ? 'Por el momento no podemos enviarte el correo para recuperar el acceso. Podés intentar de nuevo más adelante.'
            : response.message,
      );
      setNoticeKind(response.status.includes('CONFIGURATION_PENDING') ? 'info' : 'success');
    } catch (error) {
      setNoticeKind('error');
      setNotice(error instanceof Error ? error.message : 'No se pudo completar la operación.');
      if (error instanceof ApiError && error.code === 'EMAIL_NOT_VERIFIED')
        setMode('EMAIL_PENDING');
    } finally {
      setBusy(false);
    }
  }
  const titles: Record<Mode, string> = {
    LOGIN: 'Ingresar',
    REGISTER: 'Crear mi cuenta',
    VERIFY: 'Confirmar mi correo',
    EMAIL_PENDING: 'Confirmá tu correo',
    RESET_REQUEST: 'Recuperar el acceso',
    RESET: 'Elegir una nueva contraseña',
  };
  const descriptions: Record<Mode, string> = {
    LOGIN: 'Accedé a tus compras y a las tareas de tu farmacia.',
    REGISTER: 'Una cuenta personal para comprar para vos o para un familiar adulto autorizado.',
    VERIFY:
      'Confirmá este correo para habilitar tu cuenta. Después podrás ingresar con tu contraseña.',
    EMAIL_PENDING:
      'La cuenta necesita confirmar el correo para operar. Abrí el enlace de verificación cuando lo recibas; podés solicitar otro desde acá.',
    RESET_REQUEST:
      'Si el correo corresponde a una cuenta, recibirás las instrucciones para cambiar tu contraseña.',
    RESET: 'El enlace permite cambiar la contraseña. Después ingresá con la nueva clave.',
  };
  const showEmail =
    mode === 'REGISTER' ||
    mode === 'RESET_REQUEST' ||
    mode === 'EMAIL_PENDING' ||
    (mode === 'LOGIN' && credentials);
  const showPassword = mode === 'REGISTER' || mode === 'RESET' || (mode === 'LOGIN' && credentials);
  return (
    <Box
      sx={{
        maxWidth: 1100,
        mx: 'auto',
        p: { xs: 2, md: 5 },
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
        gap: { xs: 3, md: 7 },
        alignItems: 'start',
      }}
    >
      <Stack spacing={3} sx={{ py: { xs: 1, md: 5 } }}>
        <Typography variant="overline" color="primary">
          Farmacias conectadas · Mendoza
        </Typography>
        <Typography
          component="h1"
          variant="h3"
          sx={{
            fontSize: { xs: '2.1rem', md: '3rem' },
            fontWeight: 720,
            letterSpacing: '-.04em',
            maxWidth: 420,
          }}
        >
          Tu farmacia,
          <br />
          más cerca.
        </Typography>
        <Typography color="text.secondary" sx={{ maxWidth: 400, fontSize: '1.05rem' }}>
          Elegí tus medicamentos, recibí una propuesta y seguí la compra hasta la entrega o el
          retiro.
        </Typography>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {[
            'La farmacia confirma el precio y la disponibilidad.',
            'Aceptás las condiciones antes de pagar.',
            'Pagás directamente a cada farmacia.',
          ].map((text, i) => (
            <Stack key={text} direction="row" spacing={2} sx={{ alignItems: 'center' }}>
              <Box
                sx={{
                  width: 30,
                  height: 30,
                  borderRadius: '50%',
                  bgcolor: 'primary.light',
                  color: 'primary.main',
                  display: 'grid',
                  placeItems: 'center',
                  flexShrink: 0,
                  fontWeight: 700,
                }}
              >
                {i + 1}
              </Box>
              <Typography variant="body2">{text}</Typography>
            </Stack>
          ))}
        </Stack>
      </Stack>
      <Card>
        <CardContent sx={{ p: { xs: 3, md: 4 } }}>
          <Stack component="form" spacing={2.5} onSubmit={(event) => void submit(event)}>
            <Box>
              <Typography component="h2" variant="h5">
                {titles[mode]}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {descriptions[mode]}
              </Typography>
            </Box>
            {notice && <Alert severity={noticeKind}>{notice}</Alert>}
            {demo && mode === 'LOGIN' && !credentials && (
              <TextField
                select
                label="Perfil de demostración"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              >
                {Object.entries(demoProfiles).map(([key, title]) => (
                  <MenuItem key={key} value={`${key}@demo.invalid`}>
                    {title}
                  </MenuItem>
                ))}
              </TextField>
            )}
            {mode === 'REGISTER' && (
              <>
                <TextField
                  label="Nombre completo"
                  slotProps={{ htmlInput: { maxLength: 120 } }}
                  autoComplete="name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                    gap: 2,
                  }}
                >
                  <TextField
                    label="Fecha de nacimiento"
                    type="date"
                    required
                    value={birthDate}
                    onChange={(e) => setBirthDate(e.target.value)}
                    slotProps={{ inputLabel: { shrink: true } }}
                    helperText="Para personas de 18 años o más."
                  />
                  <TextField
                    label="DNI"
                    autoComplete="off"
                    required
                    value={dni}
                    onChange={(e) => setDni(e.target.value)}
                    slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                  />
                </Box>
                <TextField
                  label="Teléfono de contacto"
                  autoComplete="tel"
                  required
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                />
                <TextField
                  label="Domicilio"
                  slotProps={{ htmlInput: { minLength: 5, maxLength: 300 } }}
                  autoComplete="street-address"
                  required
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                />
              </>
            )}
            {showEmail && (
              <TextField
                label="Correo electrónico"
                slotProps={{ htmlInput: { maxLength: 254 } }}
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            )}
            {showPassword && (
              <TextField
                label="Contraseña"
                type="password"
                autoComplete={mode === 'LOGIN' ? 'current-password' : 'new-password'}
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                helperText={
                  mode === 'REGISTER' || mode === 'RESET'
                    ? 'Usá una contraseña propia de al menos 12 caracteres.'
                    : undefined
                }
                slotProps={{ htmlInput: { minLength: mode === 'LOGIN' ? 1 : 12, maxLength: 72 } }}
              />
            )}
            {(mode === 'REGISTER' || mode === 'RESET') && (
              <TextField
                label="Repetir contraseña"
                type="password"
                autoComplete="new-password"
                required
                value={confirmation}
                onChange={(e) => setConfirmation(e.target.value)}
              />
            )}
            <Button
              variant="contained"
              type="submit"
              size="large"
              disabled={busy || ((mode === 'VERIFY' || mode === 'RESET') && !intent?.token)}
            >
              {busy
                ? 'Procesando…'
                : mode === 'LOGIN'
                  ? 'Ingresar'
                  : mode === 'REGISTER'
                    ? 'Crear cuenta y verificar correo'
                    : mode === 'VERIFY'
                      ? 'Confirmar mi correo'
                      : mode === 'RESET'
                        ? 'Guardar nueva contraseña'
                        : mode === 'EMAIL_PENDING'
                          ? 'Reenviar verificación'
                          : 'Solicitar enlace de recuperación'}
            </Button>
            {mode === 'LOGIN' ? (
              <>
                <Button onClick={() => changeMode('RESET_REQUEST')}>Olvidé mi contraseña</Button>
                <Divider />
                <Button onClick={() => changeMode('REGISTER')}>
                  Crear una cuenta de comprador
                </Button>
                {demo && (
                  <Button
                    onClick={() => {
                      setCredentials(!credentials);
                      setNotice('');
                      if (credentials) {
                        setEmail('comprador@demo.invalid');
                        setPassword('DemoMedicapp2026!');
                      } else {
                        setEmail('');
                        setPassword('');
                      }
                    }}
                  >
                    {credentials ? 'Elegir una persona del escenario' : 'Ingresar con otra cuenta'}
                  </Button>
                )}
              </>
            ) : (
              <Button onClick={() => changeMode('LOGIN')}>Volver a ingresar</Button>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
