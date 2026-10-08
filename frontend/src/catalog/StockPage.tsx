import { useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Actor, api, canOperateBranch, dateTime, money } from '../api';
import { LotsPanel } from '../admin/LotsPanel';
import { Branch, Product, Stock } from './types';

export function StockPage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const operatedBranches = actor.branchIds.filter((id) =>
    canOperateBranch(actor, id, 'PHARMACY', 'PHARMACIST'),
  );
  const [branchId, setBranchId] = useState(operatedBranches[0] ?? '');
  const [productId, setProductId] = useState('');
  const [editVersion, setEditVersion] = useState(0);
  const [quantity, setQuantity] = useState('0');
  const [price, setPrice] = useState('0.00');
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const form = useRef<HTMLDivElement>(null);
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
  });
  const branches = useQuery({
    queryKey: ['branches'],
    queryFn: () => api<Branch[]>('/api/catalog/branches'),
  });
  const stock = useQuery({
    queryKey: ['stock', branchId],
    queryFn: () => api<Stock[]>(`/api/branches/${branchId}/stock`),
    enabled: !!branchId,
  });
  const current = stock.data?.find((x) => x.productId === productId);
  const selectProduct = (id: string) => {
    const row = stock.data?.find((item) => item.productId === id);
    setProductId(id);
    setQuantity(String(row?.quantity ?? 0));
    setPrice(row?.price ?? '0.00');
    setEditVersion(row?.version ?? 0);
    setReason('');
    setError('');
  };
  const searchTerm = search
    .trim()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
  const visibleStock = stock.data?.filter((row) => {
    const product = products.data?.find((item) => item.id === row.productId);
    return `${row.productName} ${product?.presentation ?? ''} ${product?.code ?? ''}`
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .includes(searchTerm);
  });
  const changed = !!productId && (current?.version ?? 0) !== editVersion;
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Stock y precios</Typography>
      <Typography color="text.secondary">
        Disponibilidad informada por tu farmacia. Confirmá la existencia física al revisar cada
        solicitud. Las reservas de compras aceptadas ya están descontadas del saldo disponible.
      </Typography>
      {operatedBranches.length === 0 && (
        <Alert severity="warning">
          Tu cuenta no tiene una sucursal asignada. Solicitá al administrador que registre el
          permiso correspondiente.
        </Alert>
      )}
      <TextField
        select
        label="Sucursal"
        value={branchId}
        onChange={(e) => {
          setBranchId(e.target.value);
          setProductId('');
          setReason('');
          setNotice('');
          setError('');
        }}
      >
        {branches.data
          ?.filter((x) => operatedBranches.includes(x.id))
          .map((x) => (
            <MenuItem key={x.id} value={x.id}>
              {x.name}
            </MenuItem>
          ))}
      </TextField>
      {stock.error && <Alert severity="error">{stock.error.message}</Alert>}
      {products.error && <Alert severity="error">{products.error.message}</Alert>}
      {branches.error && <Alert severity="error">{branches.error.message}</Alert>}
      {error && (
        <Alert severity="error" onClose={() => setError('')}>
          {error}
        </Alert>
      )}
      {notice && (
        <Alert severity="success" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      <Card ref={form}>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 2 }}>
            Actualizar una presentación
          </Typography>
          <Stack
            spacing={2}
            component="form"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!productId || !branchId || busy) return;
              if (changed) {
                setError('El stock cambió. Cargá los datos actuales antes de guardar.');
                return;
              }
              const total = Number(quantity);
              const normalizedPrice = price.trim().replace(',', '.');
              if (
                !/^\d+$/.test(quantity) ||
                !Number.isInteger(total) ||
                total > 2147483647 ||
                total < (current?.reservedQuantity ?? 0)
              ) {
                setError(
                  `La existencia debe ser una cantidad entera que incluya las ${current?.reservedQuantity ?? 0} unidades reservadas.`,
                );
                return;
              }
              if (
                !/^\d{1,16}(?:\.\d{1,2})?$/.test(normalizedPrice) ||
                !/[1-9]/.test(normalizedPrice)
              ) {
                setError(
                  'Ingresá un precio positivo con hasta dos decimales, sin separadores de miles.',
                );
                return;
              }
              if (!reason.trim()) {
                setError('Indicá qué comprobación o ajuste motiva la actualización.');
                return;
              }
              setBusy(true);
              setNotice('');
              setError('');
              try {
                const saved = await api<Stock>(`/api/branches/${branchId}/stock/${productId}`, {
                  method: 'PUT',
                  body: {
                    quantity: total,
                    price: normalizedPrice,
                    expectedVersion: editVersion,
                    reason: reason.trim(),
                  },
                });
                setNotice('Stock y precio actualizados.');
                setReason('');
                setEditVersion(saved.version);
                setQuantity(String(saved.quantity));
                setPrice(saved.price);
                cache.setQueryData<Stock[]>(['stock', branchId], (rows) => {
                  const previous = rows ?? [];
                  return previous.some((row) => row.productId === saved.productId)
                    ? previous.map((row) => (row.productId === saved.productId ? saved : row))
                    : [...previous, saved];
                });
                await cache.invalidateQueries({ queryKey: ['stock', branchId] });
                await cache.invalidateQueries({ queryKey: ['lots', branchId, productId] });
              } catch (error) {
                setError(error instanceof Error ? error.message : 'No se pudo actualizar.');
              } finally {
                setBusy(false);
              }
            }}
          >
            <TextField
              select
              required
              label="Producto"
              value={productId}
              onChange={(e) => selectProduct(e.target.value)}
            >
              {products.data?.map((x) => (
                <MenuItem key={x.id} value={x.id}>
                  {x.name} · {x.presentation}
                </MenuItem>
              ))}
            </TextField>
            {current && (
              <Alert severity="info">
                Existencia registrada: {current.quantity}. Reservas vigentes:{' '}
                {current.reservedQuantity}. Disponible para nuevas compras:{' '}
                {current.availableQuantity}. Moneda: {current.currency}. Si hay lotes registrados,
                ajustá sus cantidades desde «Lotes y conservación» y luego revisá el precio.
              </Alert>
            )}
            {changed && (
              <Alert
                severity="warning"
                action={
                  <Button onClick={() => selectProduct(productId)}>Cargar datos actuales</Button>
                }
              >
                El stock cambió desde que abriste esta presentación. Cargá los datos actuales antes
                de guardar un nuevo ajuste.
              </Alert>
            )}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                required
                label="Existencia total"
                type="number"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                slotProps={{
                  htmlInput: { min: current?.reservedQuantity ?? 0, max: 2147483647, step: 1 },
                }}
                helperText="Contá todas las unidades físicas, incluidas las reservadas."
              />
              <TextField
                required
                label="Precio por presentación"
                value={price}
                onChange={(e) => setPrice(e.target.value)}
                slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                helperText="Usá coma o punto decimal, sin separadores de miles."
              />
            </Stack>
            <TextField
              required
              label="Motivo y comprobación realizada"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
            <Button
              type="submit"
              variant="contained"
              disabled={busy || !branchId || !productId || stock.isPending || changed}
            >
              {busy ? 'Guardando…' : 'Guardar actualización'}
            </Button>
          </Stack>
        </CardContent>
      </Card>
      <TextField
        label="Buscar en el stock de esta sucursal"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="Nombre, presentación o código"
      />
      {stock.isPending && branchId && <Typography>Consultando stock de la sucursal…</Typography>}
      {stock.data?.length === 0 && (
        <Alert severity="info">
          La sucursal todavía no tiene existencias cargadas. Seleccioná una presentación y registrá
          su conteo físico y precio.
        </Alert>
      )}
      {stock.data && stock.data.length > 0 && visibleStock?.length === 0 && (
        <Alert severity="info">No hay presentaciones que coincidan con tu búsqueda.</Alert>
      )}
      <Stack spacing={1.5}>
        {visibleStock?.map((x) => (
          <Card key={x.productId}>
            <CardContent>
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ justifyContent: 'space-between' }}
              >
                <Typography sx={{ fontWeight: 650 }}>{x.productName}</Typography>
                <Typography>{money(x.price, x.currency)}</Typography>
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {products.data?.find((product) => product.id === x.productId)?.presentation}
              </Typography>
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
                <Chip size="small" label={`${x.availableQuantity} disponibles`} />
                <Chip size="small" label={`${x.reservedQuantity} reservadas`} />
                <Typography variant="caption" color="text.secondary">
                  Actualizado {dateTime(x.observedAt)} ·{' '}
                  {x.source === 'INTEGRATED' ? 'Sistema conectado' : 'Registro manual'}
                </Typography>
              </Stack>
              <Button
                onClick={() => {
                  selectProduct(x.productId);
                  form.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }}
                sx={{ mt: 1 }}
              >
                Actualizar stock o precio
              </Button>
            </CardContent>
          </Card>
        ))}
      </Stack>
      <LotsPanel
        key={branchId}
        branchId={branchId}
        branches={branches.data?.filter((x) => operatedBranches.includes(x.id)) ?? []}
        products={products.data ?? []}
      />
    </Stack>
  );
}
