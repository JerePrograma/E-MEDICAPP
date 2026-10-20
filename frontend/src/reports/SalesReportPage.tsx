import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Actor, api, money } from '../api';
import { EmptyState, MetricRow, PageHeader } from '../components/PageElements';
import {
  bucketLabel,
  businessDate,
  reportDateTime,
  reportPeriods,
  reportRange,
  validReportDate,
} from './presentation';
import type { SalesBranch, SalesPeriod, SalesReport, SalesTotals } from './types';

function AmountCells({ totals }: { totals: SalesTotals }) {
  return (
    <>
      <TableCell>{totals.currency}</TableCell>
      <TableCell align="right">{money(totals.gross, totals.currency)}</TableCell>
      <TableCell align="right">{money(totals.refunds, totals.currency)}</TableCell>
      <TableCell align="right" sx={{ fontWeight: 700 }}>
        {money(totals.net, totals.currency)}
      </TableCell>
      <TableCell align="right">{totals.paidOrders}</TableCell>
    </>
  );
}

function AmountHead({ label }: { label: string }) {
  return (
    <TableHead>
      <TableRow>
        <TableCell>{label}</TableCell>
        <TableCell>Moneda</TableCell>
        <TableCell align="right">Cobrado</TableCell>
        <TableCell align="right">Reintegrado</TableCell>
        <TableCell align="right">Neto</TableCell>
        <TableCell align="right">Pedidos con cobro</TableCell>
      </TableRow>
    </TableHead>
  );
}

