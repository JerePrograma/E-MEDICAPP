import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
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
import { DocumentInput } from '../components/DocumentInput';

type Delivery = {
  id: string;
  orderId: string;
  orderVersion: number;
  branchId: string;
  version: number;
  status: string;
  address: string;
  branchName: string;
  mode: string;
  coldChain: boolean;
  special: boolean;
};
type DeliveryAction = 'accept' | 'pickup' | 'complete' | 'incident';
const names: Record<string, string> = {
  PENDING: 'Pendiente',
  PREPARING: 'En preparación',
  READY: 'Listo',
  ASSIGNED: 'Asignado',
  ACCEPTED: 'Aceptado por cadete',
  PICKED_UP: 'En camino',
  DELIVERED: 'Entregado',
  COLLECTED: 'Retirado en farmacia',
  INCIDENT: 'Con incidencia',
  CANCELLED: 'Cancelado',
  RESOLVED: 'Cerrado con resolución',
};
const titles: Record<DeliveryAction, string> = {
  accept: 'Aceptar tarea',
  pickup: 'Recibir paquete y comenzar entrega',
  complete: 'Confirmar recepción del pedido',
  incident: 'Informar incidencia a la farmacia',
};
export function DeliveryPage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const [selection, setSelection] = useState<{ delivery: Delivery; action: DeliveryAction } | null>(
    null,
  );
  const [code, setCode] = useState(''),
    [note, setNote] = useState(''),
    [temperature, setTemperature] = useState(''),
    [evidence, setEvidence] = useState('');
  const [verified, setVerified] = useState(false),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState('');
  const deliveries = useQuery({
    queryKey: ['deliveries'],
    queryFn: () => api<Delivery[]>('/api/deliveries'),
    refetchInterval: 10000,
  });
  const driver = actor.roles.includes('DRIVER');
  async function submit() {
    if (!selection) return;
    setBusy(true);
    setError('');
    const { delivery, action } = selection;
    const conditions = {
      conditionEvidenceDocumentId: evidence || null,
      temperatureC: temperature || null,
    };
    const body =
      action === 'incident'
        ? {
            expectedVersion: delivery.orderVersion,
            reason: note,
            evidenceDocumentId: evidence || null,
          }
        : {
            expectedVersion: delivery.version,
            ...(action === 'pickup' ? { custodyNote: note, ...conditions } : {}),
            ...(action === 'complete'
              ? { code, recipientVerified: verified, note, ...conditions }
              : {}),
          };
    const path =
      action === 'incident'
        ? `/api/orders/${delivery.orderId}/incident`
        : `/api/deliveries/${delivery.id}/${action}`;
    try {
      await api(path, { method: 'POST', idempotent: action === 'complete', body });
      setSelection(null);
      await cache.invalidateQueries({ queryKey: ['deliveries'] });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo registrar.');
    } finally {
      setBusy(false);
    }
  }
  function open(delivery: Delivery, action: DeliveryAction) {
    setSelection({ delivery, action });
    setCode('');
    setNote('');
    setTemperature('');
    setEvidence('');
    setVerified(false);
    setError('');
  }
  function shareLocation(delivery: Delivery) {
    if (!navigator.geolocation) {
      setNotice('Este dispositivo no informa ubicación.');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => {
        void api(`/api/deliveries/${delivery.id}/location`, {
          method: 'POST',
          body: { lat: p.coords.latitude, lon: p.coords.longitude, accuracy: p.coords.accuracy },
        })
          .then(() => setNotice('Ubicación de esta tarea actualizada.'))
          .catch((e) => setNotice(e instanceof Error ? e.message : 'No se pudo actualizar.'));
      },
      () => setNotice('No se pudo consultar tu ubicación.'),
      { timeout: 10000, maximumAge: 30000 },
    );
  }
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Entregas</Typography>
      <Typography color="text.secondary">
        {driver
          ? 'Tus tareas asignadas y constancias de recepción.'
          : 'Seguimiento de entregas de tus sucursales.'}
      </Typography>
      {deliveries.error && <Alert severity="error">{deliveries.error.message}</Alert>}
      {notice && (
        <Alert severity="info" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {deliveries.data?.length === 0 && <Alert severity="info">No hay entregas asignadas.</Alert>}
      {deliveries.data?.map((delivery) => (
        <Card key={delivery.id}>
          <CardContent>
            <Stack spacing={2}>
              <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between' }}>
                <Typography variant="h6">{delivery.branchName}</Typography>
                <Chip label={names[delivery.status] ?? delivery.status} />
              </Stack>
              <Typography>{delivery.address}</Typography>
              <Typography variant="caption" color="text.secondary">
                Pedido {delivery.orderId.slice(0, 8).toUpperCase()}
              </Typography>
              {delivery.special && (
                <Alert severity="info">
                  El paquete requiere controles de conservación y custodia documentados.
                </Alert>
              )}
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {driver && delivery.status === 'ASSIGNED' && (
                  <Button variant="contained" onClick={() => open(delivery, 'accept')}>
                    Aceptar tarea
                  </Button>
                )}
                {driver && delivery.status === 'ACCEPTED' && (
                  <Button variant="contained" onClick={() => open(delivery, 'pickup')}>
                    Recibí el paquete de la farmacia
                  </Button>
                )}
                {driver && delivery.status === 'PICKED_UP' && (
                  <>
                    <Button variant="contained" onClick={() => open(delivery, 'complete')}>
                      Confirmar entrega
                    </Button>
                    <Button onClick={() => shareLocation(delivery)}>
                      Actualizar mi ubicación para esta entrega
                    </Button>
                  </>
                )}
                {driver && ['ASSIGNED', 'ACCEPTED', 'PICKED_UP'].includes(delivery.status) && (
                  <Button color="warning" onClick={() => open(delivery, 'incident')}>
                    Informar incidencia
                  </Button>
                )}
              </Stack>
            </Stack>
          </CardContent>
        </Card>
      ))}
      <Dialog
        open={!!selection}
        onClose={busy ? undefined : () => setSelection(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{selection ? titles[selection.action] : ''}</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            {error && <Alert severity="error">{error}</Alert>}
            <Typography>{selection?.delivery.address}</Typography>
            {selection?.action === 'accept' ? (
              <Typography>
                Confirmás que podés realizar esta entrega asignada por tu farmacia.
              </Typography>
            ) : (
              <TextField
                label={
                  selection?.action === 'pickup'
                    ? 'Condiciones del paquete recibido'
                    : selection?.action === 'incident'
                      ? 'Qué ocurrió y qué necesita resolver la farmacia'
                      : 'Constancia de entrega'
                }
                required
                multiline
                minRows={2}
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            )}
            {selection?.action === 'complete' && (
              <>
                <TextField
                  label="Código informado por el receptor"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                />
                <FormControlLabel
                  control={
                    <Checkbox checked={verified} onChange={(e) => setVerified(e.target.checked)} />
                  }
                  label="Verifiqué que recibe el titular o la persona autorizada"
                />
              </>
            )}
            {selection &&
              selection.action !== 'accept' &&
              (selection.delivery.special || selection.action === 'incident') && (
                <>
                  {selection.delivery.coldChain && selection.action !== 'incident' && (
                    <TextField
                      required
                      label="Temperatura observada del paquete (°C)"
                      value={temperature}
                      onChange={(e) => setTemperature(e.target.value)}
                    />
                  )}
                  <DocumentInput
                    kind="ENTREGA"
                    branchId={selection.delivery.branchId}
                    label={
                      selection.action === 'incident'
                        ? 'Evidencia de la incidencia (opcional)'
                        : 'Evidencia de conservación y custodia comprobada'
                    }
                    value={evidence}
                    onChange={setEvidence}
                  />
                </>
              )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setSelection(null)}>
            Volver
          </Button>
          <Button variant="contained" disabled={busy} onClick={() => void submit()}>
            Confirmar
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
