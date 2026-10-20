import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Actor, api } from '../api';
import type { Branch } from '../catalog/types';
import { EmptyState, PageHeader } from '../components/PageElements';
import type { Promotion } from './types';
import { mendozaDateTime, promotionDate, promotionState } from './types';

type Draft = {
  original?: Promotion;
  title: string;
  description: string;
  startsAt: string;
  endsAt: string;
  featured: boolean;
  active: boolean;
  branchIds: string[];
  reason: string;
};

function draftFor(promotion?: Promotion): Draft {
  return {
    original: promotion,
    title: promotion?.title ?? '',
    description: promotion?.description ?? '',
    startsAt: mendozaDateTime(promotion?.startsAt ?? new Date()),
    endsAt: mendozaDateTime(promotion?.endsAt ?? new Date(Date.now() + 7 * 86400000)),
    featured: promotion?.featured ?? true,
    active: promotion?.active ?? true,
    branchIds: promotion?.branchIds ?? [],
    reason: '',
  };
}

export function PromotionManagementPage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const query = useQuery({
    queryKey: ['managed-promotions', actor.id],
    queryFn: () => api<Promotion[]>('/api/pharmacy/promotions'),
  });
  const branches = useQuery({
    queryKey: ['promotion-branches', actor.id],
    queryFn: () => api<Branch[]>('/api/pharmacy/promotion-branches'),
  });
  const edit = (promotion?: Promotion) => {
    setError('');
    setDraft(draftFor(promotion));
  };
  const selectedBranch = branches.data?.find((branch) => draft?.branchIds.includes(branch.id));
  const set = (patch: Partial<Draft>) =>
    setDraft((current) => (current ? { ...current, ...patch } : current));
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!draft || busy) return;
    if (!draft.branchIds.length) {
      setError('Seleccioná al menos una sucursal participante.');
      return;
    }
    // The form always uses Mendoza wall time, independent of the device time zone.
    const startsAt = new Date(`${draft.startsAt}:00-03:00`);
    const endsAt = new Date(`${draft.endsAt}:00-03:00`);
    if (
      !Number.isFinite(startsAt.getTime()) ||
      !Number.isFinite(endsAt.getTime()) ||
      startsAt >= endsAt
    ) {
      setError('La fecha de finalización debe ser posterior a la de inicio.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api(`/api/pharmacy/promotions${draft.original ? `/${draft.original.id}` : ''}`, {
        method: draft.original ? 'PUT' : 'POST',
        body: {
          title: draft.title.trim(),
          description: draft.description.trim(),
          startsAt: startsAt.toISOString(),
          endsAt: endsAt.toISOString(),
          featured: draft.featured,
          active: draft.active,
          branchIds: draft.branchIds,
          version: draft.original?.version ?? 0,
          reason: draft.reason.trim(),
        },
      });
      setDraft(null);
      setNotice(
        'Promoción guardada. Se mostrará a los clientes durante su vigencia si está publicada.',
      );
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['managed-promotions'] }),
        cache.invalidateQueries({ queryKey: ['promotions'] }),
      ]);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'No se pudo guardar la promoción.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Stack spacing={3}>
      <PageHeader
        eyebrow="Beneficios de tu farmacia"
        title="Gestionar promociones"
        description="Publicá ofertas y sus condiciones. Indicá las sucursales participantes y la vigencia; los clientes podrán ubicarlas en el mapa."
        action={
          <Button
            variant="contained"
            onClick={() => edit()}
            disabled={!branches.data?.some((branch) => branch.active)}
          >
            Nueva promoción
          </Button>
        }
      />
      {notice && (
        <Alert severity="success" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {(query.isPending || branches.isPending) && (
        <CircularProgress aria-label="Cargando gestión de promociones" />
      )}
      {(query.error || branches.error) && (
        <Alert severity="error">
          {query.error?.message ?? branches.error?.message}
          <Button
            onClick={() => {
              void query.refetch();
              void branches.refetch();
            }}
          >
            Volver a intentar
          </Button>
        </Alert>
      )}
      {branches.data?.length === 0 && (
        <Alert severity="info">
          Tu cuenta necesita un permiso de farmacia sobre una sucursal para publicar promociones.
        </Alert>
      )}
      {query.data?.length === 0 && (
        <EmptyState
          title="Creá tu primera promoción"
          description="Incluí el beneficio, las condiciones, las fechas y las sucursales donde estará disponible."
        />
      )}
      {query.data?.map((promotion) => (
        <Card key={promotion.id}>
          <CardContent>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={2}
              sx={{ justifyContent: 'space-between' }}
            >
              <Stack spacing={1} sx={{ minWidth: 0 }}>
                <Typography component="h2" variant="h6">
                  {promotion.title}
                </Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <Chip
                    size="small"
                    label={promotionState(promotion)}
                    color={promotionState(promotion) === 'Vigente' ? 'success' : 'default'}
                  />
                  {promotion.featured && (
                    <Chip size="small" label="Destacada" color="primary" variant="outlined" />
                  )}
                </Stack>
                <Typography>{promotion.chainName}</Typography>
                <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {promotion.description}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {promotionDate(promotion.startsAt)} — {promotionDate(promotion.endsAt)} · hora de
                  Mendoza
                </Typography>
                {promotion.branches.map((branch) => (
                  <Typography variant="body2" key={branch.id}>
                    {branch.name} · {branch.address}
                  </Typography>
                ))}
              </Stack>
              <Button sx={{ alignSelf: 'flex-start' }} onClick={() => edit(promotion)}>
                Modificar promoción
              </Button>
            </Stack>
          </CardContent>
        </Card>
      ))}
      <Dialog
        open={!!draft}
        onClose={() => {
          if (!busy) setDraft(null);
        }}
        fullWidth
        maxWidth="md"
      >
        {draft && (
          <Box component="form" onSubmit={(event) => void save(event)}>
            <DialogTitle>{draft.original ? 'Modificar promoción' : 'Nueva promoción'}</DialogTitle>
            <DialogContent>
              <Stack spacing={2.5} sx={{ pt: 1 }}>
                {error && <Alert severity="error">{error}</Alert>}
                <TextField
                  label="Título de la promoción"
                  value={draft.title}
                  onChange={(event) => set({ title: event.target.value })}
                  required
                  slotProps={{ htmlInput: { maxLength: 160 } }}
                />
                <TextField
                  label="Beneficio y condiciones"
                  value={draft.description}
                  onChange={(event) => set({ description: event.target.value })}
                  required
                  multiline
                  minRows={3}
                  slotProps={{ htmlInput: { maxLength: 4000 } }}
                  helperText="Describí productos incluidos, precio o descuento, medios de pago, restricciones y disponibilidad. El importe final se confirma en el presupuesto de la compra."
                />
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField
                    label="Inicio (hora de Mendoza)"
                    type="datetime-local"
                    value={draft.startsAt}
                    onChange={(event) => set({ startsAt: event.target.value })}
                    required
                    fullWidth
                    slotProps={{ inputLabel: { shrink: true } }}
                  />
                  <TextField
                    label="Finalización (hora de Mendoza)"
                    type="datetime-local"
                    value={draft.endsAt}
                    onChange={(event) => set({ endsAt: event.target.value })}
                    required
                    fullWidth
                    slotProps={{ inputLabel: { shrink: true } }}
                  />
                </Stack>
                <Box
                  component="fieldset"
                  sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, p: 2 }}
                >
                  <Typography component="legend" sx={{ px: 1 }}>
                    Sucursales participantes
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                    Elegí sucursales de una misma cadena. Una farmacia independiente publica para su
                    propia sucursal.
                  </Typography>
                  <Stack>
                    {branches.data?.map((branch) => {
                      const checked = draft.branchIds.includes(branch.id);
                      const differentChain =
                        !!selectedBranch &&
                        (selectedBranch.chainId
                          ? selectedBranch.chainId !== branch.chainId
                          : selectedBranch.id !== branch.id);
                      return (
                        <FormControlLabel
                          key={branch.id}
                          control={
                            <Checkbox
                              checked={checked}
                              disabled={!checked && (!branch.active || differentChain)}
                              onChange={(_, selected) =>
                                set({
                                  branchIds: selected
                                    ? [...draft.branchIds, branch.id]
                                    : draft.branchIds.filter((id) => id !== branch.id),
                                })
                              }
                            />
                          }
                          label={`${branch.name} · ${branch.address}${branch.active ? '' : ' (inactiva)'}`}
                        />
                      );
                    })}
                  </Stack>
                </Box>
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={draft.featured}
                      onChange={(_, featured) => set({ featured })}
                    />
                  }
                  label="Mostrar como destacada"
                />
                <FormControlLabel
                  control={
                    <Checkbox checked={draft.active} onChange={(_, active) => set({ active })} />
                  }
                  label="Publicada (visible durante su vigencia)"
                />
                <TextField
                  label="Motivo del cambio"
                  value={draft.reason}
                  onChange={(event) => set({ reason: event.target.value })}
                  required
                  slotProps={{ htmlInput: { maxLength: 500 } }}
                  helperText="Se conserva junto con el responsable del registro."
                />
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button disabled={busy} onClick={() => setDraft(null)}>
                Cancelar
              </Button>
              <Button disabled={busy || !!branches.error} type="submit" variant="contained">
                {busy ? 'Guardando…' : 'Guardar promoción'}
              </Button>
            </DialogActions>
          </Box>
        )}
      </Dialog>
    </Stack>
  );
}
