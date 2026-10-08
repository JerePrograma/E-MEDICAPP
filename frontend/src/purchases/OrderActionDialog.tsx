import { useState } from 'react';
import {
  Alert,
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
import { useQuery } from '@tanstack/react-query';
import { Actor, api, dateTime, money } from '../api';
import type { Stock } from '../catalog/types';
import { DocumentInput } from '../components/DocumentInput';
import { Order, Purchase } from './types';

export type Action =
  | 'review'
  | 'payment'
  | 'prepare'
  | 'dispense'
  | 'assign-driver'
  | 'pickup-complete'
  | 'refund'
  | 'incident'
  | 'invoice'
  | 'cancel'
  | 'reconcile-payment'
  | 'resolve-incident';
const titles: Record<Action, string> = {
  review: 'Revisar solicitud',
  payment: 'Verificar pago recibido',
  prepare: 'Iniciar preparación',
  dispense: 'Registrar dispensación',
  'assign-driver': 'Asignar cadete propio',
  'pickup-complete': 'Confirmar retiro en farmacia',
  refund: 'Confirmar reintegro realizado',
  incident: 'Registrar incidencia',
  invoice: 'Adjuntar factura de la farmacia',
  cancel: 'Cancelar el pedido de esta farmacia',
  'reconcile-payment': 'Conciliar el pago informado de un pedido cerrado',
  'resolve-incident': 'Resolver incidencia con la farmacia',
};

function decimal(value: string): string | null {
  const normalized = value.trim().replace(',', '.');
  return /^\d{1,16}(?:\.\d{1,2})?$/.test(normalized) ? normalized : null;
}

function cents(value: string): bigint {
  const [whole = '0', fraction = ''] = value.split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
}

function amountFromCents(value: bigint): string {
  return `${value / 100n}.${String(value % 100n).padStart(2, '0')}`;
}

function mendozaDateTime(value?: string): string {
  if (!value) return '';
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: 'America/Argentina/Mendoza',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(new Date(value))
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function OrderActionDialog({
  action,
  order,
  purchase,
  actor,
  onClose,
  onDone,
}: {
  action: Action;
  order: Order;
  purchase: Purchase;
  actor: Actor;
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState(
    action === 'payment' || action === 'reconcile-payment'
      ? (order.paymentSubmission?.reference ?? '')
      : action === 'review'
        ? (order.prescriptionReview?.reference ?? '')
        : '',
  );
  const [evidence, setEvidence] = useState(
    action === 'payment' ? (order.paymentSubmission?.evidenceDocumentId ?? '') : '',
  );
  const [checked, setChecked] = useState(false);
  const [stockChecked, setStockChecked] = useState(false);
  const [decision, setDecision] = useState('APPROVE');
  const [resolution, setResolution] = useState('REFUND');
  const [reconciliation, setReconciliation] = useState('');
  const [origin, setOrigin] = useState(false);
  const [issuer, setIssuer] = useState(
    action === 'review' ? (order.prescriptionReview?.issuer ?? '') : '',
  );
  const [expires, setExpires] = useState(
    action === 'review' ? mendozaDateTime(order.prescriptionReview?.expiresAt) : '',
  );
  const [code, setCode] = useState('');
  const [driver, setDriver] = useState('');
  const [temperature, setTemperature] = useState('');
  const [coverage, setCoverage] = useState<Record<string, string>>(
    Object.fromEntries(order.lines.map((line) => [line.productId, line.coverageAmount ?? '0.00'])),
  );
  const [coverageRef, setCoverageRef] = useState<Record<string, string>>(
    Object.fromEntries(order.lines.map((line) => [line.productId, line.coverageReference ?? ''])),
  );
  const [coverageDocs, setCoverageDocs] = useState<Record<string, string>>(
    Object.fromEntries(
      order.lines.map((line) => [line.productId, line.coverageEvidenceDocumentId ?? '']),
    ),
  );
  const [authorized, setAuthorized] = useState<Record<string, string>>(
    Object.fromEntries(
      order.lines.map((line) => [line.productId, String(line.authorizedQuantity ?? line.quantity)]),
    ),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const drivers = useQuery({
    queryKey: ['drivers', order.branchId],
    queryFn: () =>
      api<{ id: string; name: string; available: boolean }[]>(
        `/api/branches/${order.branchId}/drivers`,
      ),
    enabled: action === 'assign-driver',
  });
  const stock = useQuery({
    queryKey: ['stock', order.branchId],
    queryFn: () => api<Stock[]>(`/api/branches/${order.branchId}/stock`),
    enabled: action === 'review',
  });
  const prescriptionDocument = order.prescriptionDocumentId ?? purchase.prescriptionDocumentId;
  const authorizationDocument = order.authorizationDocumentId ?? purchase.authorizationDocumentId;
  const needsPrescription = order.lines.some((line) => line.requiresPrescription);
  const special = order.lines.some((line) => line.coldChain || line.restricted);
  const cold = order.lines.some((line) => line.coldChain);
  const prescriptionExpiry = expires ? new Date(`${expires}:00-03:00`) : null;
  const positiveCoverage = (id: string) => {
    const value = decimal(coverage[id] || '0');
    return value !== null && cents(value) > 0n;
  };
  function validation(): string | null {
    if (action === 'review') {
      if (!reason.trim()) return 'Detallá las comprobaciones realizadas o el motivo del rechazo.';
      if (decision === 'REJECT') return null;
      if (!checked) return 'Confirmá la revisión de identidad, mayoría de edad y autorización.';
      if (!stockChecked)
        return 'Confirmá las existencias físicas, la presentación y el precio vigente.';
      if (!stock.data) return 'Esperá la consulta de stock y precios antes de aprobar.';
      if (
        needsPrescription &&
        (!prescriptionDocument ||
          !reference.trim() ||
          !issuer.trim() ||
          !origin ||
          !prescriptionExpiry ||
          !Number.isFinite(prescriptionExpiry.getTime()) ||
          prescriptionExpiry.getTime() <= Date.now())
      ) {
        return 'Consultá la receta y registrá referencia, emisor, vigencia futura y verificación de origen.';
      }
      if (special && !evidence)
        return 'Adjuntá evidencia de conservación y capacidad para esta categoría.';
      for (const line of order.lines) {
        const available = stock.data.find((row) => row.productId === line.productId);
        if (!available || available.availableQuantity < line.quantity)
          return `El stock informado de ${line.name} no alcanza. Actualizalo o rechazá para buscar otra farmacia.`;
        const value = decimal(coverage[line.productId] || '0');
        if (value === null)
          return `Ingresá una cobertura válida para ${line.name}, con hasta dos decimales.`;
        if (cents(value) > cents(available.price) * BigInt(line.quantity))
          return `La cobertura de ${line.name} supera el importe de las unidades solicitadas.`;
        if (
          cents(value) > 0n &&
          (!coverageRef[line.productId]?.trim() || !coverageDocs[line.productId])
        )
          return `La cobertura de ${line.name} necesita referencia y evidencia de autorización.`;
        const quantity = Number(authorized[line.productId] || line.quantity);
        if (
          line.requiresPrescription &&
          (!Number.isInteger(quantity) || quantity < line.quantity || quantity > 2147483647)
        )
          return `La cantidad autorizada de ${line.name} debe cubrir la cantidad solicitada.`;
      }
    }
    if (['payment', 'refund', 'invoice'].includes(action)) {
      if (!reference.trim() || !evidence)
        return 'Registrá la referencia y adjuntá el comprobante correspondiente.';
      if (action !== 'invoice' && !checked)
        return 'Confirmá que comprobaste la operación en el medio externo de la farmacia.';
    }
    if (
      action === 'assign-driver' &&
      !drivers.data?.some((person) => person.id === driver && person.available)
    )
      return 'Seleccioná un cadete propio disponible para esta entrega.';
    if (action === 'dispense' && needsPrescription && !reference.trim())
      return 'Registrá la referencia de dispensación o consumo de la receta comprobada.';
    if (action === 'dispense' && !checked)
      return 'Confirmá que comprobaste físicamente el pedido antes de registrar la dispensación.';
    if (action === 'dispense' && special && (!reason.trim() || !evidence))
      return 'Registrá los controles de conservación y adjuntá su evidencia.';
    if (
      action === 'pickup-complete' &&
      (!/^\d{6}$/.test(code.trim()) || !checked || !reason.trim())
    )
      return 'Ingresá el código de seis dígitos, verificá al receptor y registrá la constancia de retiro.';
    if (action === 'pickup-complete' && special && !evidence)
      return 'Adjuntá la evidencia de conservación al retirar.';
    if (['incident', 'resolve-incident'].includes(action) && !reason.trim())
      return 'Describí la situación y la resolución que se necesita.';
    if (action === 'resolve-incident' && !evidence)
      return 'Adjuntá la evidencia que respalda la evaluación farmacéutica.';
    if (action === 'cancel' && (!reason.trim() || !checked))
      return 'Indicá el motivo y confirmá que la farmacia no podrá cumplir este pedido.';
    if (
      action === 'reconcile-payment' &&
      (!['RECEIVED', 'NOT_RECEIVED'].includes(reconciliation) ||
        !reference.trim() ||
        !reason.trim() ||
        !evidence ||
        !checked)
    )
      return 'Elegí el resultado y documentá la comprobación externa con referencia, motivo y evidencia.';
    if (
      cold &&
      (action === 'dispense' ||
        action === 'pickup-complete' ||
        (action === 'resolve-incident' && resolution === 'RETRY')) &&
      (!temperature.trim() || !/^-?\d{1,3}(?:[.,]\d{1,2})?$/.test(temperature.trim()))
    )
      return 'Registrá la temperatura efectivamente observada, con hasta dos decimales.';
    if (
      cold &&
      (action === 'dispense' ||
        action === 'pickup-complete' ||
        (action === 'resolve-incident' && resolution === 'RETRY'))
    ) {
      const observed = Number(temperature.trim().replace(',', '.'));
      for (const line of order.lines.filter((item) => item.coldChain)) {
        if (line.minTemperatureC == null || line.maxTemperatureC == null)
          return `${line.name} no tiene un rango de conservación documentado. Resolvé la condición con la farmacia antes de continuar.`;
        if (observed < Number(line.minTemperatureC) || observed > Number(line.maxTemperatureC))
          return `La temperatura de ${line.name} está fuera del rango registrado. Detené el cumplimiento y documentá una incidencia.`;
      }
    }
    return null;
  }
  async function submit() {
    const failure = validation();
    if (failure) {
      setError(failure);
      return;
    }
    setBusy(true);
    setError('');
    let body: Record<string, unknown> = { expectedVersion: order.version };
    switch (action) {
      case 'review':
        body = {
          ...body,
          decision,
          reason,
          prescriptionReference: reference || null,
          prescriptionIssuer: issuer || null,
          prescriptionExpiresAt:
            decision === 'APPROVE' ? (prescriptionExpiry?.toISOString() ?? null) : null,
          originVerified: origin,
          authorizationChecked: checked,
          conditionEvidenceDocumentId: evidence || null,
          lines: order.lines.map((x) => ({
            productId: x.productId,
            coverageAmount: decimal(coverage[x.productId] || '0.00'),
            coverageReference: coverageRef[x.productId] || null,
            coverageEvidenceDocumentId: coverageDocs[x.productId] || null,
            authorizedQuantity: Number(authorized[x.productId] || x.quantity),
          })),
        };
        break;
      case 'payment':
      case 'refund':
        body = {
          ...body,
          reference,
          evidenceDocumentId: evidence || null,
          verifiedExternally: checked,
        };
        break;
      case 'dispense':
        body = {
          ...body,
          prescriptionConsumptionReference: reference || null,
          conditionEvidenceDocumentId: evidence || null,
          temperatureC: temperature.trim().replace(',', '.') || null,
          conditionNote: reason,
        };
        break;
      case 'assign-driver':
        body = { ...body, driverId: driver };
        break;
      case 'pickup-complete':
        body = {
          ...body,
          code: code.trim(),
          recipientVerified: checked,
          note: reason,
          conditionEvidenceDocumentId: evidence || null,
          temperatureC: temperature.trim().replace(',', '.') || null,
        };
        break;
      case 'incident':
        body = { ...body, reason, evidenceDocumentId: evidence || null };
        break;
      case 'cancel':
        body = { ...body, reason: reason.trim() };
        break;
      case 'reconcile-payment':
        body = {
          ...body,
          decision: reconciliation,
          reference: reference.trim(),
          reason: reason.trim(),
          evidenceDocumentId: evidence,
          verifiedExternally: checked,
        };
        break;
      case 'resolve-incident':
        body = {
          ...body,
          decision: resolution,
          reason,
          evidenceDocumentId: evidence || null,
          temperatureC: temperature.trim().replace(',', '.') || null,
        };
        break;
      case 'invoice':
        body = { ...body, evidenceDocumentId: evidence, reference };
        break;
    }
    try {
      await api(`/api/orders/${order.id}/${action}`, {
        method: 'POST',
        body,
        idempotent: [
          'payment',
          'refund',
          'dispense',
          'pickup-complete',
          'cancel',
          'reconcile-payment',
        ].includes(action),
      });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo registrar la acción.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>{titles[action]}</DialogTitle>
      <DialogContent dividers>
        <Stack
          spacing={2.5}
          component="form"
          id="order-action-form"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <Typography sx={{ fontWeight: 650 }}>{order.branchName}</Typography>
          {error && <Alert severity="error">{error}</Alert>}
          {action !== 'review' && (
            <Typography variant="body2" color="text.secondary">
              {order.lines
                .map((line) => `${line.name} · ${line.presentation} × ${line.quantity}`)
                .join(' / ')}
            </Typography>
          )}
          {cold &&
            ['review', 'dispense', 'pickup-complete', 'resolve-incident'].includes(action) && (
              <Alert severity="info">
                {order.lines
                  .filter((line) => line.coldChain)
                  .map((line) => (
                    <Typography key={line.productId} variant="body2">
                      {line.name}:{' '}
                      {line.minTemperatureC != null && line.maxTemperatureC != null
                        ? `rango registrado ${line.minTemperatureC} a ${line.maxTemperatureC} °C.`
                        : 'No tiene un rango de conservación documentado.'}
                    </Typography>
                  ))}
                {action === 'review'
                  ? 'Comprobá la capacidad de conservación de esta farmacia antes de aprobar la propuesta.'
                  : 'Registrá la observación física de este momento. Si el paquete no conserva sus condiciones, detené el cumplimiento y documentá una incidencia.'}
              </Alert>
            )}
          {action === 'review' && (
            <>
              {order.beneficiary && (
                <Typography>
                  Beneficiario: {order.beneficiary.name} · Nacimiento {order.beneficiary.birthDate}
                </Typography>
              )}
              {prescriptionDocument && (
                <Button
                  component="a"
                  href={`/api/documents/${prescriptionDocument}`}
                  target="_blank"
                  rel="noopener"
                >
                  Consultar receta privada
                </Button>
              )}
              {authorizationDocument && (
                <Button
                  component="a"
                  href={`/api/documents/${authorizationDocument}`}
                  target="_blank"
                  rel="noopener"
                >
                  Consultar autorización firmada
                </Button>
              )}
              {order.coverageRequest ? (
                <Alert severity="info">
                  <Typography sx={{ fontWeight: 650 }}>
                    Cobertura solicitada por el comprador
                  </Typography>
                  <Typography variant="body2">
                    {order.coverageRequest.provider} · Plan {order.coverageRequest.plan} ·
                    Afiliación {order.coverageRequest.memberNumber}
                  </Typography>
                  <Button
                    component="a"
                    href={`/api/documents/${order.coverageRequest.evidenceDocumentId}`}
                    target="_blank"
                    rel="noopener"
                  >
                    Consultar credencial de cobertura
                  </Button>
                  <Typography variant="body2">
                    Comprobá la afiliación y la autorización en la fuente correspondiente. El aporte
                    registrado abajo será el importe efectivamente autorizado.
                  </Typography>
                </Alert>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  El comprador solicitó una compra particular, sin declarar obra social o prepaga.
                </Typography>
              )}
              <TextField
                select
                label="Resultado de la revisión"
                value={decision}
                onChange={(e) => setDecision(e.target.value)}
              >
                <MenuItem value="APPROVE">Aprobar propuesta</MenuItem>
                <MenuItem value="REJECT">Rechazar y buscar alternativa</MenuItem>
              </TextField>
              <TextField
                required
                multiline
                minRows={2}
                label="Comprobaciones realizadas y motivo"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              {decision === 'REJECT' && (
                <Alert severity="warning">
                  Este pedido se retirará de la propuesta y se buscará una alternativa. El comprador
                  deberá aceptar las nuevas condiciones.
                </Alert>
              )}
              {decision === 'APPROVE' && (
                <>
                  <Alert severity="info">
                    Verificá el stock físico y el precio vigente antes de aprobar. La reserva se
                    hará cuando el comprador acepte la propuesta completa.
                    {order.highValueRequired && order.reviewCount < 2
                      ? ' Esta compra requiere la conformidad de dos farmacéuticos distintos.'
                      : ''}
                  </Alert>
                  <FormControlLabel
                    control={
                      <Checkbox checked={checked} onChange={(e) => setChecked(e.target.checked)} />
                    }
                    label="Verifiqué personalmente edad, identidad y autorización cuando corresponde"
                  />
                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={stockChecked}
                        onChange={(e) => setStockChecked(e.target.checked)}
                      />
                    }
                    label="Confirmé existencias físicas, precio vigente y presentación solicitada"
                  />
                  {needsPrescription && (
                    <>
                      <TextField
                        required
                        label="Referencia de receta y verificación de origen"
                        value={reference}
                        onChange={(e) => setReference(e.target.value)}
                      />
                      <TextField
                        required
                        label="Emisor de la receta"
                        value={issuer}
                        onChange={(e) => setIssuer(e.target.value)}
                      />
                      <TextField
                        required
                        type="datetime-local"
                        label="Vigencia de la receta"
                        value={expires}
                        onChange={(e) => setExpires(e.target.value)}
                        slotProps={{ inputLabel: { shrink: true } }}
                        helperText="Fecha y hora de vigencia comprobadas. Horario de Mendoza."
                      />
                      <FormControlLabel
                        control={
                          <Checkbox
                            checked={origin}
                            onChange={(e) => setOrigin(e.target.checked)}
                          />
                        }
                        label="Verifiqué el origen en la fuente correspondiente"
                      />
                    </>
                  )}
                  <Typography variant="subtitle1" sx={{ fontWeight: 650 }}>
                    Cobertura comprobada por presentación
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    El aporte corresponde al total de las unidades de cada presentación. Si no hay
                    cobertura, dejá cero. No se calcula un descuento automático.
                  </Typography>
                  {stock.isPending && (
                    <Typography>Consultando precios y stock de la sucursal…</Typography>
                  )}
                  {stock.error && <Alert severity="error">{stock.error.message}</Alert>}
                  {order.lines.map((x) => {
                    const current = stock.data?.find((row) => row.productId === x.productId);
                    const gross = current ? cents(current.price) * BigInt(x.quantity) : null;
                    const covered = decimal(coverage[x.productId] || '0');
                    const balance =
                      gross !== null && covered !== null ? gross - cents(covered) : null;
                    return (
                      <Stack
                        key={x.productId}
                        spacing={1}
                        sx={{ p: 2, border: '1px solid #dfe8e7', borderRadius: 2 }}
                      >
                        <Typography>
                          {x.name} · {x.presentation} · {x.quantity}{' '}
                          {x.quantity === 1 ? 'unidad' : 'unidades'}
                        </Typography>
                        {current && (
                          <Typography variant="body2" color="text.secondary">
                            {current.availableQuantity} disponibles · Precio por presentación{' '}
                            {money(current.price, current.currency)} · Importe sin cobertura{' '}
                            {money(amountFromCents(gross!), current.currency)}
                          </Typography>
                        )}
                        <TextField
                          label="Aporte total de obra social"
                          value={coverage[x.productId] ?? '0.00'}
                          onChange={(e) =>
                            setCoverage({ ...coverage, [x.productId]: e.target.value })
                          }
                          slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                          helperText="Importe total autorizado. Usá coma o punto decimal, sin separadores de miles."
                        />
                        {balance !== null && balance >= 0n && (
                          <Typography variant="body2" sx={{ fontWeight: 650 }}>
                            A pagar por el comprador:{' '}
                            {money(amountFromCents(balance), order.currency)}
                          </Typography>
                        )}
                        {x.requiresPrescription && (
                          <TextField
                            required
                            label="Cantidad autorizada en la receta"
                            type="number"
                            value={authorized[x.productId] ?? String(x.quantity)}
                            onChange={(e) =>
                              setAuthorized({ ...authorized, [x.productId]: e.target.value })
                            }
                            slotProps={{ htmlInput: { min: x.quantity, max: 2147483647, step: 1 } }}
                            helperText="Cantidad total autorizada para esta presentación en la receta, aunque esta farmacia entregue sólo una parte."
                          />
                        )}
                        {positiveCoverage(x.productId) && (
                          <>
                            <TextField
                              required
                              label="Referencia de autorización de obra social"
                              value={coverageRef[x.productId] ?? ''}
                              onChange={(e) =>
                                setCoverageRef({ ...coverageRef, [x.productId]: e.target.value })
                              }
                            />
                            <DocumentInput
                              kind="COBERTURA"
                              label="Evidencia de autorización de cobertura"
                              branchId={order.branchId}
                              value={coverageDocs[x.productId]}
                              onChange={(id) =>
                                setCoverageDocs({ ...coverageDocs, [x.productId]: id })
                              }
                            />
                            {coverageDocs[x.productId] && (
                              <Button
                                component="a"
                                href={`/api/documents/${coverageDocs[x.productId]}`}
                                target="_blank"
                                rel="noopener"
                              >
                                Consultar autorización de cobertura
                              </Button>
                            )}
                          </>
                        )}
                      </Stack>
                    );
                  })}
                  {special && (
                    <DocumentInput
                      kind="OTRO"
                      label="Evidencia de condiciones especiales"
                      branchId={order.branchId}
                      value={evidence}
                      onChange={setEvidence}
                    />
                  )}
                </>
              )}
            </>
          )}
          {(action === 'payment' || action === 'refund') && (
            <>
              <Typography variant="h6">
                {action === 'payment'
                  ? `Importe a comprobar: ${money(order.total, order.currency)}`
                  : `Reintegro pendiente: ${money(amountFromCents(order.refunds.filter((refund) => ['REQUESTED', 'PENDING'].includes(refund.status)).reduce((total, refund) => total + cents(refund.amount), 0n)), order.currency)}`}
              </Typography>
              <Alert severity="info">
                La farmacia realiza y comprueba la operación en su medio de pago. Este registro
                conserva la verificación y su evidencia.
              </Alert>
              {action === 'payment' && order.paymentSubmission && (
                <Alert severity="info">
                  El comprador presentó un comprobante el{' '}
                  {dateTime(order.paymentSubmission.submittedAt)}. Revisalo y comprobá la
                  acreditación por {money(order.total, order.currency)} en la cuenta de la farmacia
                  antes de confirmar.
                </Alert>
              )}
              {action === 'payment' && !order.paymentSubmission && (
                <Typography variant="body2" color="text.secondary">
                  El comprador todavía no presentó un comprobante. Podés registrar un pago
                  presencial u otro cobro comprobado por la farmacia.
                </Typography>
              )}
              {action === 'payment' && evidence && (
                <Button
                  component="a"
                  href={`/api/documents/${evidence}`}
                  target="_blank"
                  rel="noopener"
                  sx={{ alignSelf: 'flex-start' }}
                >
                  Consultar comprobante de pago
                </Button>
              )}
              <TextField
                required
                label="Referencia de la operación externa"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
              <DocumentInput
                label={
                  action === 'payment'
                    ? 'Comprobante de pago verificado'
                    : 'Comprobante de reintegro realizado'
                }
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
              <FormControlLabel
                control={
                  <Checkbox checked={checked} onChange={(e) => setChecked(e.target.checked)} />
                }
                label={
                  action === 'payment'
                    ? 'Comprobé que la farmacia recibió el importe completo'
                    : 'Comprobé que la farmacia reintegró el importe correspondiente'
                }
              />
            </>
          )}
          {action === 'prepare' && (
            <Alert severity="info">
              Confirmás el inicio de preparación de este pedido. La aplicación comprobará que la
              compra completa esté pagada y sus condiciones sigan vigentes. Separá las
              presentaciones y cantidades aceptadas; el stock se descontará al registrar la
              dispensación.
            </Alert>
          )}
          {action === 'dispense' && (
            <>
              <Alert severity="info">
                El farmacéutico registra la salida efectiva de las presentaciones reservadas. Se
                descontarán existencias, lotes y saldo de receta; cualquier diferencia debe
                resolverse antes de continuar.
              </Alert>
              {needsPrescription && (
                <TextField
                  required
                  label="Referencia de dispensación/consumo de receta"
                  value={reference}
                  onChange={(e) => setReference(e.target.value)}
                  helperText="Referencia comprobada en la fuente o registro de dispensación correspondiente."
                />
              )}
              <TextField
                required={special}
                label="Condiciones comprobadas"
                multiline
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              {cold && (
                <TextField
                  required
                  label="Temperatura observada (°C)"
                  value={temperature}
                  onChange={(e) => setTemperature(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                  helperText="El servidor comprobará el rango documentado de cada producto. Una excursión requiere una incidencia."
                />
              )}
              {special && (
                <DocumentInput
                  kind="OTRO"
                  label="Evidencia de conservación y dispensación"
                  branchId={order.branchId}
                  value={evidence}
                  onChange={setEvidence}
                />
              )}
              <FormControlLabel
                control={
                  <Checkbox
                    checked={checked}
                    onChange={(event) => setChecked(event.target.checked)}
                  />
                }
                label="Comprobé presentación, cantidad y condiciones; verifiqué la receta cuando corresponde"
              />
            </>
          )}
          {action === 'assign-driver' && (
            <>
              <Alert severity="info">
                Elegí un cadete propio disponible. Primero aceptará la tarea y después registrará la
                recepción física del paquete. Las categorías especiales también requieren
                capacidades documentadas.
              </Alert>
              {drivers.isPending && <Typography>Consultando cadetes de la sucursal…</Typography>}
              {drivers.error && <Alert severity="error">{drivers.error.message}</Alert>}
              {drivers.data && !drivers.data.some((person) => person.available) && (
                <Alert severity="warning">
                  No hay cadetes propios disponibles. Actualizá su disponibilidad o resolvé la
                  situación con el comprador antes de continuar.
                </Alert>
              )}
              <TextField
                select
                required
                label="Cadete de la sucursal"
                value={driver}
                onChange={(e) => setDriver(e.target.value)}
              >
                {drivers.data?.map((x) => (
                  <MenuItem key={x.id} value={x.id} disabled={!x.available}>
                    {x.name}
                    {!x.available ? ' · No disponible' : ''}
                  </MenuItem>
                ))}
              </TextField>
            </>
          )}
          {action === 'pickup-complete' && (
            <>
              <TextField
                required
                label="Código de recepción del comprador"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                slotProps={{
                  htmlInput: { inputMode: 'numeric', maxLength: 6, pattern: '[0-9]{6}' },
                }}
                helperText="Pedile al comprador que genere un código vigente al momento de retirar."
              />
              <FormControlLabel
                control={
                  <Checkbox checked={checked} onChange={(e) => setChecked(e.target.checked)} />
                }
                label="Verifiqué presencialmente al receptor y su autorización"
              />
              <TextField
                required
                label="Constancia de recepción"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                multiline
                minRows={2}
                helperText="Identificación del receptor y comprobación de la nota firmada cuando retira una persona autorizada."
              />
            </>
          )}
          {action === 'pickup-complete' && order.lines.some((x) => x.coldChain || x.restricted) && (
            <>
              {cold && (
                <TextField
                  required
                  label="Temperatura observada en la recepción (°C)"
                  value={temperature}
                  onChange={(e) => setTemperature(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                />
              )}
              <DocumentInput
                kind="OTRO"
                label="Evidencia de conservación al retirar"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'resolve-incident' && (
            <>
              <Alert severity="info">
                Registrá la evaluación farmacéutica y la resolución acordada. Una devolución no
                reingresa medicamentos al stock automáticamente.
              </Alert>
              <TextField
                select
                label="Resolución"
                value={resolution}
                onChange={(e) => setResolution(e.target.value)}
              >
                <MenuItem value="REFUND">Solicitar reintegro completo de lo cobrado</MenuItem>
                <MenuItem value="RETRY">Reintentar el cumplimiento seguro</MenuItem>
                <MenuItem value="CLOSE_WITHOUT_REFUND">
                  Cerrar con resolución farmacéutica documentada
                </MenuItem>
              </TextField>
              <TextField
                required
                label="Evaluación y acuerdo de resolución"
                multiline
                minRows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <DocumentInput
                kind="OTRO"
                label="Evidencia que respalda la resolución"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
              {resolution === 'RETRY' && order.lines.some((x) => x.coldChain) && (
                <TextField
                  required
                  label="Temperatura comprobada antes del reintento (°C)"
                  value={temperature}
                  onChange={(e) => setTemperature(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                />
              )}
            </>
          )}
          {action === 'incident' && (
            <>
              <TextField
                label="Qué ocurrió y qué resolución necesita"
                required
                multiline
                minRows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <DocumentInput
                kind="OTRO"
                label="Evidencia de la incidencia (opcional)"
                branchId={actor.branchIds.includes(order.branchId) ? order.branchId : undefined}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'invoice' && (
            <>
              <Alert severity="info">
                Adjuntá la factura emitida por esta farmacia para el pedido aceptado. El archivo
                quedará disponible para el comprador.
              </Alert>
              <TextField
                required
                label="Número/referencia de factura"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
              <DocumentInput
                label="Factura emitida por la farmacia"
                kind="FACTURA"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'cancel' && (
            <>
              <Alert severity="warning">
                Se liberarán las reservas de este pedido y se solicitará el reintegro completo de
                cualquier cobro registrado. La compra quedará pendiente de resolución con el
                comprador; las otras farmacias no continuarán automáticamente. El reintegro debe
                realizarlo y comprobarlo la farmacia.
              </Alert>
              <TextField
                required
                multiline
                minRows={3}
                label="Motivo de la cancelación de esta farmacia"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
              <FormControlLabel
                control={
                  <Checkbox
                    checked={checked}
                    onChange={(event) => setChecked(event.target.checked)}
                  />
                }
                label="Confirmo que esta farmacia no podrá cumplir el pedido aceptado"
              />
            </>
          )}
          {action === 'reconcile-payment' && (
            <>
              <Typography variant="h6">
                Importe informado: {money(order.total, order.currency)}
              </Typography>
              <Alert severity="warning">
                El comprador presentó un comprobante y este pedido ya está cerrado. Revisá si el
                dinero ingresó efectivamente a la farmacia. Si se recibió, se registrará el cobro y
                quedará solicitado su reintegro completo; la compra permanecerá cerrada y sus
                reservas liberadas.
              </Alert>
              {order.paymentSubmission && (
                <Button
                  component="a"
                  href={`/api/documents/${order.paymentSubmission.evidenceDocumentId}`}
                  target="_blank"
                  rel="noopener"
                >
                  Consultar comprobante presentado por el comprador
                </Button>
              )}
              <TextField
                select
                required
                label="Resultado de la conciliación"
                value={reconciliation}
                onChange={(event) => setReconciliation(event.target.value)}
              >
                <MenuItem value="RECEIVED">La farmacia recibió el importe completo</MenuItem>
                <MenuItem value="NOT_RECEIVED">La farmacia no recibió ese pago</MenuItem>
              </TextField>
              {reconciliation === 'NOT_RECEIVED' && (
                <Alert severity="info">
                  Se cerrará el reporte con la comprobación de que la farmacia no recibió fondos. La
                  evidencia permite al comprador revisar su operación con el medio de pago.
                </Alert>
              )}
              <TextField
                required
                label="Referencia de la verificación externa"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
              />
              <TextField
                required
                multiline
                minRows={3}
                label="Comprobaciones y motivo de la conciliación"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
              <DocumentInput
                label="Evidencia de la conciliación realizada"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
              <FormControlLabel
                control={
                  <Checkbox
                    checked={checked}
                    onChange={(event) => setChecked(event.target.checked)}
                  />
                }
                label="Revisé el medio de pago de la farmacia y comprobé el resultado registrado"
              />
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button disabled={busy} onClick={onClose}>
          Volver
        </Button>
        <Button
          variant="contained"
          color={action === 'cancel' ? 'error' : 'primary'}
          disabled={busy}
          type="submit"
          form="order-action-form"
        >
          {busy ? 'Guardando…' : 'Confirmar registro'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
