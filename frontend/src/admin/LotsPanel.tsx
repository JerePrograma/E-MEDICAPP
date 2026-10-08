import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import type { Branch, Product } from '../catalog/types';

type Lot = {
  id: string;
  lotCode: string;
  expiresOn: string | null;
  quantity: number;
  reservedQuantity: number;
  status: string;
  version: number;
};
type Lots = { branchId: string; productId: string; stockVersion: number; lots: Lot[] };
const statusLabels: Record<string, string> = {
  AVAILABLE: 'Disponible',
  QUARANTINED: 'En cuarentena',
  EXPIRED: 'Vencido',
  DEPLETED: 'Agotado',
};

export function LotsPanel({
  branches,
  products,
  branchId: scopedBranchId,
}: {
  branches: Branch[];
  products: Product[];
  branchId?: string;
}) {
  const cache = useQueryClient();
  const [selectedBranch, setBranch] = useState('');
  const branchId = scopedBranchId ?? selectedBranch;
  const [productId, setProduct] = useState('');
  const [selected, setSelected] = useState<Lot | null>(null);
  const [selectedStockVersion, setSelectedStockVersion] = useState(0);
  const [code, setCode] = useState('');
  const [expiry, setExpiry] = useState('');
  const [quantity, setQuantity] = useState('0');
  const [status, setStatus] = useState('AVAILABLE');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const lots = useQuery({
    queryKey: ['lots', branchId, productId],
    queryFn: () => api<Lots>(`/api/branches/${branchId}/stock/${productId}/lots`),
    enabled: !!branchId && !!productId,
  });
  const reset = () => {
    setSelected(null);
    setCode('');
    setExpiry('');
    setQuantity('0');
    setStatus('AVAILABLE');
    setReason('');
    setError('');
  };
  const edit = (lot: Lot) => {
    setSelected(lot);
    setSelectedStockVersion(lots.data?.stockVersion ?? 0);
    setCode(lot.lotCode);
    setExpiry(lot.expiresOn ?? '');
    setQuantity(String(lot.quantity));
    setStatus(lot.status);
    setReason('');
    setError('');
  };
  return (
    <Stack spacing={2}>
      <Typography variant="h6">Lotes y conservación</Typography>
      <Typography color="text.secondary">
        El saldo disponible se calcula con los lotes utilizables. Los lotes reservados no pueden
        retirarse de disponibilidad hasta resolver sus pedidos.
      </Typography>
      {scopedBranchId === undefined && (
        <TextField
          select
          label="Sucursal"
          value={branchId}
          onChange={(e) => {
            setBranch(e.target.value);
            reset();
          }}
        >
          <MenuItem value="">Seleccionar</MenuItem>
          {branches.map((x) => (
            <MenuItem key={x.id} value={x.id}>
              {x.name}
            </MenuItem>
          ))}
        </TextField>
      )}
      <TextField
        select
        label="Presentación"
        value={productId}
        onChange={(e) => {
          setProduct(e.target.value);
          reset();
        }}
      >
        <MenuItem value="">Seleccionar</MenuItem>
        {products.map((x) => (
          <MenuItem key={x.id} value={x.id}>
            {x.name} · {x.presentation}
          </MenuItem>
        ))}
      </TextField>
      {lots.error && <Alert severity="info">{lots.error.message}</Alert>}
      {notice && (
        <Alert severity="success" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      {error && <Alert severity="error">{error}</Alert>}
      {lots.data && (
        <>
          {selected && selectedStockVersion !== lots.data.stockVersion && (
            <Alert
              severity="warning"
              action={
                <Button
                  onClick={() => {
                    const current = lots.data.lots.find((lot) => lot.id === selected.id);
                    if (current) edit(current);
                    else reset();
                  }}
                >
                  Cargar datos actuales
                </Button>
              }
            >
              El stock cambió desde que abriste este lote. Revisá los datos actuales antes de
              guardar el ajuste.
            </Alert>
          )}
          <Alert severity="info">
            Registrar el primer lote establece el saldo total según sus cantidades. Completá todos
            los lotes comprobados antes de aceptar nuevas compras.
          </Alert>
          <Card>
            <CardContent>
              <Stack
                component="form"
                spacing={2}
                onSubmit={async (event) => {
                  event.preventDefault();
                  if (busy || !lots.data) return;
                  const total = Number(quantity);
                  if (!code.trim() || !reason.trim()) {
                    setError('Completá el lote comprobado y el motivo de su registro o ajuste.');
                    return;
                  }
                  if (
                    !/^\d+$/.test(quantity) ||
                    !Number.isInteger(total) ||
                    total > 2147483647 ||
                    total < (selected?.reservedQuantity ?? 0)
                  ) {
                    setError(
                      `La existencia del lote debe ser entera e incluir sus ${selected?.reservedQuantity ?? 0} unidades reservadas.`,
                    );
                    return;
                  }
                  if (selected && selected.reservedQuantity > 0 && status !== 'AVAILABLE') {
                    setError(
                      'Este lote tiene pedidos reservados. Resolvelos antes de cambiar su disponibilidad.',
                    );
                    return;
                  }
                  if (
                    status === 'AVAILABLE' &&
                    (products.find((product) => product.id === productId)?.coldChain ||
                      products.find((product) => product.id === productId)?.restricted) &&
                    !expiry
                  ) {
                    setError(
                      'Las categorías especiales requieren un vencimiento comprobado para su trazabilidad.',
                    );
                    return;
                  }
                  setBusy(true);
                  setError('');
                  try {
                    await api(
                      `/api/branches/${branchId}/stock/${productId}/lots${selected ? `/${selected.id}` : ''}`,
                      {
                        method: selected ? 'PUT' : 'POST',
                        body: {
                          lotCode: code.trim(),
                          expiresOn: expiry || null,
                          quantity: total,
                          status,
                          expectedVersion: selected?.version ?? 0,
                          stockVersion: selected ? selectedStockVersion : lots.data.stockVersion,
                          reason: reason.trim(),
                        },
                      },
                    );
                    reset();
                    setNotice('Lote y saldo de stock actualizados.');
                    await cache.invalidateQueries({ queryKey: ['lots', branchId, productId] });
                    await cache.invalidateQueries({ queryKey: ['stock', branchId] });
                  } catch (failure) {
                    setError(
                      failure instanceof Error ? failure.message : 'No se pudo guardar el lote.',
                    );
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                <Typography sx={{ fontWeight: 650 }}>
                  {selected ? 'Modificar lote' : 'Agregar lote'}
                </Typography>
                <TextField
                  required
                  label="Código de lote comprobado"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
                <TextField
                  label="Vencimiento"
                  type="date"
                  value={expiry}
                  onChange={(e) => setExpiry(e.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
                <TextField
                  required
                  label="Existencia física del lote"
                  type="number"
                  value={quantity}
                  onChange={(e) => setQuantity(e.target.value)}
                  slotProps={{
                    htmlInput: { min: selected?.reservedQuantity ?? 0, max: 2147483647, step: 1 },
                  }}
                  helperText="Incluí las unidades separadas para pedidos reservados."
                />
                <TextField
                  select
                  label="Condición del lote"
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  {Object.entries(statusLabels).map(([id, name]) => (
                    <MenuItem
                      key={id}
                      value={id}
                      disabled={(selected?.reservedQuantity ?? 0) > 0 && id !== 'AVAILABLE'}
                    >
                      {name}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  required
                  label="Motivo y comprobación del ajuste"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                <Button
                  disabled={busy || (!!selected && selectedStockVersion !== lots.data.stockVersion)}
                  variant="contained"
                  type="submit"
                >
                  {busy ? 'Guardando…' : 'Guardar lote'}
                </Button>
                {selected && <Button onClick={reset}>Agregar otro lote</Button>}
              </Stack>
            </CardContent>
          </Card>
          {lots.data.lots.length === 0 && (
            <Alert severity="info">
              Esta presentación todavía no tiene lotes registrados en la sucursal.
            </Alert>
          )}
          {lots.data.lots.map((lot) => (
            <Card key={lot.id}>
              <CardContent>
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={2}
                  sx={{ justifyContent: 'space-between' }}
                >
                  <Stack spacing={1}>
                    <Typography sx={{ fontWeight: 650 }}>Lote {lot.lotCode}</Typography>
                    <Typography variant="body2">
                      {lot.quantity} unidades · {lot.reservedQuantity} reservadas ·{' '}
                      {statusLabels[lot.status] ?? lot.status}
                    </Typography>
                    <Typography variant="caption">
                      {lot.expiresOn
                        ? `Vence ${new Date(`${lot.expiresOn}T12:00:00`).toLocaleDateString('es-AR')}`
                        : 'Vencimiento no registrado'}
                    </Typography>
                  </Stack>
                  <Button onClick={() => edit(lot)}>Modificar lote</Button>
                </Stack>
              </CardContent>
            </Card>
          ))}
        </>
      )}
    </Stack>
  );
}
