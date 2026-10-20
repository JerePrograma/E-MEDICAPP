import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import { LocationPicker } from '../components/LocationPicker';
import type { Promotion } from './types';
import { promotionDate } from './types';

export function PromotionCards({ promotions }: { promotions: Promotion[] }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = promotions.find((promotion) => promotion.id === selectedId);
  useEffect(() => {
    if (selectedId && !selected) setSelectedId(null);
  }, [selectedId, selected]);
  const [branchId, setBranchId] = useState('');
  const selectedBranch = selected?.branches.find((branch) => branch.id === branchId);
  const shownBranches = selectedBranch ? [selectedBranch] : (selected?.branches ?? []);
  return (
    <>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: 'repeat(2,minmax(0,1fr))' },
          gap: 2,
        }}
      >
        {promotions.map((promotion) => (
          <Card key={promotion.id}>
            <CardContent>
              <Stack spacing={1.5}>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <Chip size="small" label={promotion.chainName} variant="outlined" />
                  {promotion.featured && <Chip size="small" label="Destacada" color="primary" />}
                </Stack>
                <Typography component="h3" variant="h6" sx={{ overflowWrap: 'anywhere' }}>
                  {promotion.title}
                </Typography>
                <Typography
                  sx={{
                    whiteSpace: 'pre-wrap',
                    overflowWrap: 'anywhere',
                    display: '-webkit-box',
                    WebkitLineClamp: 4,
                    WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                  }}
                >
                  {promotion.description}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Válida hasta {promotionDate(promotion.endsAt)} · hora de Mendoza
                </Typography>
                <Box>
                  <Typography variant="body2" sx={{ fontWeight: 650 }}>
                    Disponible en estas sucursales:
                  </Typography>
                  {promotion.branches.map((branch) => (
                    <Typography key={branch.id} variant="body2">
                      {branch.name} · {branch.address}
                    </Typography>
                  ))}
                </Box>
                <Button
                  variant="outlined"
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={() => {
                    setSelectedId(promotion.id);
                    setBranchId('');
                  }}
                >
                  Ver promoción y mapa
                </Button>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Box>
      <Dialog open={!!selected} onClose={() => setSelectedId(null)} fullWidth maxWidth="md">
        {selected && (
          <>
            <DialogTitle>{selected.title}</DialogTitle>
            <DialogContent>
              <Stack spacing={2} sx={{ pt: 1 }}>
                <Typography variant="h6">Cadena / farmacia: {selected.chainName}</Typography>
                <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {selected.description}
                </Typography>
                <Typography variant="body2">
                  Desde {promotionDate(selected.startsAt)} hasta {promotionDate(selected.endsAt)} ·
                  hora de Mendoza
                </Typography>
                <Alert severity="info">
                  Consultá las condiciones de la promoción con la sucursal. Para comprar por la app,
                  la farmacia confirma el importe en el presupuesto antes de que pagues.
                </Alert>
                <Typography component="h3" variant="h6">
                  Sucursales participantes
                </Typography>
                {selected.branches.map((branch) => (
                  <Card key={branch.id} variant="outlined">
                    <CardContent>
                      <Typography sx={{ fontWeight: 650 }}>
                        {selected.chainName} · {branch.name}
                      </Typography>
                      <Typography>{branch.address}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {branch.pickupEnabled
                          ? 'Retiro en sucursal disponible'
                          : 'Esta sucursal no tiene retiro habilitado en la app'}
                      </Typography>
                      <Button onClick={() => setBranchId(branch.id)}>
                        Ubicar {branch.name} en el mapa
                      </Button>
                    </CardContent>
                  </Card>
                ))}
                {selectedBranch && (
                  <Button onClick={() => setBranchId('')}>
                    Mostrar todas las sucursales participantes
                  </Button>
                )}
                <LocationPicker
                  key={`${selected.id}-${selected.version}-${branchId}-${shownBranches.map((branch) => `${branch.id}:${branch.version}`).join(',')}`}
                  value={null}
                  onChange={() => {}}
                  delivery={false}
                  readOnly
                  branches={shownBranches}
                />
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setSelectedId(null)}>Cerrar</Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </>
  );
}
