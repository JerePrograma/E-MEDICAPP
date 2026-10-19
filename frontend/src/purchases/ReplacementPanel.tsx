import { DocumentButton } from '../components/DocumentButton';
import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Actor, api, dateTime } from '../api';
import type { Branch } from '../catalog/types';
import { DocumentInput } from '../components/DocumentInput';
import { LocationPicker, Point } from '../components/LocationPicker';
import { StatusChip } from '../components/PageElements';
import type { Line, Order, Purchase } from './types';

type Replacement = {
  id: string;
  version: number;
  status: string;
  originalOrderId: string;
  replacementPurchaseId?: string;
  replacementOrderId?: string;
  requiresPrescription: boolean;
  requiresAuthorization: boolean;
  lines: Line[];
  mode: string;
  address?: string;
  deliveryMinutes: number;
  expiresAt: string;
  reason: string;
  originalDisposition: string;
};
type Selection =
  { kind: 'PROPOSE' } | { kind: 'ACCEPT' | 'REJECT' | 'REVIEW'; replacement: Replacement };

export function ReplacementPanel({
  actor,
  order,
  purchase,
  canReview,
  onOpenPurchase,
  onDone,
}: {
  actor: Actor;
  order: Order;
  purchase: Purchase;
  canReview: boolean;
  onOpenPurchase: (id: string) => void;
  onDone: () => void;
}) {
  const cache = useQueryClient();
  const [selection, setSelection] = useState<Selection | null>(null);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [disposition, setDisposition] = useState('NO_RETURN_DOCUMENTED');
  const [dispositionNote, setDispositionNote] = useState('');
  const [mode, setMode] = useState(purchase.mode);
  const [address, setAddress] = useState(purchase.destinationAddress ?? '');
  const [point, setPoint] = useState<Point | null>(null);
  const [minutes, setMinutes] = useState('');
  const [receiverName, setReceiverName] = useState('');
  const [receiverIdentity, setReceiverIdentity] = useState('');
  const [prescription, setPrescription] = useState('');
  const [authorization, setAuthorization] = useState('');
  const [reference, setReference] = useState('');
  const [issuer, setIssuer] = useState('');
  const [expires, setExpires] = useState('');
  const [originChecked, setOriginChecked] = useState(false);
  const [authorizationChecked, setAuthorizationChecked] = useState(false);
  const [authorizedQuantities, setAuthorizedQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const proposals = useQuery({
    queryKey: ['replacements', order.id],
    queryFn: () => api<Replacement[]>(`/api/orders/${order.id}/replacements`),
    refetchInterval: 10000,
  });
  const branches = useQuery({
    queryKey: ['branches'],
    queryFn: () => api<Branch[]>('/api/catalog/branches'),
    enabled: selection?.kind === 'PROPOSE',
  });
  const reviewing = selection?.kind === 'REVIEW' ? selection.replacement : null;
  const reviewPurchase = useQuery({
    queryKey: ['replacement-review', reviewing?.replacementPurchaseId],
    queryFn: () => api<Purchase>(`/api/purchases/${reviewing?.replacementPurchaseId}`),
    enabled: !!reviewing?.replacementPurchaseId,
  });
  const owner = actor.roles.includes('BUYER') && purchase.buyerUserId === actor.id;
  const selectedReplacement =
    selection && 'replacement' in selection ? selection.replacement : null;
  const authorizationRequired =
    !!selectedReplacement?.requiresAuthorization ||
    receiverName.trim() !== purchase.beneficiary?.name;
  function open(next: Selection) {
    setSelection(next);
    setReason('');
    setEvidence('');
    setError('');
    setQuantities({});
    setDisposition(order.fulfillment?.returnReceivedAt ? 'RETURNED' : 'NO_RETURN_DOCUMENTED');
    setDispositionNote('');
    setMode(purchase.mode);
    setAddress(purchase.destinationAddress ?? '');
    setPoint(null);
    setMinutes('');
    setReceiverName(order.receiverName ?? purchase.beneficiary?.name ?? actor.name);
    setReceiverIdentity(order.receiverIdentity ?? actor.dni ?? '');
    setPrescription('');
    setAuthorization('');
    setReference('');
    setIssuer('');
    setExpires('');
    setOriginChecked(false);
    setAuthorizationChecked(false);
    setAuthorizedQuantities({});
  }
  async function submit() {
    if (!selection) return;
    setError('');
    let path: string;
    let body: Record<string, unknown>;
    if (selection.kind === 'PROPOSE') {
      const lines = order.lines
        .filter((x) => x.lineId && (quantities[x.lineId] ?? 0) > 0)
        .map((x) => ({ lineId: x.lineId, quantity: quantities[x.lineId!] }));
      const branch = branches.data?.find((x) => x.id === order.branchId);
      const destination =
        mode === 'PICKUP' && branch
          ? { lat: Number(branch.latitude), lon: Number(branch.longitude) }
          : point;
      if (
        !lines.length ||
        !reason.trim() ||
        !evidence ||
        !dispositionNote.trim() ||
        !destination ||
        (mode === 'DELIVERY' && address.trim().length < 5) ||
        !/^\d+$/.test(minutes) ||
        Number(minutes) < 1
      ) {
        setError(
          'Seleccioná las unidades afectadas y completá motivo, destino, plazo, disposición física y evidencia.',
        );
        return;
      }
      path = `/api/orders/${order.id}/replacements`;
      body = {
        expectedVersion: order.version,
        lines,
        reason: reason.trim(),
        evidenceDocumentId: evidence,
        originalDisposition: disposition,
        dispositionNote: dispositionNote.trim(),
        mode,
        deliveryAddress: mode === 'DELIVERY' ? address.trim() : '',
        latitude: destination.lat,
        longitude: destination.lon,
        deliveryMinutes: Number(minutes),
      };
    } else {
      const replacement = selection.replacement;
      path = `/api/replacements/${replacement.id}/${selection.kind.toLowerCase()}`;
      body = { expectedVersion: replacement.version };
      if (selection.kind === 'ACCEPT') {
        if (
          !receiverName.trim() ||
          !/^\d{7,12}$/.test(receiverIdentity.replace(/[.\s]/g, '')) ||
          (replacement.requiresPrescription && !prescription) ||
          (authorizationRequired && !authorization)
        ) {
          setError('Indicá al receptor y adjuntá nueva receta y autorización cuando correspondan.');
          return;
        }
        body = {
          ...body,
          receiverName: receiverName.trim(),
          receiverIdentity: receiverIdentity.replace(/[.\s]/g, ''),
          prescriptionDocumentId: prescription || null,
          authorizationDocumentId: authorization || null,
        };
      } else if (selection.kind === 'REJECT') {
        if (!reason.trim()) {
          setError('Indicá el motivo del rechazo.');
          return;
        }
        body = { ...body, reason: reason.trim() };
      } else {
        const expiry = expires ? new Date(`${expires}:00-03:00`) : null;
        if (
          !reason.trim() ||
          (replacement.requiresPrescription &&
            (!reference.trim() ||
              !issuer.trim() ||
              !expiry ||
              !Number.isFinite(expiry.getTime()) ||
              expiry.getTime() <= Date.now() ||
              !originChecked ||
              replacement.lines.some(
                (line) =>
                  line.requiresPrescription &&
                  (!/^\d+$/.test(authorizedQuantities[line.lineId!] ?? '') ||
                    Number(authorizedQuantities[line.lineId!]) < line.quantity),
              ))) ||
          (replacement.requiresAuthorization && !authorizationChecked) ||
          !evidence
        ) {
          setError(
            'Completá la revisión de los nuevos documentos y adjuntá evidencia de las condiciones comprobadas para la reposición.',
          );
          return;
        }
        body = {
          ...body,
          reason: reason.trim(),
          originVerified: originChecked,
          authorizationChecked,
          prescriptionReference: reference || null,
          issuer: issuer || null,
          expiresAt: expiry?.toISOString() ?? null,
          conditionEvidenceDocumentId: evidence || null,
          lines: replacement.lines
            .filter((line) => line.requiresPrescription)
            .map((line) => ({
              lineId: line.lineId,
              authorizedQuantity: Number(authorizedQuantities[line.lineId!]),
            })),
        };
      }
    }
    setBusy(true);
    try {
      await api(path, { method: 'POST', idempotent: true, body });
      setSelection(null);
      await cache.invalidateQueries({ queryKey: ['replacements', order.id] });
      await cache.invalidateQueries({ queryKey: ['purchases'] });
      onDone();
    } catch (error) {
      setError(error instanceof Error ? error.message : 'No se pudo registrar la reposición.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Stack spacing={2}>
      {canReview &&
        ['INCIDENT', 'RETURNED'].includes(order.fulfillment?.status ?? '') &&
        !order.cancellationPending && (
          <Button sx={{ alignSelf: 'flex-start' }} onClick={() => open({ kind: 'PROPOSE' })}>
            Proponer reposición sin cargo
          </Button>
        )}
      {proposals.error && <Alert severity="warning">{proposals.error.message}</Alert>}
      {proposals.data?.map((replacement) => (
        <Box
          key={replacement.id}
          sx={{
            p: 2,
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 2,
            bgcolor: 'background.default',
          }}
        >
          <Stack spacing={1.5}>
            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ flexWrap: 'wrap', alignItems: 'center' }}
            >
              <Typography sx={{ fontWeight: 650 }}>Reposición sin cargo</Typography>
              <StatusChip
                status={replacement.status}
                text={
                  {
                    PROPOSED: 'Propuesta para aceptar',
                    ACCEPTED: 'Documentación por revisar',
                    READY: 'Documentación revisada',
                    REJECTED: 'Rechazada',
                    EXPIRED: 'Propuesta vencida',
                    FULFILLED: 'Reposición cumplida',
                    CANCELLED: 'Cancelada',
                  }[replacement.status]
                }
              />
            </Stack>
            <Typography variant="body2">
              {replacement.lines
                .map((x) => `${x.name} · ${x.presentation} × ${x.quantity}`)
                .join(' / ')}
            </Typography>
            <Typography variant="body2">
              {replacement.mode === 'PICKUP'
                ? 'Retiro en farmacia'
                : `Entrega: ${replacement.address}`}{' '}
              · Plazo informado: {replacement.deliveryMinutes} minutos.
            </Typography>
            <Typography variant="body2">Motivo: {replacement.reason}</Typography>
            {replacement.status === 'PROPOSED' && (
              <Typography variant="caption" color="text.secondary">
                Propuesta vigente hasta {dateTime(replacement.expiresAt)}.
              </Typography>
            )}
            {replacement.status === 'ACCEPTED' && (
              <Alert severity="info">
                La farmacia debe revisar la documentación de la reposición antes de preparar las
                nuevas unidades.
              </Alert>
            )}
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {owner && replacement.status === 'PROPOSED' && (
                <>
                  <Button variant="contained" onClick={() => open({ kind: 'ACCEPT', replacement })}>
                    Revisar y aceptar reposición
                  </Button>
                  <Button onClick={() => open({ kind: 'REJECT', replacement })}>
                    Rechazar reposición
                  </Button>
                </>
              )}
              {canReview && replacement.status === 'ACCEPTED' && (
                <Button variant="contained" onClick={() => open({ kind: 'REVIEW', replacement })}>
                  Revisar documentación de reposición
                </Button>
              )}
              {replacement.replacementPurchaseId && (
                <Button onClick={() => onOpenPurchase(replacement.replacementPurchaseId!)}>
                  Abrir compra de reposición
                </Button>
              )}
            </Stack>
          </Stack>
        </Box>
      ))}
      <Dialog
        open={!!selection}
        onClose={busy ? undefined : () => setSelection(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>
          {selection?.kind === 'PROPOSE'
            ? 'Proponer reposición sin cargo'
            : selection?.kind === 'ACCEPT'
              ? 'Aceptar reposición sin cargo'
              : selection?.kind === 'REJECT'
                ? 'Rechazar reposición'
                : 'Revisar documentación de reposición'}
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5}>
            {error && <Alert severity="error">{error}</Alert>}
            {selection?.kind === 'PROPOSE' && (
              <>
                <Alert severity="info">
                  La reposición usa unidades y lotes nuevos. Conserva el pedido, la factura y la
                  custodia originales. No reincorpora el producto retornado al stock ni genera un
                  cobro adicional.
                </Alert>
                {order.lines
                  .filter((x) => x.lineId)
                  .map((x) => (
                    <Box key={x.lineId}>
                      <Typography sx={{ fontWeight: 650 }}>
                        {x.name} · {x.presentation}
                      </Typography>
                      <TextField
                        label={`Unidades a reponer de ${x.name}`}
                        type="number"
                        value={quantities[x.lineId!] ?? 0}
                        onChange={(e) =>
                          setQuantities({
                            ...quantities,
                            [x.lineId!]: Math.max(
                              0,
                              Math.min(x.quantity, Math.trunc(Number(e.target.value) || 0)),
                            ),
                          })
                        }
                        slotProps={{ htmlInput: { min: 0, max: x.quantity, step: 1 } }}
                        helperText={`Hasta ${x.quantity} unidades del pedido original.`}
                      />
                    </Box>
                  ))}
                <TextField
                  required
                  multiline
                  label="Motivo de la reposición"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                <TextField
                  select
                  label="Disposición del producto original"
                  value={disposition}
                  onChange={(e) => setDisposition(e.target.value)}
                >
                  <MenuItem value="RETURNED" disabled={!order.fulfillment?.returnReceivedAt}>
                    Retorno recibido físicamente en farmacia
                  </MenuItem>
                  <MenuItem value="NO_RETURN_DOCUMENTED">
                    Resolución documentada sin retorno
                  </MenuItem>
                </TextField>
                <TextField
                  required
                  multiline
                  label="Evaluación física y fundamento de la disposición"
                  value={dispositionNote}
                  onChange={(e) => setDispositionNote(e.target.value)}
                />
                <TextField
                  select
                  label="Modalidad de la reposición"
                  value={mode}
                  onChange={(e) => {
                    setMode(e.target.value);
                    setPoint(null);
                  }}
                >
                  <MenuItem value="DELIVERY">Entrega a domicilio sin costo</MenuItem>
                  <MenuItem value="PICKUP">Retiro en farmacia</MenuItem>
                </TextField>
                {mode === 'DELIVERY' && (
                  <>
                    <TextField
                      required
                      label="Domicilio de entrega de la reposición"
                      value={address}
                      onChange={(e) => setAddress(e.target.value)}
                    />
                    <LocationPicker
                      delivery
                      value={point}
                      onChange={setPoint}
                      branches={branches.data}
                    />
                  </>
                )}
                <TextField
                  required
                  type="number"
                  label="Plazo de reposición (minutos)"
                  value={minutes}
                  onChange={(e) => setMinutes(e.target.value)}
                  slotProps={{ htmlInput: { min: 1, max: 10080, step: 1 } }}
                />
                <DocumentInput
                  kind="OTRO"
                  label="Evidencia de la evaluación y propuesta de reposición"
                  branchId={order.branchId}
                  value={evidence}
                  onChange={setEvidence}
                />
              </>
            )}
            {selection?.kind === 'ACCEPT' && (
              <>
                <Alert severity="info">
                  Aceptás las presentaciones, cantidades, modalidad y plazo de esta reposición sin
                  cargo. La farmacia revisará los documentos nuevos antes de preparar el pedido
                  vinculado.
                </Alert>
                <Typography>
                  {selection.replacement.lines
                    .map((x) => `${x.name} · ${x.presentation} × ${x.quantity}`)
                    .join(' / ')}
                </Typography>
                <Typography>
                  {selection.replacement.mode === 'PICKUP'
                    ? 'Retiro en farmacia'
                    : selection.replacement.address}{' '}
                  · {selection.replacement.deliveryMinutes} minutos.
                </Typography>
                <TextField
                  required
                  label="Nombre de quien recibirá la reposición"
                  value={receiverName}
                  onChange={(e) => setReceiverName(e.target.value)}
                />
                <TextField
                  required
                  label="DNI de quien recibirá la reposición"
                  value={receiverIdentity}
                  onChange={(e) => setReceiverIdentity(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                />
                {selection.replacement.requiresPrescription && (
                  <DocumentInput
                    kind="RECETA"
                    label="Nueva receta para la reposición"
                    value={prescription}
                    onChange={setPrescription}
                  />
                )}
                {authorizationRequired && (
                  <DocumentInput
                    kind="AUTORIZACION"
                    label="Nueva nota firmada para la reposición"
                    value={authorization}
                    onChange={setAuthorization}
                  />
                )}
              </>
            )}
            {selection?.kind === 'REJECT' && (
              <TextField
                required
                multiline
                label="Motivo para rechazar la reposición"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            )}
            {selection?.kind === 'REVIEW' && (
              <>
                <Alert severity="info">
                  Comprobá los nuevos documentos y la aptitud de la farmacia para cumplir la
                  reposición. El producto retornado permanece fuera del stock disponible.
                </Alert>
                {reviewPurchase.error && (
                  <Alert severity="error">{reviewPurchase.error.message}</Alert>
                )}
                {reviewPurchase.data?.orders.map((newOrder) => (
                  <Stack key={newOrder.id} spacing={1}>
                    {(newOrder.prescriptionDocumentId ??
                      reviewPurchase.data?.prescriptionDocumentId) && (
                      <DocumentButton
                        href={`/api/documents/${newOrder.prescriptionDocumentId ?? reviewPurchase.data?.prescriptionDocumentId}`}
                        target="_blank"
                        rel="noopener"
                      >
                        Consultar nueva receta de reposición
                      </DocumentButton>
                    )}
                    {(newOrder.authorizationDocumentId ??
                      reviewPurchase.data?.authorizationDocumentId) && (
                      <DocumentButton
                        href={`/api/documents/${newOrder.authorizationDocumentId ?? reviewPurchase.data?.authorizationDocumentId}`}
                        target="_blank"
                        rel="noopener"
                      >
                        Consultar nueva nota de reposición
                      </DocumentButton>
                    )}
                  </Stack>
                ))}
                <TextField
                  required
                  multiline
                  label="Comprobaciones de la reposición"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                {selection.replacement.requiresPrescription && (
                  <>
                    <TextField
                      required
                      label="Referencia de nueva receta"
                      value={reference}
                      onChange={(e) => setReference(e.target.value)}
                    />
                    <TextField
                      required
                      label="Emisor de nueva receta"
                      value={issuer}
                      onChange={(e) => setIssuer(e.target.value)}
                    />
                    <TextField
                      required
                      type="datetime-local"
                      label="Vigencia de nueva receta"
                      value={expires}
                      onChange={(e) => setExpires(e.target.value)}
                      slotProps={{ inputLabel: { shrink: true } }}
                      helperText="Horario de Mendoza."
                    />
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={originChecked}
                          onChange={(e) => setOriginChecked(e.target.checked)}
                        />
                      }
                      label="Comprobé origen y vigencia de la nueva receta"
                    />
                    {selection.replacement.lines
                      .filter((line) => line.requiresPrescription)
                      .map((line) => (
                        <TextField
                          key={line.lineId}
                          required
                          type="number"
                          label={`Cantidad autorizada en nueva receta: ${line.name}`}
                          value={authorizedQuantities[line.lineId!] ?? ''}
                          onChange={(event) =>
                            setAuthorizedQuantities({
                              ...authorizedQuantities,
                              [line.lineId!]: event.target.value,
                            })
                          }
                          slotProps={{ htmlInput: { min: line.quantity, max: 100, step: 1 } }}
                          helperText={`Leé la cantidad autorizada en la nueva receta. La reposición solicita ${line.quantity} unidades.`}
                        />
                      ))}
                  </>
                )}
                {selection.replacement.requiresAuthorization && (
                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={authorizationChecked}
                        onChange={(e) => setAuthorizationChecked(e.target.checked)}
                      />
                    }
                    label="Comprobé identidad, firma y alcance de la nueva nota"
                  />
                )}
                <DocumentInput
                  kind="OTRO"
                  label="Evidencia de condiciones para la reposición"
                  branchId={order.branchId}
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
            {busy
              ? 'Guardando…'
              : selection?.kind === 'PROPOSE'
                ? 'Presentar reposición al comprador'
                : selection?.kind === 'ACCEPT'
                  ? 'Aceptar condiciones de reposición'
                  : selection?.kind === 'REJECT'
                    ? 'Confirmar rechazo de reposición'
                    : 'Confirmar revisión de reposición'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
