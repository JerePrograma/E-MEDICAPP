import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Checkbox,
  Box,
  CircularProgress,
  MenuItem,
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
import { Actor, api, dateTime } from '../api';
import { EmptyState, MetricRow, PageHeader, StatusChip } from '../components/PageElements';
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
  taskExpiresAt?: string;
  pickupExpiresAt?: string;
  retryCount?: number;
  retryScheduledAt?: string;
  custodyHolder?: string;
  returnReceivedAt?: string;
  coordinationNote?: string;
  receiverName?: string;
  receiverAuthorizationRequired?: boolean;
  receiverAuthorizationValid?: boolean;
  authorizationDocumentId?: string;
};
type DeliveryAction = 'accept' | 'reject' | 'pickup' | 'complete' | 'incident';
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
  RETURN_REQUIRED: 'Retorno a la farmacia pendiente',
  RETURNED: 'Retorno recibido por la farmacia',
};
const titles: Record<DeliveryAction, string> = {
  accept: 'Aceptar tarea',
  reject: 'Rechazar tarea asignada',
  pickup: 'Recibir paquete y comenzar entrega',
  complete: 'Confirmar recepción del pedido',
  incident: 'Informar incidencia a la farmacia',
};
export function DeliveryPage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const [filter, setFilter] = useState('ACTIVE');
  const [recipientName, setRecipientName] = useState('');
  const [recipientIdentity, setRecipientIdentity] = useState('');
  const [incidentKind, setIncidentKind] = useState('OTHER');
  const [selection, setSelection] = useState<{ delivery: Delivery; action: DeliveryAction } | null>(
    null,
  );
  const [code, setCode] = useState(''),
    [note, setNote] = useState(''),
    [temperature, setTemperature] = useState(''),
    [evidence, setEvidence] = useState('');
  const [packageMatches, setPackageMatches] = useState(false);
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
  const finished = (status: string) =>
    ['DELIVERED', 'COLLECTED', 'CANCELLED', 'RESOLVED'].includes(status);
  const visible =
    deliveries.data?.filter(
      (x) => filter === 'ALL' || (filter === 'ACTIVE' ? !finished(x.status) : finished(x.status)),
    ) ?? [];
  async function submit() {
    if (!selection) return;
    if (
      selection.action === 'complete' &&
      (!/^\d{6}$/.test(code.trim()) ||
        !verified ||
        !packageMatches ||
        !recipientName.trim() ||
        !/^\d{7,12}$/.test(recipientIdentity.replace(/[.\s]/g, '')) ||
        !note.trim())
    ) {
      setError(
        'Ingresá el código vigente, nombre y DNI comprobados, y la constancia de recepción.',
      );
      return;
    }
    if (!['accept'].includes(selection.action) && !note.trim()) {
      setError('Registrá las condiciones comprobadas o el motivo de la acción.');
      return;
    }
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
            kind: incidentKind,
            evidenceDocumentId: evidence || null,
          }
        : {
            expectedVersion: delivery.version,
            ...(action === 'reject' ? { reason: note.trim() } : {}),
            ...(action === 'pickup' ? { custodyNote: note, ...conditions } : {}),
            ...(action === 'complete'
              ? {
                  code: code.trim(),
                  recipientVerified: verified,
                  packageMatches,
                  recipientName: recipientName.trim(),
                  recipientIdentity: recipientIdentity.replace(/[.\s]/g, ''),
                  authorizationDocumentId: delivery.receiverAuthorizationRequired
                    ? delivery.authorizationDocumentId
                    : null,
                  note,
                  ...conditions,
                }
              : {}),
          };
    const path =
      action === 'incident'
        ? `/api/orders/${delivery.orderId}/incident`
        : `/api/deliveries/${delivery.id}/${action}`;
    try {
      const result = await api<{ receiptRejected?: boolean; message?: string }>(path, {
        method: 'POST',
        idempotent: action === 'complete',
        body,
      });
      if (result.receiptRejected) {
        setError(result.message ?? 'El código no coincide. Pedile al receptor un código vigente.');
        return;
      }
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
    setRecipientName(delivery.receiverName ?? '');
    setRecipientIdentity('');
    setIncidentKind('OTHER');
    setNote('');
    setTemperature('');
    setEvidence('');
    setVerified(false);
    setPackageMatches(false);
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
      <PageHeader
        eyebrow={driver ? 'Tu jornada de entrega' : 'Logística de tu farmacia'}
        title="Entregas"
        description={
          driver
            ? 'Aceptá tu tarea, registrá la custodia y comprobá a quién entregás. La farmacia resuelve las incidencias.'
            : 'Seguí el paquete, su custodia y las tareas de tus cadetes propios.'
        }
      />
      {deliveries.data && (
        <MetricRow
          metrics={[
            {
              label: 'Por aceptar',
              value: deliveries.data.filter((x) => x.status === 'ASSIGNED').length,
            },
            {
              label: 'En camino',
              value: deliveries.data.filter((x) => x.status === 'PICKED_UP').length,
            },
            {
              label: 'Requieren resolución',
              value: deliveries.data.filter((x) =>
                ['INCIDENT', 'RETURN_REQUIRED', 'RETURNED'].includes(x.status),
              ).length,
            },
            {
              label: 'Recepciones completadas',
              value: deliveries.data.filter((x) => ['DELIVERED', 'COLLECTED'].includes(x.status))
                .length,
            },
          ]}
        />
      )}
      <TextField
        select
        label="Mostrar entregas"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        sx={{ maxWidth: 340 }}
      >
        <MenuItem value="ACTIVE">En curso</MenuItem>
        <MenuItem value="FINISHED">Finalizadas</MenuItem>
        <MenuItem value="ALL">Todas</MenuItem>
      </TextField>
      {deliveries.error && <Alert severity="error">{deliveries.error.message}</Alert>}
      {notice && (
        <Alert severity="info" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {deliveries.isPending && <CircularProgress aria-label="Consultando entregas" />}
      {deliveries.data && !visible.length && (
        <EmptyState
          title="Sin tareas en esta bandeja"
          description="Las nuevas tareas asignadas por tu farmacia aparecerán acá."
        />
      )}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, gap: 2 }}>
        {visible.map((delivery) => (
          <Card key={delivery.id}>
            <CardContent>
              <Stack spacing={2}>
                <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between' }}>
                  <Typography variant="h6">{delivery.branchName}</Typography>
                  <StatusChip status={delivery.status} text={names[delivery.status]} />
                </Stack>
                <Typography>
                  {delivery.address ||
                    (delivery.mode === 'PICKUP'
                      ? 'Retiro presencial en sucursal'
                      : 'Domicilio pendiente')}
                </Typography>
                {delivery.receiverName && (
                  <Typography variant="body2">Recibe: {delivery.receiverName}</Typography>
                )}
                {delivery.taskExpiresAt && delivery.status === 'ASSIGNED' && (
                  <Alert severity="warning">
                    Aceptá o rechazá antes del {dateTime(delivery.taskExpiresAt)}.
                  </Alert>
                )}
                {delivery.pickupExpiresAt &&
                  delivery.mode === 'PICKUP' &&
                  !finished(delivery.status) && (
                    <Typography variant="body2" color="text.secondary">
                      Plazo de retiro: {dateTime(delivery.pickupExpiresAt)}
                    </Typography>
                  )}
                {delivery.retryScheduledAt && (
                  <Alert severity="info">
                    Único reintento coordinado: {dateTime(delivery.retryScheduledAt)}.{' '}
                    {delivery.coordinationNote}
                  </Alert>
                )}
                {delivery.status === 'RETURN_REQUIRED' && (
                  <Alert severity="warning">
                    Regresá el paquete a la farmacia. La farmacia registrará su recepción física y
                    acordará la resolución con el comprador.
                  </Alert>
                )}
                {delivery.status === 'RETURNED' && (
                  <Alert severity="info">
                    El paquete volvió a la farmacia. Esperá una nueva tarea o la resolución
                    documentada.
                  </Alert>
                )}
                {delivery.receiverAuthorizationRequired && !delivery.receiverAuthorizationValid && (
                  <Alert severity="warning">
                    La autorización de recepción necesita resolución de la farmacia. No confirmes la
                    entrega.
                  </Alert>
                )}
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
                  {driver && delivery.status === 'ASSIGNED' && (
                    <Button color="warning" onClick={() => open(delivery, 'reject')}>
                      Rechazar tarea
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
      </Box>
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
                    : selection?.action === 'reject'
                      ? 'Motivo por el que no podés realizar la tarea'
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
            {selection?.action === 'incident' && (
              <TextField
                select
                label="Tipo de incidencia"
                value={incidentKind}
                onChange={(e) => setIncidentKind(e.target.value)}
              >
                {Object.entries({
                  RECIPIENT_ABSENT: 'No hay un receptor autorizado en el domicilio',
                  RECIPIENT_REFUSED: 'El receptor rechaza el paquete',
                  PRODUCT_DISCREPANCY: 'Diferencia en medicamentos o cantidades',
                  TEMPERATURE: 'Condiciones de conservación',
                  OTHER: 'Otra situación',
                }).map(([value, title]) => (
                  <MenuItem
                    key={value}
                    value={value}
                    disabled={
                      ['RECIPIENT_ABSENT', 'RECIPIENT_REFUSED'].includes(value) &&
                      selection.delivery.status !== 'PICKED_UP'
                    }
                  >
                    {title}
                  </MenuItem>
                ))}
              </TextField>
            )}
            {selection?.action === 'complete' && (
              <>
                <TextField
                  label="Nombre de la persona que recibe"
                  required
                  value={recipientName}
                  onChange={(e) => setRecipientName(e.target.value)}
                />
                <TextField
                  label="DNI comprobado de quien recibe"
                  required
                  value={recipientIdentity}
                  onChange={(e) => setRecipientIdentity(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                  helperText="Transcribí el documento observado presencialmente."
                />
                <TextField
                  label="Código informado por el receptor"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                />
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={packageMatches}
                      onChange={(e) => setPackageMatches(e.target.checked)}
                    />
                  }
                  label="Medicamentos, cantidades y presentación coinciden con el pedido"
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
              !['accept', 'reject'].includes(selection.action) &&
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
