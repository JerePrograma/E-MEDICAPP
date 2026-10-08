import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  FormControlLabel,
  Checkbox,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Actor, api, canOperateBranch, dateTime, label, money } from '../api';
import { DocumentInput } from '../components/DocumentInput';
import { CreatePurchase } from './CreatePurchase';
import { Action, OrderActionDialog } from './OrderActionDialog';
import { Order, Purchase } from './types';

function buyerGuidance(purchase: Purchase) {
  const pendingRefund = purchase.orders.some((x) =>
    x.refunds?.some((r) => r.status !== 'VERIFIED'),
  );
  switch (purchase.status) {
    case 'ROUTING':
      return [
        '1. La farmacia revisa tu solicitud',
        'Comprobará stock físico, receta y cobertura. La propuesta aparecerá aquí cuando todas las farmacias asignadas terminen la revisión.',
      ];
    case 'QUOTED':
      return [
        '2. Revisá el presupuesto y decidí',
        'Confirmá medicamentos, cantidades, cobertura, importe y farmacias. La reserva comienza cuando aceptás expresamente la propuesta vigente.',
      ];
    case 'ACCEPTED':
    case 'PARTIALLY_PAID':
      return [
        '3. Completá el pago directo',
        'Pagá el importe de cada farmacia según sus instrucciones y adjuntá el comprobante. La farmacia debe confirmar que recibió los fondos antes de preparar la compra completa.',
      ];
    case 'PAID':
    case 'FULFILLING':
      return [
        '4. Seguí la preparación y recepción',
        purchase.mode === 'PICKUP'
          ? 'Esperá que cada farmacia indique que el pedido está listo. Al retirar, mostrás tu código vigente y un documento de identidad; si va otra persona, necesita autorización firmada.'
          : 'Cada farmacia prepara su pedido y coordina su cadete. Mostrá el código vigente sólo al recibir los medicamentos; si recibe otra persona, necesita autorización firmada.',
      ];
    case 'COMPLETED':
      return [
        'Tu compra está completada',
        'Podés consultar las facturas de cada farmacia. Si detectás un problema con la recepción, informá una incidencia en el pedido correspondiente.',
      ];
    case 'COMPENSATING':
      return [
        'La compra necesita una resolución',
        pendingRefund
          ? 'La farmacia debe realizar y comprobar los reintegros pendientes. Los importes se muestran por farmacia; la solicitud de reintegro todavía no acredita una devolución.'
          : 'La farmacia revisará la incidencia y registrará una resolución. Consultá el estado de cada pedido; los medicamentos dispensados no se devuelven al stock automáticamente.',
      ];
    case 'CANCELLED':
      return [
        'Compra cancelada',
        pendingRefund
          ? 'Consultá los reintegros pendientes por farmacia. Se considerarán realizados cuando la farmacia confirme la devolución en su medio de pago.'
          : 'Se liberaron las reservas anteriores a la dispensación. Si había cobros registrados, consultá su devolución en el detalle de cada farmacia.',
      ];
    case 'RESOLVED':
      return [
        'Compra cerrada con resolución',
        'El cierre puede incluir una devolución o una solución registrada por la farmacia. Conservá las facturas y consultá los reintegros de cada pedido.',
      ];
    case 'UNAVAILABLE':
      return [
        'No encontramos disponibilidad para esta solicitud',
        'Podés intentar una nueva búsqueda o iniciar otra compra. No hay un importe confirmado ni corresponde pagar por esta solicitud.',
      ];
    default:
      return [
        'Seguí tu compra',
        'Las novedades y acciones disponibles se actualizan en esta pantalla.',
      ];
  }
}

function fulfillmentLabel(order: Order, mode: string) {
  const status = order.fulfillment?.status;
  if (!status) return undefined;
  return (
    (
      {
        READY: mode === 'PICKUP' ? 'Listo para retirar' : 'Listo para el cadete',
        ASSIGNED: 'Cadete asignado',
        ACCEPTED: 'El cadete aceptó la entrega',
        PICKED_UP: 'En camino al domicilio',
        DELIVERED: 'Entregado en el domicilio',
        COLLECTED: 'Retirado en la farmacia',
        INCIDENT: 'Incidencia en revisión',
        RESOLVED: 'Cumplimiento cerrado con resolución',
        CANCELLED: 'Entrega o retiro cancelado',
      } as Record<string, string>
    )[status] ?? label(status)
  );
}

