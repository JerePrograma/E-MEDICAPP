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
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import type { Product } from '../catalog/types';
import { DocumentInput } from '../components/DocumentInput';
import type { Order } from './types';

export function SubstitutionDialog({
  order,
  onClose,
  onDone,
}: {
  order: Order;
  onClose: () => void;
  onDone: () => void;
}) {
  const [lineId, setLineId] = useState(order.lines[0]?.lineId ?? '');
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState(String(order.lines[0]?.quantity ?? 1));
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [compatible, setCompatible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
  });
  const original = order.lines.find((x) => x.lineId === lineId);
  const alternative = products.data?.find((x) => x.id === productId);
  const requiresPrescription =
    !!original?.requiresPrescription || !!alternative?.requiresPrescription;
  async function submit() {
    if (
      !original ||
      !alternative ||
      !reason.trim() ||
      !evidence ||
      !/^\d+$/.test(quantity) ||
      Number(quantity) < 1 ||
      Number(quantity) > 100
    ) {
      setError('Elegí la presentación original y su alternativa, cantidad, motivo y evidencia.');
      return;
    }
    if (requiresPrescription && (!compatible || Number(quantity) !== original.quantity)) {
      setError(
        'Comprobá la compatibilidad documentada con la receta y conservá la cantidad autorizada.',
      );
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api(`/api/orders/${order.id}/substitutions`, {
        method: 'POST',
        idempotent: true,
        body: {
          expectedVersion: order.version,
          lineId,
          productId,
          quantity: Number(quantity),
          reason: reason.trim(),
          evidenceDocumentId: evidence,
          prescriptionCompatible: compatible,
        },
      });
      onDone();
    } catch (error) {
      setError(error instanceof Error ? error.message : 'No se pudo presentar la alternativa.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>Proponer sustitución</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5}>
          <Typography sx={{ fontWeight: 650 }}>{order.branchName}</Typography>
          <Alert severity="info">
            La propuesta pausa la revisión y libera el presupuesto anterior. El comprador decide si
            acepta la alternativa; después se revisarán stock, receta, cobertura e importe y deberá
            aceptar un nuevo presupuesto.
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}
          {products.error && <Alert severity="error">{products.error.message}</Alert>}
          <TextField
            select
            label="Presentación original"
            value={lineId}
            onChange={(e) => {
              setLineId(e.target.value);
              setQuantity(
                String(order.lines.find((x) => x.lineId === e.target.value)?.quantity ?? 1),
              );
              setProductId('');
              setEvidence('');
              setCompatible(false);
            }}
          >
            {order.lines
              .filter((x) => x.lineId)
              .map((x) => (
                <MenuItem key={x.lineId} value={x.lineId}>
                  {x.name} · {x.presentation} × {x.quantity}
                </MenuItem>
              ))}
          </TextField>
          <TextField
            select
            label="Presentación alternativa"
            value={productId}
            onChange={(e) => {
              setProductId(e.target.value);
              setEvidence('');
              setCompatible(false);
            }}
            helperText="Seleccioná una alternativa revisada por el profesional. La aplicación no determina equivalencia médica."
          >
            {products.data
              ?.filter((x) => x.active && x.id !== original?.productId)
              .map((x) => (
                <MenuItem
                  key={x.id}
                  value={x.id}
                  disabled={!original?.requiresPrescription && x.requiresPrescription}
                >
                  {x.name} · {x.presentation}
                </MenuItem>
              ))}
          </TextField>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 2 }}>
            <TextField
              label="Cantidad propuesta"
              type="number"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              slotProps={{
                htmlInput: { min: 1, max: 100, step: 1 },
                input: { readOnly: requiresPrescription },
              }}
              helperText={
                requiresPrescription
                  ? 'Se conserva la cantidad de la receta.'
                  : 'Cantidad que el comprador debe aceptar.'
              }
            />
          </Box>
          <TextField
            required
            multiline
            minRows={2}
            label="Motivo de la alternativa propuesta"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {requiresPrescription && (
            <FormControlLabel
              control={
                <Checkbox checked={compatible} onChange={(e) => setCompatible(e.target.checked)} />
              }
              label="Comprobé compatibilidad con la receta y adjunto evidencia médica"
            />
          )}
          <DocumentInput
            key={`${lineId}:${productId}`}
            label={
              requiresPrescription
                ? 'Evidencia médica de compatibilidad con la receta'
                : 'Evidencia de la propuesta de sustitución'
            }
            kind={requiresPrescription ? 'RECETA' : 'OTRO'}
            branchId={requiresPrescription ? undefined : order.branchId}
            value={evidence}
            onChange={setEvidence}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button disabled={busy} onClick={onClose}>
          Volver
        </Button>
        <Button variant="contained" disabled={busy || !productId} onClick={() => void submit()}>
          {busy ? 'Enviando…' : 'Presentar alternativa al comprador'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
