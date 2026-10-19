import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { Actor, api, label } from '../api';
import { StatusChip } from '../components/PageElements';
import type { UserOption } from './types';

const roles = ['BUYER', 'PHARMACY', 'PHARMACIST', 'DRIVER', 'PHARMACY_ADMIN', 'ADMIN'];

export function UsersPanel({
  users,
  actor,
  onSaved,
}: {
  users: UserOption[];
  actor: Actor;
  onSaved: () => Promise<void>;
}) {
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<UserOption | null>(null);
  const [active, setActive] = useState(true);
  const [selectedRoles, setSelectedRoles] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const visible = users.filter((x) =>
    `${x.name} ${x.email}`.toLowerCase().includes(search.trim().toLowerCase()),
  );
  async function save() {
    if (!selected) return;
    setBusy(true);
    setError('');
    try {
      await api(`/api/admin/users/${selected.id}/access`, {
        method: 'PUT',
        body: { active, roles: selectedRoles, expectedVersion: selected.version },
      });
      if (selected.id === actor.id) {
        window.location.reload();
        return;
      }
      setSelected(null);
      await onSaved();
    } catch (error) {
      setError(error instanceof Error ? error.message : 'No se pudo modificar el acceso.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Stack spacing={2}>
      <Alert severity="info">
        El correo confirmado habilita la cuenta del comprador. Los permisos de farmacia también
        necesitan una membresía de sucursal. Modificar un acceso cierra las sesiones anteriores de
        esa persona.
      </Alert>
      <TextField
        label="Buscar persona por nombre o correo"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />
      {!visible.length && (
        <Typography color="text.secondary">No hay personas para esta búsqueda.</Typography>
      )}
      {visible.map((user) => (
        <Card key={user.id}>
          <CardContent>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={2}
              sx={{ justifyContent: 'space-between' }}
            >
              <Box>
                <Typography sx={{ fontWeight: 650 }}>{user.name}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {user.email}
                </Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
                  <StatusChip
                    status={user.active ? 'ACTIVE' : 'SUSPENDED'}
                    text={user.active ? 'Cuenta activa' : 'Cuenta desactivada'}
                  />
                  <StatusChip
                    status={user.emailVerified ? 'VERIFIED' : 'PENDING'}
                    text={user.emailVerified ? 'Correo confirmado' : 'Correo pendiente'}
                  />
                </Stack>
                <Typography variant="caption" color="text.secondary">
                  {user.roles.map(label).join(' · ') || 'Sin roles'}
                </Typography>
              </Box>
              <Button
                onClick={() => {
                  setSelected(user);
                  setActive(user.active);
                  setSelectedRoles(user.roles);
                  setError('');
                }}
              >
                Modificar acceso
              </Button>
            </Stack>
          </CardContent>
        </Card>
      ))}
      <Dialog
        open={!!selected}
        onClose={busy ? undefined : () => setSelected(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Acceso de {selected?.name}</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            {error && <Alert severity="error">{error}</Alert>}
            <Typography variant="body2">
              Los cambios revocan las sesiones abiertas. La aplicación conserva el último
              administrador activo.
            </Typography>
            <FormControlLabel
              control={<Checkbox checked={active} onChange={(e) => setActive(e.target.checked)} />}
              label="Cuenta activa"
            />
            <Typography variant="subtitle2">Roles de la cuenta</Typography>
            {roles.map((role) => (
              <FormControlLabel
                key={role}
                control={
                  <Checkbox
                    checked={selectedRoles.includes(role)}
                    onChange={(e) =>
                      setSelectedRoles(
                        e.target.checked
                          ? [...selectedRoles, role]
                          : selectedRoles.filter((x) => x !== role),
                      )
                    }
                  />
                }
                label={label(role)}
              />
            ))}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setSelected(null)}>
            Volver
          </Button>
          <Button variant="contained" disabled={busy} onClick={() => void save()}>
            {busy ? 'Guardando…' : 'Guardar acceso'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