export function PurchasePage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState('');
  const [action, setAction] = useState<{ kind: Action; order: Order } | null>(null);
  const [notice, setNotice] = useState('');
  const [consent, setConsent] = useState(false);
  const [consentVersion, setConsentVersion] = useState<number | null>(null);
  const [accepting, setAccepting] = useState(false);
  const [cancel, setCancel] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const [showPrevious, setShowPrevious] = useState(false);
  const [paymentOrder, setPaymentOrder] = useState<Order | null>(null);
  const [paymentReference, setPaymentReference] = useState('');
  const [paymentEvidence, setPaymentEvidence] = useState('');
  const [paymentError, setPaymentError] = useState('');
  const [receipt, setReceipt] = useState<{ order: string; code: string; expiresAt: string } | null>(
    null,
  );
  const purchases = useQuery({
    queryKey: ['purchases'],
    queryFn: () => api<Purchase[]>('/api/purchases'),
    refetchInterval: 10000,
  });
  const details = useQuery({
    queryKey: ['purchase', selected],
    queryFn: () => api<Purchase>(`/api/purchases/${selected}`),
    enabled: !!selected,
    refetchInterval: 5000,
  });
  const purchase = details.data;
  const buyer = actor.roles.includes('BUYER');
  const pharmacist = actor.roles.includes('PHARMACIST');
  const pharmacy = pharmacist || actor.roles.includes('PHARMACY');
  const ownsPurchase = buyer && !!purchase && purchase.buyerUserId === actor.id;
  const activeOrders =
    purchase?.orders.filter((x) => !['CANCELLED', 'RESOLVED'].includes(x.status)) ?? [];
  const previousOrders =
    purchase?.orders.filter((x) => ['CANCELLED', 'RESOLVED'].includes(x.status)) ?? [];
  const shownOrders =
    purchase?.orders.filter(
      (x) =>
        showPrevious ||
        !['CANCELLED', 'RESOLVED'].includes(x.status) ||
        ['CANCELLED', 'RESOLVED', 'COMPENSATING'].includes(purchase.status) ||
        x.refunds?.length,
    ) ?? [];
  const guidance = purchase && ownsPurchase ? buyerGuidance(purchase) : null;
  const visiblePurchases =
    purchases.data?.filter(
      (x) =>
        filter === 'ALL' ||
        (filter === 'OPEN'
          ? !['COMPLETED', 'CANCELLED', 'RESOLVED', 'UNAVAILABLE'].includes(x.status)
          : ['COMPLETED', 'CANCELLED', 'RESOLVED', 'UNAVAILABLE'].includes(x.status)),
    ) ?? [];
  const canPrepare =
    !!purchase &&
    purchase.allOrdersPaid === true &&
    ['PAID', 'FULFILLING'].includes(purchase.status);
  const quoteExpired =
    !!purchase?.quoteExpiresAt && new Date(purchase.quoteExpiresAt).getTime() <= Date.now();
  useEffect(() => {
    setReceipt(null);
    setShowPrevious(false);
  }, [selected]);
  useEffect(() => {
    if (accepting && purchase && consentVersion !== purchase.version) {
      setConsent(false);
      setConsentVersion(purchase.version);
      setNotice('La propuesta se actualizó. Revisá nuevamente las condiciones antes de aceptar.');
    }
  }, [accepting, purchase?.version, consentVersion]);
  useEffect(() => {
    if (!paymentOrder || !purchase) return;
    const currentOrder = purchase.orders.find((x) => x.id === paymentOrder.id);
    if (
      !currentOrder ||
      !['ACCEPTED', 'PAYMENT_PENDING'].includes(currentOrder.status) ||
      !['ACCEPTED', 'PARTIALLY_PAID'].includes(purchase.status)
    ) {
      setPaymentOrder(null);
      setNotice('Este pedido ya no espera un comprobante. Consultá su estado actualizado.');
    } else if (currentOrder.version !== paymentOrder.version) {
      setPaymentOrder(currentOrder);
    }
  }, [purchase?.version, paymentOrder?.id]);
  async function refresh() {
    await cache.invalidateQueries({ queryKey: ['purchases'] });
    await cache.invalidateQueries({ queryKey: ['purchase', selected] });
  }
  async function command(actionName: 'accept' | 'cancel' | 'reroute') {
    if (!purchase) return;
    if (
      actionName === 'accept' &&
      (!consent || consentVersion !== purchase.version || quoteExpired)
    ) {
      setConsent(false);
      setNotice('Revisá y aceptá las condiciones de una propuesta vigente antes de continuar.');
      return;
    }
    setBusy(true);
    setNotice('');
    try {
      await api(`/api/purchases/${purchase.id}/${actionName}`, {
        method: 'POST',
        idempotent: true,
        body: {
          expectedVersion: purchase.version,
          ...(actionName === 'cancel' ? { reason: cancelReason } : {}),
        },
      });
      setAccepting(false);
      setCancel(false);
      setConsent(false);
      await refresh();
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'No se pudo completar.');
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  async function reportPayment() {
    if (!paymentOrder || !paymentReference.trim() || !paymentEvidence) {
      setPaymentError(
        'Ingresá la referencia de la operación y adjuntá el comprobante antes de informar el pago.',
      );
      return;
    }
    setBusy(true);
    setPaymentError('');
    try {
      await api(`/api/orders/${paymentOrder.id}/payment-report`, {
        method: 'POST',
        idempotent: true,
        body: {
          expectedVersion: paymentOrder.version,
          reference: paymentReference.trim(),
          evidenceDocumentId: paymentEvidence,
        },
      });
      setPaymentOrder(null);
      await refresh();
    } catch (e) {
      setPaymentError(e instanceof Error ? e.message : 'No se pudo informar el pago.');
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  if (creating)
    return (
      <Stack spacing={2}>
        <Button onClick={() => setCreating(false)} sx={{ alignSelf: 'flex-start' }}>
          ← Volver a mis compras
        </Button>
        <CreatePurchase
          actor={actor}
          onCreated={(id) => {
            setCreating(false);
            setSelected(id);
            void refresh();
          }}
        />
      </Stack>
    );
  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between' }}
      >
        <Box>
          <Typography variant="h4">{pharmacy ? 'Solicitudes y pedidos' : 'Mis compras'}</Typography>
          <Typography color="text.secondary">
            {pharmacy
              ? 'Revisión, cobro y cumplimiento por sucursal.'
              : 'Tus propuestas, pagos y entregas en un solo lugar.'}
          </Typography>
        </Box>
        {buyer && (
          <Button variant="contained" onClick={() => setCreating(true)}>
            Nueva compra
          </Button>
        )}
      </Stack>
      {notice && (
        <Alert severity="warning" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {purchases.error && <Alert severity="error">{purchases.error.message}</Alert>}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '300px minmax(0,1fr)' },
          gap: 3,
          alignItems: 'start',
        }}
      >
        <Stack spacing={1.5}>
          <FormControl size="small" fullWidth>
            <InputLabel id="purchase-filter-label">Mostrar compras</InputLabel>
            <Select
              labelId="purchase-filter-label"
              label="Mostrar compras"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            >
              <MenuItem value="ALL">Todas</MenuItem>
              <MenuItem value="OPEN">En curso</MenuItem>
              <MenuItem value="CLOSED">Finalizadas</MenuItem>
            </Select>
          </FormControl>
          {purchases.isPending && <CircularProgress size={24} />}{' '}
          {purchases.data?.length === 0 && (
            <Alert severity="info">Todavía no hay compras para esta cuenta.</Alert>
          )}
          {purchases.data && purchases.data.length > 0 && visiblePurchases.length === 0 && (
            <Typography color="text.secondary">No hay compras para este filtro.</Typography>
          )}
          {visiblePurchases.map((x) => (
            <Card
              key={x.id}
              sx={{ border: x.id === selected ? '2px solid #14766f' : '1px solid #dfe8e7' }}
            >
              <CardContent>
                <Typography sx={{ fontWeight: 650 }}>
                  Compra {x.id.slice(0, 8).toUpperCase()}
                </Typography>
                <Chip size="small" label={label(x.status)} sx={{ my: 1 }} />
                <Typography variant="body2">
                  {label(x.mode)} ·{' '}
                  {['ROUTING', 'UNAVAILABLE'].includes(x.status)
                    ? 'Importe a confirmar'
                    : money(x.total, x.currency)}
                </Typography>
                {x.createdAt && (
                  <Typography variant="caption" color="text.secondary">
                    {dateTime(x.createdAt)}
                  </Typography>
                )}
                {x.beneficiary?.name && (
                  <Typography variant="body2" sx={{ mt: 1 }}>
                    {x.beneficiary.name}
                  </Typography>
                )}
                <Button
                  fullWidth
                  onClick={() => {
                    setSelected(x.id);
                    setReceipt(null);
                  }}
                >
                  Ver detalle
                </Button>
              </CardContent>
            </Card>
          ))}
        </Stack>
        {!selected ? (
          <Card>
            <CardContent sx={{ p: 4 }}>
              <Typography variant="h5">Una propuesta clara, antes de pagar.</Typography>
              <Typography color="text.secondary" sx={{ mt: 2 }}>
                Cada farmacia informa su disponibilidad y verifica las condiciones. Podés ver el
                desglose y aceptar antes de que comience la preparación.
              </Typography>
            </CardContent>
          </Card>
        ) : details.isPending ? (
          <CircularProgress />
        ) : details.error ? (
          <Alert severity="error">{details.error.message}</Alert>
        ) : (
          purchase && (
            <Stack spacing={2}>
              <Card>
                <CardContent>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    <Typography variant="h6">
                      Compra {purchase.id.slice(0, 8).toUpperCase()}
                    </Typography>
                    <Chip label={label(purchase.status)} />
                  </Stack>
                  <Typography sx={{ mt: 1 }}>{label(purchase.mode)}</Typography>
                  {purchase.createdAt && (
                    <Typography variant="body2" color="text.secondary">
                      Solicitud del {dateTime(purchase.createdAt)}
                    </Typography>
                  )}
                  {purchase.beneficiary && (
                    <Typography sx={{ mt: 1 }}>
                      Para {purchase.beneficiary.name}
                      {purchase.thirdParty ? ' · familiar autorizado' : ''}
                    </Typography>
                  )}
                  {purchase.mode === 'DELIVERY' && purchase.destinationAddress && (
                    <Typography variant="body2">
                      Domicilio de entrega: {purchase.destinationAddress}
                    </Typography>
                  )}
                  {guidance && (
                    <Alert
                      severity={
                        purchase.status === 'COMPENSATING' || purchase.status === 'UNAVAILABLE'
                          ? 'warning'
                          : purchase.status === 'COMPLETED'
                            ? 'success'
                            : 'info'
                      }
                      sx={{ mt: 2 }}
                    >
                      <Typography sx={{ fontWeight: 650 }}>{guidance[0]}</Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        {guidance[1]}
                      </Typography>
                    </Alert>
                  )}
                  {purchase.message &&
                    ['ROUTING', 'QUOTED', 'UNAVAILABLE'].includes(purchase.status) && (
                      <Alert severity="info" sx={{ mt: 2 }}>
                        {purchase.message}
                      </Alert>
                    )}
                  {purchase.status === 'QUOTED' && purchase.quoteExpiresAt && (
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                      Propuesta vigente hasta {dateTime(purchase.quoteExpiresAt)}
                    </Typography>
                  )}
                  {ownsPurchase &&
                    ['ACCEPTED', 'PARTIALLY_PAID'].includes(purchase.status) &&
                    purchase.reserveExpiresAt && (
                      <Alert severity="warning" sx={{ mt: 2 }}>
                        Completá el pago y su comprobación antes del{' '}
                        {dateTime(purchase.reserveExpiresAt)}. Si la reserva vence antes de que se
                        verifiquen todos los pagos, se cancela la compra y se solicitan los
                        reintegros de los cobros registrados.
                      </Alert>
                    )}
                  {activeOrders.length > 1 && (
                    <Alert severity="info" sx={{ mt: 2 }}>
                      La compra se distribuyó entre {activeOrders.length} farmacias. Cada una recibe
                      su importe, emite su factura y realiza su entrega o retiro. La preparación
                      espera la confirmación de todos los pagos.
                    </Alert>
                  )}
                  <Typography variant="h5" sx={{ my: 2 }}>
                    {['ROUTING', 'UNAVAILABLE'].includes(purchase.status)
                      ? 'Importe a confirmar por las farmacias'
                      : `Total ${money(purchase.total, purchase.currency)}`}
                  </Typography>
                  <Typography variant="body2">
                    Envío y uso de E-MEDICAPP sin costo para el comprador.
                  </Typography>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 2 }}>
                    {ownsPurchase && purchase.status === 'QUOTED' && (
                      <Button
                        variant="contained"
                        disabled={busy || quoteExpired}
                        onClick={() => {
                          setAccepting(true);
                          setConsent(false);
                          setConsentVersion(purchase.version);
                        }}
                      >
                        Revisar y aceptar presupuesto
                      </Button>
                    )}
                    {ownsPurchase && purchase.status === 'QUOTED' && quoteExpired && (
                      <Typography variant="body2" color="error">
                        Este presupuesto venció. Pedí una nueva propuesta para confirmar
                        disponibilidad e importes.
                      </Typography>
                    )}
                    {ownsPurchase &&
                      ['ROUTING', 'QUOTED', 'UNAVAILABLE'].includes(purchase.status) && (
                        <Button disabled={busy} onClick={() => void command('reroute')}>
                          Buscar una nueva propuesta
                        </Button>
                      )}
                    {ownsPurchase &&
                      !['COMPLETED', 'CANCELLED', 'UNAVAILABLE', 'RESOLVED'].includes(
                        purchase.status,
                      ) && (
                        <Button
                          color="error"
                          onClick={() => {
                            setCancelReason('');
                            setCancel(true);
                          }}
                        >
                          Solicitar cancelación
                        </Button>
                      )}
                  </Stack>
                </CardContent>
              </Card>
              {previousOrders.length > 0 &&
                !['CANCELLED', 'RESOLVED', 'COMPENSATING'].includes(purchase.status) && (
                  <Button
                    sx={{ alignSelf: 'flex-start' }}
                    onClick={() => setShowPrevious(!showPrevious)}
                  >
                    {showPrevious
                      ? 'Ocultar alternativas anteriores'
                      : `Ver ${previousOrders.length} ${previousOrders.length === 1 ? 'pedido anterior' : 'pedidos anteriores'}`}
                  </Button>
                )}
              {shownOrders.map((order) => {
                const canOperate = canOperateBranch(
                  actor,
                  order.branchId,
                  'PHARMACY',
                  'PHARMACIST',
                );
                const canReview = canOperateBranch(actor, order.branchId, 'PHARMACIST');
                const isIncident = order.fulfillment?.status === 'INCIDENT';
                return (
                  <Card key={order.id}>
                    <CardContent>
                      <Stack spacing={2}>
                        <Stack
                          direction={{ xs: 'column', sm: 'row' }}
                          sx={{ justifyContent: 'space-between' }}
                          spacing={1}
                        >
                          <Typography variant="h6">{order.branchName}</Typography>
                          <Chip size="small" label={label(order.status)} />
                        </Stack>
                        {order.branchAddress && (
                          <Typography variant="body2" color="text.secondary">
                            {purchase.mode === 'PICKUP' ? 'Retirá en: ' : 'Sucursal: '}
                            {order.branchAddress}
                          </Typography>
                        )}
                        {order.merchantName && (
                          <Typography variant="body2" color="text.secondary">
                            Factura y cobro: {order.merchantName}
                          </Typography>
                        )}
                        {fulfillmentLabel(order, purchase.mode) && (
                          <Alert
                            severity={
                              isIncident
                                ? 'warning'
                                : ['DELIVERED', 'COLLECTED'].includes(
                                      order.fulfillment?.status ?? '',
                                    )
                                  ? 'success'
                                  : 'info'
                            }
                          >
                            {fulfillmentLabel(order, purchase.mode)}
                          </Alert>
                        )}
                        {order.highValueRequired && (
                          <Alert severity={order.reviewCount >= 2 ? 'success' : 'warning'}>
                            Revisión de alto valor: {order.reviewCount} de 2 profesionales
                            distintos.{' '}
                            {order.reviewCount < 2
                              ? 'Se requiere una segunda cuenta farmacéutica de esta sucursal.'
                              : ''}
                          </Alert>
                        )}
                        {(order.lines ?? []).map((line, i) => (
                          <Box key={`${line.productId}-${i}`}>
                            <Typography sx={{ fontWeight: 600 }}>
                              {line.name} · {line.quantity}{' '}
                              {line.quantity === 1 ? 'unidad' : 'unidades'}
                            </Typography>
                            {order.reviewCount > 0 ? (
                              <Typography variant="body2" color="text.secondary">
                                {line.presentation} · Precio unitario{' '}
                                {money(line.unitPrice, order.currency)} · Cobertura{' '}
                                {money(line.coverageAmount, order.currency)} · A pagar{' '}
                                {money(line.total, order.currency)}
                              </Typography>
                            ) : (
                              <Typography variant="body2" color="text.secondary">
                                {line.presentation} · Precio y cobertura a confirmar por la
                                farmacia.
                              </Typography>
                            )}
                          </Box>
                        ))}
                        <Divider />
                        <Typography sx={{ fontWeight: 650 }}>
                          {order.reviewCount > 0
                            ? `Importe de esta farmacia: ${money(order.total, order.currency)}`
                            : 'Importe de esta farmacia a confirmar.'}
                        </Typography>
                        {ownsPurchase &&
                          ['ACCEPTED', 'PARTIALLY_PAID'].includes(purchase.status) &&
                          purchase.messages?.paymentPending && (
                            <Alert severity="info">{purchase.messages.paymentPending}</Alert>
                          )}
                        {order.paymentInstructions &&
                          ownsPurchase &&
                          order.status === 'ACCEPTED' &&
                          Number(order.total) > 0 && (
                            <Alert severity="info">
                              Pago directo a la farmacia: {order.paymentInstructions}
                            </Alert>
                          )}
                        {ownsPurchase &&
                          order.status === 'ACCEPTED' &&
                          Number(order.total) === 0 && (
                            <Alert severity="info">
                              Tu saldo en esta farmacia es cero. No hagas una transferencia: la
                              farmacia debe verificar la cobertura antes de continuar.
                            </Alert>
                          )}
                        {order.paymentSubmission && order.status === 'ACCEPTED' && (
                          <Alert severity="info">
                            <Typography sx={{ fontWeight: 650 }}>
                              Comprobante enviado; la farmacia debe confirmar el cobro
                            </Typography>
                            <Typography variant="body2">
                              Referencia {order.paymentSubmission.reference} ·{' '}
                              {dateTime(order.paymentSubmission.submittedAt)}
                            </Typography>
                            <Button
                              component="a"
                              href={`/api/documents/${order.paymentSubmission.evidenceDocumentId}`}
                              target="_blank"
                              rel="noopener"
                            >
                              Ver comprobante enviado
                            </Button>
                          </Alert>
                        )}
                        {order.paymentSubmission?.status === 'PENDING' &&
                          order.status === 'CANCELLED' && (
                            <Alert severity="warning">
                              El pedido está cancelado y el comprobante informado sigue pendiente de
                              conciliación. La farmacia debe comprobar si ingresó el dinero; si lo
                              recibió, corresponde tramitar su reintegro completo.
                            </Alert>
                          )}
                        {order.paymentSubmission?.status === 'NOT_RECEIVED' && (
                          <Alert severity="warning">
                            La farmacia terminó la conciliación y no identificó el ingreso del pago
                            informado. Consultá directamente con la farmacia y tu medio de pago
                            usando la referencia del comprobante.
                          </Alert>
                        )}
                        {order.payments?.map((payment) => (
                          <Alert key={payment.id} severity="info">
                            Pago directo: {money(payment.amount, payment.currency)} ·{' '}
                            {label(payment.status)}
                            {payment.external_reference
                              ? ` · Referencia ${payment.external_reference}`
                              : ''}
                          </Alert>
                        ))}
                        {order.refunds?.map((refund) => (
                          <Alert
                            key={refund.id}
                            severity={refund.status === 'VERIFIED' ? 'success' : 'warning'}
                          >
                            Reintegro: {money(refund.amount, refund.currency)} ·{' '}
                            {label(refund.status)}
                          </Alert>
                        ))}
                        {ownsPurchase && order.fulfillment?.location && (
                          <Alert severity="info">
                            Última ubicación informada por el cadete:{' '}
                            {dateTime(order.fulfillment.location.observedAt)}.{' '}
                            <Button
                              component="a"
                              href={`https://www.openstreetmap.org/?mlat=${order.fulfillment.location.lat}&mlon=${order.fulfillment.location.lon}#map=16/${order.fulfillment.location.lat}/${order.fulfillment.location.lon}`}
                              target="_blank"
                              rel="noopener"
                            >
                              Ver ubicación en el mapa
                            </Button>
                          </Alert>
                        )}
                        {order.invoiceDocumentId && (
                          <Button
                            component="a"
                            href={`/api/documents/${order.invoiceDocumentId}`}
                            target="_blank"
                            rel="noopener"
                          >
                            Ver factura de la farmacia
                          </Button>
                        )}
                        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                          {canReview &&
                            !order.reviewApproved &&
                            ['PROPOSED', 'REVIEW_PENDING', 'QUOTED'].includes(order.status) && (
                              <Button
                                variant="contained"
                                onClick={() => setAction({ kind: 'review', order })}
                              >
                                Revisar solicitud
                              </Button>
                            )}
                          {ownsPurchase &&
                            ['ACCEPTED', 'PAYMENT_PENDING'].includes(order.status) &&
                            Number(order.total) > 0 &&
                            ['ACCEPTED', 'PARTIALLY_PAID'].includes(purchase.status) && (
                              <Button
                                variant="contained"
                                disabled={busy}
                                onClick={() => {
                                  setPaymentOrder(order);
                                  setPaymentReference('');
                                  setPaymentEvidence('');
                                  setPaymentError('');
                                }}
                              >
                                {order.paymentSubmission
                                  ? 'Actualizar comprobante de pago'
                                  : 'Informar pago'}
                              </Button>
                            )}
                          {canOperate &&
                            ['ACCEPTED', 'PAYMENT_PENDING'].includes(order.status) &&
                            ['ACCEPTED', 'PARTIALLY_PAID'].includes(purchase.status) && (
                              <Button
                                variant="contained"
                                onClick={() => setAction({ kind: 'payment', order })}
                              >
                                Verificar pago
                              </Button>
                            )}
                          {canOperate && order.status === 'PAID' && (
                            <Button
                              variant="contained"
                              disabled={!canPrepare}
                              onClick={() => setAction({ kind: 'prepare', order })}
                            >
                              Preparar
                            </Button>
                          )}
                          {canReview && order.status === 'PREPARING' && !isIncident && (
                            <Button
                              variant="contained"
                              disabled={!order.invoiceDocumentId}
                              onClick={() => setAction({ kind: 'dispense', order })}
                            >
                              Registrar dispensación
                            </Button>
                          )}
                          {canOperate &&
                            ['PAID', 'PREPARING', 'DISPENSED'].includes(order.status) &&
                            !isIncident && (
                              <Button onClick={() => setAction({ kind: 'invoice', order })}>
                                {order.invoiceDocumentId
                                  ? 'Reemplazar factura'
                                  : 'Adjuntar factura'}
                              </Button>
                            )}
                          {canOperate &&
                            !isIncident &&
                            order.status === 'DISPENSED' &&
                            purchase.mode === 'DELIVERY' && (
                              <Button
                                variant="contained"
                                onClick={() => setAction({ kind: 'assign-driver', order })}
                              >
                                Asignar cadete
                              </Button>
                            )}
                          {canReview &&
                            !isIncident &&
                            order.status === 'DISPENSED' &&
                            purchase.mode === 'PICKUP' && (
                              <Button
                                variant="contained"
                                onClick={() => setAction({ kind: 'pickup-complete', order })}
                              >
                                Confirmar retiro
                              </Button>
                            )}
                          {ownsPurchase &&
                            !isIncident &&
                            ['DISPENSED', 'DISPATCHED'].includes(order.status) && (
                              <Button
                                onClick={async () => {
                                  try {
                                    const code = await api<{ code: string; expiresAt: string }>(
                                      `/api/orders/${order.id}/receipt-code`,
                                    );
                                    setReceipt({ order: order.id, ...code });
                                  } catch (e) {
                                    setNotice(
                                      e instanceof Error
                                        ? e.message
                                        : 'No se pudo consultar el código.',
                                    );
                                  }
                                }}
                              >
                                Mi código de recepción
                              </Button>
                            )}
                          {canOperate &&
                            order.refunds?.some((r) =>
                              ['REQUESTED', 'PENDING'].includes(r.status),
                            ) && (
                              <Button onClick={() => setAction({ kind: 'refund', order })}>
                                Registrar reintegro
                              </Button>
                            )}
                          {canOperate &&
                            !isIncident &&
                            ['ACCEPTED', 'PAID', 'PREPARING'].includes(order.status) && (
                              <Button
                                color="error"
                                onClick={() => setAction({ kind: 'cancel', order })}
                              >
                                Cancelar este pedido
                              </Button>
                            )}
                          {canOperate &&
                            ['CANCELLED', 'RESOLVED'].includes(order.status) &&
                            order.paymentSubmission?.status === 'PENDING' && (
                              <Button
                                variant="contained"
                                onClick={() => setAction({ kind: 'reconcile-payment', order })}
                              >
                                Conciliar pago informado
                              </Button>
                            )}
                          {canReview && order.fulfillment?.status === 'INCIDENT' && (
                            <Button
                              variant="contained"
                              onClick={() => setAction({ kind: 'resolve-incident', order })}
                            >
                              Resolver incidencia
                            </Button>
                          )}
                          {(ownsPurchase || canOperate) &&
                            order.fulfillment &&
                            !['CANCELLED', 'RESOLVED', 'INCIDENT'].includes(
                              order.fulfillment.status,
                            ) && (
                              <Button onClick={() => setAction({ kind: 'incident', order })}>
                                Informar incidencia
                              </Button>
                            )}
                        </Stack>
                        {canOperate && order.status === 'PROPOSED' && order.reviewApproved && (
                          <Typography variant="body2" color="text.secondary">
                            {purchase.status === 'QUOTED'
                              ? 'Presupuesto disponible. Se espera que el comprador acepte las condiciones antes de recibir el pago.'
                              : 'Esta sucursal terminó la revisión. Se espera la revisión de las otras farmacias para presentar el presupuesto completo.'}
                          </Typography>
                        )}
                        {canOperate && order.status === 'PAID' && !canPrepare && (
                          <Typography variant="body2" color="text.secondary">
                            La preparación está bloqueada hasta que todos los pagos de la compra
                            estén comprobados.
                          </Typography>
                        )}
                        {canReview && order.status === 'PREPARING' && !order.invoiceDocumentId && (
                          <Typography variant="body2" color="text.secondary">
                            Adjuntá la factura emitida por la farmacia antes de registrar la
                            dispensación.
                          </Typography>
                        )}
                        {receipt?.order === order.id &&
                          !isIncident &&
                          ['DISPENSED', 'DISPATCHED'].includes(order.status) && (
                            <Alert
                              severity={
                                new Date(receipt.expiresAt).getTime() > Date.now()
                                  ? 'success'
                                  : 'warning'
                              }
                            >
                              <Typography>
                                Código de recepción: <strong>{receipt.code}</strong>
                              </Typography>
                              <Typography variant="body2">
                                Vigente hasta {dateTime(receipt.expiresAt)}. Mostralo sólo al
                                recibir. Cada código nuevo reemplaza al anterior; si recibe otra
                                persona, debe llevar la autorización firmada del titular.
                              </Typography>
                              {new Date(receipt.expiresAt).getTime() <= Date.now() && (
                                <Typography variant="body2">
                                  El código venció. Generá otro con «Mi código de recepción» cuando
                                  estés listo para recibir.
                                </Typography>
                              )}
                            </Alert>
                          )}
                      </Stack>
                    </CardContent>
                  </Card>
                );
              })}
            </Stack>
          )
        )}
      </Box>
      {action && purchase && (
        <OrderActionDialog
          key={`${action.order.id}:${action.kind}`}
          action={action.kind}
          order={action.order}
          purchase={purchase}
          actor={actor}
          onClose={() => setAction(null)}
          onDone={() => {
            setAction(null);
            void refresh();
          }}
        />
      )}
      <Dialog
        open={!!paymentOrder}
        onClose={busy ? undefined : () => setPaymentOrder(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Informar pago directo a la farmacia</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            {paymentError && <Alert severity="warning">{paymentError}</Alert>}
            <Typography sx={{ fontWeight: 650 }}>{paymentOrder?.branchName}</Typography>
            <Typography variant="h6">
              Importe a pagar: {money(paymentOrder?.total, paymentOrder?.currency)}
            </Typography>
            {paymentOrder?.merchantName && (
              <Typography>Recibe el pago: {paymentOrder.merchantName}</Typography>
            )}
            {paymentOrder?.paymentInstructions && (
              <Alert severity="info">{paymentOrder.paymentInstructions}</Alert>
            )}
            <Typography>
              Adjuntar el comprobante informa tu operación. La farmacia debe comprobar el cobro en
              su medio de pago; el pedido seguirá esperando esa confirmación.
            </Typography>
            {paymentOrder?.paymentSubmission && (
              <Alert severity="warning">
                Ya informaste un comprobante. Esta actualización reemplaza el comprobante vigente
                para su revisión y conserva el registro anterior en el historial.
              </Alert>
            )}
            <TextField
              required
              label="Referencia del pago realizado"
              value={paymentReference}
              onChange={(e) => setPaymentReference(e.target.value)}
              helperText="Número de operación, referencia de transferencia o identificación del pago directo."
            />
            <DocumentInput
              kind="EVIDENCIA"
              label="Comprobante del pago a la farmacia"
              value={paymentEvidence}
              onChange={setPaymentEvidence}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setPaymentOrder(null)}>
            Volver
          </Button>
          <Button
            variant="contained"
            disabled={busy || !paymentReference.trim() || !paymentEvidence}
            onClick={() => void reportPayment()}
          >
            {busy ? 'Enviando comprobante…' : 'Enviar comprobante'}
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={accepting}
        onClose={busy ? undefined : () => setAccepting(false)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Aceptar las condiciones de la compra</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            {notice && <Alert severity="warning">{notice}</Alert>}
            <Typography>
              Se pagará directamente a cada farmacia. Cada una emitirá su factura y resolverá su
              entrega o retiro.
            </Typography>
            {purchase?.mode === 'DELIVERY' && purchase.destinationAddress && (
              <Typography>Domicilio de entrega: {purchase.destinationAddress}</Typography>
            )}
            {purchase?.quoteExpiresAt && (
              <Typography variant="body2" color="text.secondary">
                Propuesta vigente hasta {dateTime(purchase.quoteExpiresAt)}. Si cambia la farmacia,
                el precio o la cobertura, deberás aceptar otra propuesta.
              </Typography>
            )}
            {purchase?.orders
              .filter((x) => x.status === 'PROPOSED')
              .map((x) => (
                <Box key={x.id}>
                  <Typography sx={{ fontWeight: 650 }}>{x.branchName}</Typography>
                  {x.branchAddress && (
                    <Typography variant="body2">
                      {purchase.mode === 'PICKUP' ? 'Lugar de retiro: ' : 'Sucursal: '}
                      {x.branchAddress}
                    </Typography>
                  )}
                  <Typography>
                    {['ROUTING', 'UNAVAILABLE'].includes(x.status)
                      ? 'Importe a confirmar'
                      : money(x.total, x.currency)}{' '}
                    · {label(purchase.mode)}
                  </Typography>
                  {(x.lines ?? []).map((y) => (
                    <Typography key={y.productId} variant="body2">
                      {y.name} · {y.presentation}: {y.quantity}{' '}
                      {y.quantity === 1 ? 'unidad' : 'unidades'} · Precio unitario{' '}
                      {money(y.unitPrice, x.currency)} · Cobertura total{' '}
                      {money(y.coverageAmount, x.currency)} · A pagar {money(y.total, x.currency)}
                    </Typography>
                  ))}
                </Box>
              ))}
            <Typography variant="h6">Total {money(purchase?.total, purchase?.currency)}</Typography>
            {quoteExpired && (
              <Alert severity="warning">
                La vigencia de esta propuesta terminó. Volvé al detalle y buscá una nueva propuesta.
              </Alert>
            )}
            <Typography variant="body2">
              Envío: sin costo. Uso de E-MEDICAPP: sin costo. La aceptación reserva el stock durante
              el plazo informado para completar y verificar todos los pagos.
            </Typography>
            {purchase?.messages?.quoteConsent && (
              <Typography>{purchase.messages.quoteConsent}</Typography>
            )}
            <FormControlLabel
              control={
                <Checkbox checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              }
              label="Acepto expresamente farmacias, presentaciones, cantidades, cobertura, importes y modalidad de esta propuesta"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setAccepting(false)}>
            Volver
          </Button>
          <Button
            variant="contained"
            disabled={busy || !consent || quoteExpired || consentVersion !== purchase?.version}
            onClick={() => void command('accept')}
          >
            Aceptar presupuesto
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={cancel}
        onClose={busy ? undefined : () => setCancel(false)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Solicitar cancelación</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            {notice && <Alert severity="warning">{notice}</Alert>}
            <Typography>
              Antes de la dispensación se libera el stock reservado y se solicita el reintegro
              completo de los cobros registrados, sin un tope de importe. La farmacia debe realizar
              y comprobar la devolución. Si algún pedido ya fue dispensado, se abre una incidencia
              para que la farmacia determine la resolución.
            </Typography>
            <TextField
              label="Motivo"
              required
              multiline
              minRows={2}
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setCancel(false)}>
            Volver
          </Button>
          <Button
            color="error"
            variant="contained"
            disabled={busy || !cancelReason.trim()}
            onClick={() => void command('cancel')}
          >
            Confirmar solicitud
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
