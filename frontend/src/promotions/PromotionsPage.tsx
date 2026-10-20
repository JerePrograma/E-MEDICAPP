import { Alert, Button, CircularProgress, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, PageHeader } from '../components/PageElements';
import { PromotionCards } from './PromotionCards';
import type { Promotion } from './types';

export function PromotionsPage({ compact = false }: { compact?: boolean }) {
  const query = useQuery({
    queryKey: ['promotions'],
    queryFn: () => api<Promotion[]>('/api/catalog/promotions'),
    refetchInterval: 60_000,
  });
  const current = (query.data ?? []).filter(
    (p) => p.active && Date.parse(p.startsAt) <= Date.now() && Date.parse(p.endsAt) > Date.now(),
  );
  const promotions = compact
    ? current.filter((promotion) => promotion.featured).slice(0, 4)
    : current;
  if (compact && !query.isPending && !query.error && !promotions.length) return null;
  return (
    <Stack spacing={2}>
      {compact ? (
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography component="h2" variant="h5">
            Promociones destacadas
          </Typography>
          <Button component={Link} to="/app/promotions">
            Ver todas
          </Button>
        </Stack>
      ) : (
        <PageHeader
          eyebrow="Beneficios de las farmacias"
          title="Promociones"
          description="Conocé las ofertas vigentes y las sucursales exactas donde están disponibles. Abrí una promoción para consultar su dirección y ubicación."
        />
      )}
      {query.isPending && <CircularProgress aria-label="Cargando promociones" />}
      {query.error && (
        <Alert severity="warning">
          No se pudieron actualizar las promociones.{' '}
          <Button onClick={() => void query.refetch()}>Volver a intentar</Button>
        </Alert>
      )}
      {!query.isPending && !query.error && !promotions.length && (
        <EmptyState
          title="Todavía no hay promociones vigentes"
          description="Las farmacias publicarán aquí sus promociones y las sucursales participantes."
        />
      )}
      <PromotionCards promotions={promotions} />
    </Stack>
  );
}