export function SalesReportPage({ actor }: { actor: Actor }) {
  const [period, setPeriod] = useState<SalesPeriod>('MONTH');
  const [date, setDate] = useState(businessDate);
  const [branchId, setBranchId] = useState('');
  const [knownBranches, setKnownBranches] = useState<SalesBranch[]>([]);
  const [movementPage, setMovementPage] = useState(0);
  const validDate = validReportDate(date);
  const query = new URLSearchParams({ period, date });
  if (branchId) query.set('branchId', branchId);
  const report = useQuery({
    queryKey: ['sales-report', actor.id, period, date, branchId],
    queryFn: () => api<SalesReport>(`/api/reports/sales?${query}`),
    enabled: validDate,
  });
  const data = validDate ? report.data : undefined;
  useEffect(() => {
    if (report.data) setKnownBranches(report.data.branches);
  }, [report.data]);
  const currencies = data?.totals.map((total) => total.currency) ?? [];
  const emptyTotals = (currency: string): SalesTotals => ({
    currency,
    gross: '0.00',
    refunds: '0.00',
    net: '0.00',
    paidOrders: 0,
    paymentCount: 0,
    refundCount: 0,
  });

  return (
    <Stack spacing={3}>
      <PageHeader
        eyebrow="Actividad de tu farmacia"
        title="Reportes de ventas"
        description="Consultá los cobros y reintegros registrados en E-MEDICAPP por día, semana, mes o año y por sucursal."
        action={
          <Button
            variant="outlined"
            disabled={report.isFetching || !validDate}
            onClick={() => void report.refetch()}
          >
            Actualizar reporte
          </Button>
        }
      />
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            <TextField
              select
              label="Período"
              value={period}
              sx={{ minWidth: 160 }}
              onChange={(event) => {
                setPeriod(event.target.value as SalesPeriod);
                setMovementPage(0);
              }}
            >
              {reportPeriods.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Fecha de referencia"
              type="date"
              value={date}
              slotProps={{ inputLabel: { shrink: true } }}
              error={!validDate}
              helperText={
                !validDate
                  ? 'Ingresá una fecha válida.'
                  : period === 'WEEK'
                    ? 'La semana se cuenta de lunes a domingo.'
                    : 'Se incluye el período calendario que contiene esta fecha.'
              }
              onChange={(event) => {
                setDate(event.target.value);
                setMovementPage(0);
              }}
            />
            <TextField
              select
              label="Sucursal"
              value={branchId}
              sx={{ minWidth: 240, flexGrow: 1 }}
              onChange={(event) => {
                setBranchId(event.target.value);
                setMovementPage(0);
              }}
            >
              <MenuItem value="">Todas las sucursales a tu cargo</MenuItem>
              {(data?.branches ?? knownBranches).map((branch) => (
                <MenuItem key={branch.id} value={branch.id}>
                  {branch.chainName ? `${branch.chainName} · ` : ''}
                  {branch.name} · {branch.address}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </CardContent>
      </Card>
      {report.isPending && validDate && (
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <CircularProgress size={24} aria-label="Cargando reporte de ventas" />
          <Typography>Consultando los movimientos del período…</Typography>
        </Stack>
      )}
      {report.error && validDate && (
        <Alert
          severity="error"
          action={<Button onClick={() => void report.refetch()}>Reintentar</Button>}
        >
          {report.error.message}
        </Alert>
      )}
      {data && (
        <>
          <Stack spacing={1}>
            <Typography variant="h6" component="h2">
              {reportRange(data.from, data.toExclusive, data.timeZone)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Hora de Mendoza ({data.timeZone}). Cada movimiento se cuenta en la fecha de su
              verificación. Los cobros y reintegros pendientes quedan fuera del reporte.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              El neto es lo cobrado menos los reintegros verificados. No representa la ganancia de
              la farmacia. Cada moneda se informa por separado.
            </Typography>
          </Stack>
          {data.totals.map((total) => (
            <Stack key={total.currency} spacing={1.5}>
              <Typography variant="h6" component="h2">
                Resumen en {total.currency}
              </Typography>
              <MetricRow
                metrics={[
                  { label: 'Cobrado', value: money(total.gross, total.currency) },
                  { label: 'Reintegrado', value: money(total.refunds, total.currency) },
                  { label: 'Neto cobrado', value: money(total.net, total.currency) },
                  {
                    label: 'Pedidos con cobro',
                    value: total.paidOrders,
                    hint: `${total.paymentCount} cobros · ${total.refundCount} reintegros`,
                  },
                ]}
              />
            </Stack>
          ))}
          {data.movementCount === 0 ? (
            <EmptyState
              title="Sin movimientos verificados en este período"
              description="Probá otra fecha o sucursal. Los presupuestos y comprobantes pendientes de verificación no generan importes en este reporte."
            />
          ) : (
            <>
              <Card>
                <CardContent>
                  <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
                    {period === 'DAY'
                      ? 'Detalle por hora'
                      : period === 'YEAR'
                        ? 'Detalle por mes'
                        : 'Detalle por día'}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    Los pedidos se cuentan una vez dentro de cada fila. Si un pedido tiene cobros en
                    varias fechas, aparece en cada una; el resumen del período lo cuenta una sola
                    vez por moneda.
                  </Typography>
                  <TableContainer>
                    <Table size="small" aria-label="Ventas por fecha">
                      <AmountHead label={period === 'DAY' ? 'Hora' : 'Fecha'} />
                      <TableBody>
                        {data.buckets.flatMap((bucket) =>
                          currencies.map((currency) => (
                            <TableRow key={`${bucket.from}-${currency}`}>
                              <TableCell sx={{ whiteSpace: 'nowrap' }}>
                                {bucketLabel(bucket.from, period, data.timeZone)}
                              </TableCell>
                              <AmountCells
                                totals={
                                  bucket.totals.find((total) => total.currency === currency) ??
                                  emptyTotals(currency)
                                }
                              />
                            </TableRow>
                          )),
                        )}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </CardContent>
              </Card>
              <Card>
                <CardContent>
                  <Typography variant="h6" component="h2" sx={{ mb: 2 }}>
                    Detalle por sucursal
                  </Typography>
                  <TableContainer>
                    <Table size="small" aria-label="Ventas por sucursal">
                      <AmountHead label="Cadena y sucursal" />
                      <TableBody>
                        {data.byBranch.flatMap((branch) =>
                          branch.totals.map((total) => (
                            <TableRow key={`${branch.branchId}-${total.currency}`}>
                              <TableCell>
                                {branch.chainName && (
                                  <Typography variant="caption" color="text.secondary">
                                    {branch.chainName}
                                  </Typography>
                                )}
                                <Typography variant="body2">{branch.branchName}</Typography>
                              </TableCell>
                              <AmountCells totals={total} />
                            </TableRow>
                          )),
                        )}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </CardContent>
              </Card>
              <Card>
                <CardContent>
                  <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
                    Movimientos verificados
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    {data.movementCount} movimientos en el período. Las cancelaciones conservan sus
                    cobros; los reintegros se muestran como movimientos separados.
                  </Typography>
                  {data.movementsTruncated && (
                    <Alert severity="info" sx={{ mb: 2 }}>
                      Se muestran los últimos {data.movements.length} movimientos. Los totales
                      incluyen los {data.movementCount} movimientos del período. Elegí un período
                      menor o una sucursal para consultar más detalle.
                    </Alert>
                  )}
                  <TableContainer>
                    <Table size="small" aria-label="Movimientos verificados de ventas">
                      <TableHead>
                        <TableRow>
                          <TableCell>Verificado el</TableCell>
                          <TableCell>Movimiento</TableCell>
                          <TableCell>Sucursal</TableCell>
                          <TableCell>Pedido</TableCell>
                          <TableCell>Referencia</TableCell>
                          <TableCell align="right">Importe</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {data.movements
                          .slice(movementPage * 25, movementPage * 25 + 25)
                          .map((movement) => (
                            <TableRow key={`${movement.kind}-${movement.id}`}>
                              <TableCell sx={{ whiteSpace: 'nowrap' }}>
                                {reportDateTime(movement.verifiedAt, data.timeZone)}
                              </TableCell>
                              <TableCell>
                                <Chip
                                  size="small"
                                  variant="outlined"
                                  color={movement.kind === 'PAYMENT' ? 'success' : 'default'}
                                  label={movement.kind === 'PAYMENT' ? 'Cobro' : 'Reintegro'}
                                />
                              </TableCell>
                              <TableCell>
                                {movement.chainName && (
                                  <Typography variant="caption" color="text.secondary">
                                    {movement.chainName}
                                  </Typography>
                                )}
                                <Typography variant="body2">{movement.branchName}</Typography>
                              </TableCell>
                              <TableCell sx={{ fontSize: 12, overflowWrap: 'anywhere' }}>
                                {movement.orderId}
                              </TableCell>
                              <TableCell sx={{ overflowWrap: 'anywhere' }}>
                                {movement.reference || '—'}
                              </TableCell>
                              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                                {movement.kind === 'REFUND' ? '− ' : ''}
                                {money(movement.amount, movement.currency)}
                              </TableCell>
                            </TableRow>
                          ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                  <TablePagination
                    component="div"
                    count={data.movements.length}
                    rowsPerPage={25}
                    rowsPerPageOptions={[25]}
                    page={movementPage}
                    onPageChange={(_, page) => setMovementPage(page)}
                    labelDisplayedRows={({ from, to, count }) => `${from}–${to} de ${count}`}
                    getItemAriaLabel={(type) =>
                      type === 'previous' ? 'Página anterior' : 'Página siguiente'
                    }
                  />
                </CardContent>
              </Card>
            </>
          )}
        </>
      )}
    </Stack>
  );
}
