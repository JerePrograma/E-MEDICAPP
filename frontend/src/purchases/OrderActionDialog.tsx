import { useState } from 'react';
import {
  Alert,
  Box,
  Divider,
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
  | 'invoice-issue'
  | 'cancel'
  | 'reconcile-payment'
  | 'resolve-incident'
  | 'authorization'
  | 'refund-request'
  | 'financial-control-dispatch'
  | 'financial-control-refund'
  | 'financial-control-cancel'
  | 'fiscal-adjustment'
  | 'return-received';
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
  'invoice-issue': 'Informar problema de facturación',
  cancel: 'Cancelar el pedido de esta farmacia',
  'reconcile-payment': 'Conciliar el pago informado de un pedido cerrado',
  'resolve-incident': 'Resolver incidencia con la farmacia',
  authorization: 'Verificar autorización de esta compra',
  'refund-request': 'Solicitar reintegro parcial o total',
  'financial-control-dispatch': 'Revisar control de salida de alto valor',
  'financial-control-refund': 'Revisar control de reintegro de alto valor',
  'financial-control-cancel': 'Revisar cancelación de alto valor',
  'fiscal-adjustment': 'Registrar nota de crédito o débito',
  'return-received': 'Registrar recepción física del retorno',
};

function PaymentMethod({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <TextField
      select
      required
      label="Medio por el que se recibió el pago"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      <MenuItem value="TRANSFER">Transferencia bancaria</MenuItem>
      <MenuItem value="CASH">Efectivo</MenuItem>
      <MenuItem value="CARD">Tarjeta</MenuItem>
      <MenuItem value="OTHER">Otro medio comprobado</MenuItem>
    </TextField>
  );
}

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
  const pendingRefunds = order.refunds.filter((x) => ['REQUESTED', 'PENDING'].includes(x.status));
  const [refundId, setRefundId] = useState(pendingRefunds[0]?.id ?? '');
  const [amount, setAmount] = useState(
    action === 'refund'
      ? (pendingRefunds[0]?.amount ?? '0.00')
      : action === 'refund-request'
        ? (order.netPaidAmount ?? '0.00')
        : action === 'fiscal-adjustment'
          ? (pendingRefunds[0]?.amount ?? order.total)
          : (order.outstandingAmount ?? order.total),
  );
  const [rejectionKind, setRejectionKind] = useState('STOCK');
  const [splittable, setSplittable] = useState(false);
  const [deliveryMinutes, setDeliveryMinutes] = useState(
    order.deliveryMinutes ? String(order.deliveryMinutes) : '',
  );
  const [recipientName, setRecipientName] = useState(
    order.receiverName ?? purchase.beneficiary?.name ?? '',
  );
  const [recipientIdentity, setRecipientIdentity] = useState('');
  const [packageMatches, setPackageMatches] = useState(false);
  const [incidentKind, setIncidentKind] = useState('OTHER');
  const [retryScheduledAt, setRetryScheduledAt] = useState('');
  const [coordinationNote, setCoordinationNote] = useState('');
  const [fiscalKind, setFiscalKind] = useState('CREDIT');
  const [paymentMethod, setPaymentMethod] = useState('TRANSFER');
  const [selectedLines, setSelectedLines] = useState<string[]>([]);
  const [reason, setReason] = useState(action === 'cancel' ? (order.cancellationReason ?? '') : '');
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
      if (
        !/^\d+$/.test(deliveryMinutes) ||
        Number(deliveryMinutes) < 1 ||
        Number(deliveryMinutes) > 10080
      )
        return 'Informá un plazo real de preparación y recepción en minutos.';
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
    if (
      ['payment', 'refund', 'refund-request', 'fiscal-adjustment'].includes(action) ||
      (action === 'reconcile-payment' && reconciliation === 'RECEIVED')
    ) {
      const value = decimal(amount);
      if (!value || cents(value) < 0n || (action !== 'payment' && cents(value) === 0n))
        return 'Ingresá un importe válido, con hasta dos decimales y sin separadores de miles.';
      if (action === 'payment' && cents(value) > cents(order.outstandingAmount ?? order.total))
        return 'El cobro supera el saldo pendiente de esta farmacia.';
      if (action === 'refund-request' && cents(value) > cents(order.netPaidAmount ?? '0.00'))
        return 'El reintegro solicitado supera el saldo efectivamente cobrado y todavía no devuelto.';
    }
    if (action === 'refund' && !pendingRefunds.some((x) => x.id === refundId))
      return 'Elegí el reintegro pendiente que realizaste.';
    if (
      [
        'refund-request',
        'fiscal-adjustment',
        'authorization',
        'financial-control-dispatch',
        'financial-control-refund',
        'financial-control-cancel',
        'return-received',
        'invoice-issue',
      ].includes(action) &&
      !reason.trim()
    )
      return 'Registrá el motivo y las comprobaciones realizadas.';
    if (
      [
        'refund-request',
        'fiscal-adjustment',
        'financial-control-dispatch',
        'financial-control-refund',
        'financial-control-cancel',
        'return-received',
        'invoice-issue',
      ].includes(action) &&
      !evidence
    )
      return 'Adjuntá la evidencia de esta operación.';
    if (action === 'fiscal-adjustment' && !reference.trim())
      return 'Registrá la referencia de la nota emitida por la farmacia.';
    if (
      action === 'resolve-incident' &&
      resolution === 'RETRY' &&
      (!retryScheduledAt ||
        !coordinationNote.trim() ||
        !Number.isFinite(new Date(`${retryScheduledAt}:00-03:00`).getTime()) ||
        new Date(`${retryScheduledAt}:00-03:00`).getTime() <= Date.now())
    )
      return 'Acordá una fecha futura y documentá la coordinación del único reintento.';
    if (
      action === 'pickup-complete' &&
      (!recipientName.trim() ||
        !/^\d{7,12}$/.test(recipientIdentity.replace(/[.\s]/g, '')) ||
        !packageMatches)
    )
      return 'Comprobá nombre, DNI, medicamentos y cantidades antes de confirmar el retiro.';
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
          rejectionKind: decision === 'REJECT' ? rejectionKind : null,
          prescriptionSplittable: needsPrescription ? splittable : null,
          deliveryMinutes: Number(deliveryMinutes),
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
          amount: decimal(amount),
          currency: order.currency,
          ...(action === 'refund' ? { refundId } : {}),
          ...(action === 'payment' ? { method: paymentMethod } : {}),
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
          packageMatches,
          recipientName: recipientName.trim(),
          recipientIdentity: recipientIdentity.replace(/[.\s]/g, ''),
          authorizationDocumentId: authorizationDocument || null,
          note: reason,
          conditionEvidenceDocumentId: evidence || null,
          temperatureC: temperature.trim().replace(',', '.') || null,
        };
        break;
      case 'incident':
        body = { ...body, reason, kind: incidentKind, evidenceDocumentId: evidence || null };
        break;
      case 'cancel':
        body = { ...body, reason: reason.trim() };
        break;
      case 'reconcile-payment':
        body = {
          ...body,
          decision: reconciliation,
          ...(reconciliation === 'RECEIVED'
            ? { amount: decimal(amount), currency: order.currency, method: paymentMethod }
            : {}),
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
          ...(resolution === 'RETRY'
            ? {
                retryScheduledAt: new Date(`${retryScheduledAt}:00-03:00`).toISOString(),
                coordinationNote: coordinationNote.trim(),
              }
            : {}),
          reason,
          evidenceDocumentId: evidence || null,
          temperatureC: temperature.trim().replace(',', '.') || null,
        };
        break;
      case 'authorization':
        body = { ...body, decision, reason: reason.trim() };
        break;
      case 'refund-request':
        body = {
          ...body,
          amount: decimal(amount),
          currency: order.currency,
          reason: reason.trim(),
          evidenceDocumentId: evidence,
          ...(selectedLines.length ? { lineIds: selectedLines } : {}),
        };
        break;
      case 'financial-control-dispatch':
      case 'financial-control-refund':
      case 'financial-control-cancel':
        body = {
          ...body,
          operation:
            action === 'financial-control-dispatch'
              ? 'DISPATCH'
              : action === 'financial-control-cancel'
                ? 'CANCEL'
                : 'REFUND',
          reason: reason.trim(),
          evidenceDocumentId: evidence,
        };
        break;
      case 'fiscal-adjustment':
        body = {
          ...body,
          kind: fiscalKind,
          reference: reference.trim(),
          amount: decimal(amount),
          currency: order.currency,
          evidenceDocumentId: evidence,
          reason: reason.trim(),
        };
        break;
      case 'return-received':
        body = {
          ...body,
          custodyNote: reason.trim(),
          evidenceDocumentId: evidence,
          temperatureC: temperature.trim().replace(',', '.') || null,
        };
        break;
      case 'invoice':
        body = { ...body, evidenceDocumentId: evidence, reference };
        break;
      case 'invoice-issue':
        body = { ...body, reason: reason.trim(), evidenceDocumentId: evidence };
        break;
    }
    try {
      const result = await api<{ receiptRejected?: boolean; message?: string }>(
        `/api/orders/${order.id}/${action.startsWith('financial-control-') ? 'financial-control' : action}`,
        {
          method: 'POST',
          body,
          idempotent: [
            'payment',
            'refund',
            'dispense',
            'pickup-complete',
            'cancel',
            'reconcile-payment',
            'refund-request',
            'fiscal-adjustment',
            'authorization',
            'return-received',
            'invoice-issue',
            'financial-control-dispatch',
            'financial-control-refund',
            'financial-control-cancel',
          ].includes(action),
        },
      );
      if (result.receiptRejected) {
        setError(result.message ?? 'El código no coincide. Pedile al comprador un código vigente.');
        return;
      }
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo registrar la acción.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      open
      onClose={busy ? undefined : onClose}
      fullWidth
      maxWidth={action === 'review' ? 'md' : 'sm'}
    >
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
          <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 2 }}>
            <Typography sx={{ fontWeight: 650 }}>{order.branchName}</Typography>
            <Typography variant="caption" color="text.secondary">
              Pedido {order.id.slice(0, 8).toUpperCase()} · {money(order.total, order.currency)}
            </Typography>
          </Box>
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
              <Typography variant="overline" color="primary">
                1 · Identidad y documentos
              </Typography>
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
                  {order.coverageRequest.evidenceDocumentId && (
                    <Button
                      component="a"
                      href={`/api/documents/${order.coverageRequest.evidenceDocumentId}`}
                      target="_blank"
                      rel="noopener"
                    >
                      Consultar credencial de cobertura
                    </Button>
                  )}
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
                <MenuItem value="REJECT">Rechazar solicitud</MenuItem>
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
                <>
                  <TextField
                    select
                    label="Motivo del rechazo"
                    value={rejectionKind}
                    onChange={(e) => setRejectionKind(e.target.value)}
                  >
                    {Object.entries({
                      STOCK: 'Stock insuficiente',
                      CLINICAL: 'Impedimento clínico documentado',
                      VALIDITY: 'Receta o documento sin vigencia',
                      COVERAGE: 'Cobertura no comprobada',
                      CAPACITY: 'Capacidad insuficiente',
                      NON_SPLITTABLE: 'La receta requiere una única farmacia',
                      OTHER: 'Otra situación',
                    }).map(([value, title]) => (
                      <MenuItem key={value} value={value}>
                        {title}
                      </MenuItem>
                    ))}
                  </TextField>
                  <Alert severity="warning">
                    {['CLINICAL', 'VALIDITY'].includes(rejectionKind)
                      ? 'La compra se rechazará para resolver el impedimento. No se buscará otra farmacia con el mismo documento.'
                      : rejectionKind === 'NON_SPLITTABLE'
                        ? 'Se buscará una farmacia capaz de resolver la compra completa, según su disponibilidad.'
                        : 'Se buscará una alternativa disponible. El comprador deberá aceptar expresamente las nuevas condiciones.'}
                  </Alert>
                </>
              )}
              {decision === 'APPROVE' && (
                <>
                  <Alert severity="info">
                    Verificá el stock físico y el precio vigente antes de aprobar. La reserva se
                    hará al emitir el presupuesto completo, durante su vigencia.
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
                  <Divider />
                  <Typography variant="overline" color="primary">
                    2 · Receta y condiciones
                  </Typography>
                  {needsPrescription && (
                    <>
                      <FormControlLabel
                        control={
                          <Checkbox
                            checked={splittable}
                            onChange={(e) => setSplittable(e.target.checked)}
                          />
                        }
                        label="Comprobé que esta receta permite dispensación dividida entre farmacias"
                      />
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
                  <TextField
                    label={
                      purchase.mode === 'PICKUP'
                        ? 'Plazo estimado para estar listo (minutos)'
                        : 'Plazo estimado de preparación y entrega (minutos)'
                    }
                    type="number"
                    required
                    value={deliveryMinutes}
                    onChange={(e) => setDeliveryMinutes(e.target.value)}
                    slotProps={{ htmlInput: { min: 1, max: 10080, step: 1 } }}
                    helperText="Informá un plazo real para esta solicitud. El comprador lo verá antes de aceptar."
                  />
                  <Divider />
                  <Typography variant="overline" color="primary">
                    3 · Disponibilidad, cobertura e importe
                  </Typography>
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
                        sx={{
                          p: 2.5,
                          border: '1px solid',
                          borderColor: 'divider',
                          borderRadius: 2,
                          bgcolor: 'background.default',
                        }}
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
              <TextField
                label="Importe efectivamente comprobado"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                slotProps={{
                  htmlInput: { inputMode: 'decimal' },
                  input: { readOnly: action === 'refund' },
                }}
                helperText={`${order.currency}. Sin separadores de miles; hasta dos decimales.`}
              />
              {action === 'refund' && (
                <TextField
                  select
                  label="Reintegro realizado"
                  value={refundId}
                  onChange={(e) => {
                    setRefundId(e.target.value);
                    setAmount(
                      pendingRefunds.find((x) => x.id === e.target.value)?.amount ?? '0.00',
                    );
                  }}
                >
                  {pendingRefunds.map((x, index) => (
                    <MenuItem key={x.id} value={x.id}>
                      Reintegro {index + 1} · {money(x.amount, x.currency)}
                    </MenuItem>
                  ))}
                </TextField>
              )}
              <Typography variant="h6">
                {action === 'payment'
                  ? `Saldo pendiente: ${money(order.outstandingAmount ?? order.total, order.currency)}`
                  : `Reintegro pendiente: ${money(amountFromCents(order.refunds.filter((refund) => ['REQUESTED', 'PENDING'].includes(refund.status)).reduce((total, refund) => total + cents(refund.amount), 0n)), order.currency)}`}
              </Typography>
              <Alert severity="info">
                La farmacia realiza y comprueba la operación en su medio de pago. Este registro
                conserva la verificación y su evidencia.{' '}
                {action === 'payment'
                  ? 'Si se recibió sólo una parte, queda saldo pendiente y no comienza la preparación.'
                  : 'Registrá el reintegro individual efectivamente realizado.'}
              </Alert>
              {action === 'payment' && order.paymentSubmission && (
                <Alert severity="info">
                  El comprador presentó un comprobante el{' '}
                  {dateTime(order.paymentSubmission.submittedAt)}. Revisalo y comprobá la importe
                  efectivamente acreditado en la cuenta de la farmacia antes de confirmar.
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
              {action === 'payment' && (
                <PaymentMethod value={paymentMethod} onChange={setPaymentMethod} />
              )}
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
                    ? 'Comprobé que la farmacia recibió el importe registrado'
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
                label="Nombre de quien retira"
                required
                value={recipientName}
                onChange={(e) => setRecipientName(e.target.value)}
              />
              <TextField
                label="DNI comprobado de quien retira"
                required
                value={recipientIdentity}
                onChange={(e) => setRecipientIdentity(e.target.value)}
                slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                helperText="Transcribí el documento observado presencialmente."
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
                <MenuItem
                  value="RETRY"
                  disabled={
                    order.cancellationPending ||
                    (order.fulfillment?.retryCount ?? 0) >= 1 ||
                    order.fulfillment?.custodyHolder === 'DRIVER'
                  }
                >
                  Coordinar el único reintento permitido
                </MenuItem>
                <MenuItem value="CLOSE_WITHOUT_REFUND">
                  Cerrar con resolución farmacéutica documentada
                </MenuItem>
              </TextField>
              {resolution === 'RETRY' && (
                <>
                  <TextField
                    required
                    type="datetime-local"
                    label="Fecha acordada de reintento"
                    value={retryScheduledAt}
                    onChange={(e) => setRetryScheduledAt(e.target.value)}
                    slotProps={{ inputLabel: { shrink: true } }}
                    helperText="Horario de Mendoza. El paquete debe estar nuevamente en custodia de la farmacia."
                  />
                  <TextField
                    required
                    multiline
                    label="Coordinación con el comprador y el receptor"
                    value={coordinationNote}
                    onChange={(e) => setCoordinationNote(e.target.value)}
                  />
                </>
              )}
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
                select
                label="Tipo de incidencia"
                value={incidentKind}
                onChange={(e) => setIncidentKind(e.target.value)}
              >
                {Object.entries({
                  RECIPIENT_ABSENT: 'Receptor ausente',
                  RECIPIENT_REFUSED: 'Receptor rechaza el paquete',
                  PRODUCT_DISCREPANCY: 'Diferencia de medicamentos o cantidades',
                  TEMPERATURE: 'Conservación o temperatura',
                  OTHER: 'Otra situación',
                }).map(([value, title]) => (
                  <MenuItem
                    key={value}
                    value={value}
                    disabled={
                      ['RECIPIENT_ABSENT', 'RECIPIENT_REFUSED'].includes(value) &&
                      order.fulfillment?.status !== 'PICKED_UP'
                    }
                  >
                    {title}
                  </MenuItem>
                ))}
              </TextField>
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
                {order.highValueRequired && !order.cancellationPending
                  ? 'La solicitud detiene la preparación y libera las reservas. Dos farmacéuticos deben aprobar el control de cancelación antes de confirmar el cierre definitivo.'
                  : 'Se confirmará la cancelación de este pedido y se solicitará el reintegro de los cobros registrados. La farmacia debe realizar y comprobar la devolución.'}
                Las otras farmacias no continuarán automáticamente: se debe resolver la compra con
                el comprador.
              </Alert>
              <TextField
                required
                multiline
                minRows={3}
                label="Motivo de la cancelación de esta farmacia"
                slotProps={{ input: { readOnly: order.cancellationPending } }}
                helperText={
                  order.cancellationPending
                    ? 'Se conserva el motivo de la solicitud original.'
                    : undefined
                }
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
                <MenuItem value="RECEIVED">La farmacia recibió fondos</MenuItem>
                <MenuItem value="NOT_RECEIVED">La farmacia no recibió ese pago</MenuItem>
              </TextField>
              {reconciliation === 'RECEIVED' && (
                <>
                  <PaymentMethod value={paymentMethod} onChange={setPaymentMethod} />
                  <TextField
                    required
                    label="Importe efectivamente recibido"
                    value={amount}
                    onChange={(e) => setAmount(e.target.value)}
                    slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                    helperText={`${order.currency}. Se solicita la devolución del importe registrado.`}
                  />
                </>
              )}
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
          {action === 'authorization' && (
            <>
              <Alert severity="info">
                Verificá identidad y firma de la nota vinculada a esta compra. Debe autorizar la
                compra y la recepción de los medicamentos.
              </Alert>
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
              <TextField
                select
                label="Resultado de la verificación"
                value={decision}
                onChange={(e) => setDecision(e.target.value)}
              >
                <MenuItem value="APPROVE">Aprobar autorización</MenuItem>
                <MenuItem value="REQUEST_INFO">Solicitar información</MenuItem>
                <MenuItem value="REJECT">Rechazar autorización</MenuItem>
              </TextField>
              <TextField
                required
                multiline
                label="Comprobaciones y motivo"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </>
          )}
          {action === 'refund-request' && (
            <>
              <Alert severity="info">
                La solicitud registra el importe a devolver. La devolución queda pendiente hasta que
                la farmacia la realice y compruebe.
              </Alert>
              <Typography>
                Saldo cobrado sin devolver: {money(order.netPaidAmount ?? '0.00', order.currency)}
              </Typography>
              <TextField
                required
                label="Importe a reintegrar"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                helperText={`${order.currency}. Puede ser parcial o total.`}
                slotProps={{ htmlInput: { inputMode: 'decimal' } }}
              />
              <Typography variant="subtitle2">
                Presentaciones relacionadas, si corresponde
              </Typography>
              {order.lines
                .filter((x) => x.lineId)
                .map((x) => (
                  <FormControlLabel
                    key={x.lineId}
                    control={
                      <Checkbox
                        checked={selectedLines.includes(x.lineId!)}
                        onChange={(e) =>
                          setSelectedLines(
                            e.target.checked
                              ? [...selectedLines, x.lineId!]
                              : selectedLines.filter((id) => id !== x.lineId),
                          )
                        }
                      />
                    }
                    label={`${x.name} · ${x.presentation}`}
                  />
                ))}
              <TextField
                required
                multiline
                label="Motivo y acuerdo de reintegro"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <DocumentInput
                label="Evidencia del acuerdo de reintegro"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action.startsWith('financial-control-') && (
            <>
              <Alert severity="warning">
                Dos farmacéuticos distintos deben comprobar esta operación de alto valor. El
                servidor verifica que la aprobación corresponda al importe y a las condiciones
                vigentes.
              </Alert>
              <Typography>
                Operación:{' '}
                {action === 'financial-control-dispatch'
                  ? 'Salida física del paquete'
                  : action === 'financial-control-cancel'
                    ? 'Cancelación de la compra'
                    : 'Reintegro o cierre financiero'}
              </Typography>
              <TextField
                required
                multiline
                label="Comprobaciones realizadas para el control"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <DocumentInput
                label="Evidencia del control de alto valor"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'fiscal-adjustment' && (
            <>
              <Alert severity="info">
                Registrá el documento emitido por la farmacia, asociado a la factura original. Para
                devolver un cobro facturado, el crédito debe cubrir el importe reintegrado.
              </Alert>
              <TextField
                select
                label="Tipo de documento fiscal"
                value={fiscalKind}
                onChange={(e) => setFiscalKind(e.target.value)}
              >
                <MenuItem value="CREDIT">Nota de crédito</MenuItem>
                <MenuItem value="DEBIT">Nota de débito</MenuItem>
              </TextField>
              <TextField
                required
                label="Referencia de nota fiscal"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
              <TextField
                required
                label="Importe de la nota fiscal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                helperText={order.currency}
              />
              <TextField
                required
                multiline
                label="Motivo del ajuste fiscal"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <DocumentInput
                label="Nota emitida por la farmacia"
                kind="FACTURA"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'return-received' && (
            <>
              <Alert severity="warning">
                Confirmá que el paquete regresó físicamente a la farmacia. Este acto registra la
                custodia; no reincorpora medicamentos al stock.
              </Alert>
              <TextField
                required
                multiline
                label="Constancia de custodia y estado del paquete retornado"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              {cold && (
                <TextField
                  required
                  label="Temperatura observada al recibir el retorno (°C)"
                  value={temperature}
                  onChange={(e) => setTemperature(e.target.value)}
                />
              )}
              <DocumentInput
                kind="ENTREGA"
                label="Evidencia de recepción física del retorno"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
              />
            </>
          )}
          {action === 'invoice-issue' && (
            <>
              <Alert severity="warning">
                Registrá la falla comprobada al emitir la factura fuera de E-MEDICAPP.
                Administración recibirá un aviso prioritario. La farmacia debe resolver el problema
                en su sistema y adjuntar la factura válida; ese registro cierra el aviso.
              </Alert>
              <TextField
                required
                multiline
                minRows={3}
                label="Problema comprobado al emitir la factura"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                helperText="Describí el error operativo, sin incluir recetas ni información clínica."
              />
              <DocumentInput
                kind="OTRO"
                label="Evidencia operativa del problema de facturación"
                branchId={order.branchId}
                value={evidence}
                onChange={setEvidence}
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
          {busy
            ? 'Guardando…'
            : action === 'cancel' && order.cancellationPending
              ? 'Confirmar cancelación autorizada'
              : 'Confirmar registro'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
