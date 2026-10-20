import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Branch, Product } from '../catalog/types';
import { EmptyState, PageHeader } from '../components/PageElements';
import { useCart } from './CartContext';

export function CartPage() {
  const cart = useCart();
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
  });
  const branches = useQuery({
    queryKey: ['branches'],
    queryFn: () => api<Branch[]>('/api/catalog/branches'),
    enabled: !!cart.preferredBranchId,
  });
  const preferred = branches.data?.find(
    (branch) => branch.id === cart.preferredBranchId && branch.pickupEnabled,
  );
  const missing = products.data
    ? Object.keys(cart.items).filter(
        (id) => !products.data.some((product) => product.id === id && product.active),
      )
    : [];
  const unavailableBranch = !!cart.preferredBranchId && !!branches.data && !preferred;
  const ready =
    !!products.data &&
    !products.error &&
    !missing.length &&
    !unavailableBranch &&
    (!cart.preferredBranchId || (!!preferred && !branches.error));
  return (
    <Stack spacing={3}>
      <PageHeader
        eyebrow="Tu selección, a tu ritmo"
        title="Carrito de compras"
        description="Revisá los productos y las cantidades. Al continuar, completás la recepción y la documentación para que la farmacia prepare tu presupuesto."
        action={
          <Button component={Link} to="/app/catalog" variant="outlined">
            Seguir comprando
          </Button>
        }
      />
      {cart.notice && <Alert severity="warning">{cart.notice}</Alert>}
      {cart.storageWarning && (
        <Alert severity="warning">
          El navegador no permite guardar este carrito. Tu selección se conserva mientras mantengas
          esta pantalla abierta.
        </Alert>
      )}
      {cart.lineCount === 0 ? (
        <EmptyState
          title="Tu carrito está vacío"
          description="Explorá el catálogo y agregá los productos que necesitás. Podés consultar las sucursales y su ubicación antes de elegir."
          action={
            <Button component={Link} to="/app/catalog" variant="contained">
              Explorar catálogo
            </Button>
          }
        />
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'minmax(0,1fr) 320px' },
            gap: 3,
            alignItems: 'start',
          }}
        >
          <Stack spacing={2}>
            {products.isPending && <CircularProgress aria-label="Cargando productos del carrito" />}
            {products.error && (
              <Alert severity="error">
                No se pudo actualizar el catálogo. Tu carrito se conserva.{' '}
                <Button onClick={() => void products.refetch()}>Volver a intentar</Button>
              </Alert>
            )}
            {Object.entries(cart.items).map(([id, quantity]) => {
              const product = products.data?.find(
                (candidate) => candidate.id === id && candidate.active,
              );
              const name = product?.name ?? 'Producto fuera del catálogo';
              return (
                <Card key={id}>
                  <CardContent>
                    <Stack spacing={2}>
                      <Box>
                        <Typography component="h2" variant="h6" sx={{ overflowWrap: 'anywhere' }}>
                          {name}
                        </Typography>
                        <Typography color="text.secondary">
                          {product?.presentation ?? 'Quitá este producto antes de continuar.'}
                        </Typography>
                      </Box>
                      {product?.requiresPrescription && (
                        <Chip
                          label="Con receta"
                          size="small"
                          color="warning"
                          variant="outlined"
                          sx={{ alignSelf: 'flex-start' }}
                        />
                      )}
                      <Stack
                        direction="row"
                        spacing={1}
                        useFlexGap
                        sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                      >
                        <Button
                          variant="outlined"
                          aria-label={`Reducir cantidad de ${name}`}
                          disabled={quantity <= 1}
                          onClick={() => cart.updateQuantity(id, quantity - 1)}
                        >
                          −
                        </Button>
                        <Typography
                          aria-label={`Cantidad de ${name}`}
                          sx={{ minWidth: 32, textAlign: 'center', fontWeight: 700 }}
                        >
                          {quantity}
                        </Typography>
                        <Button
                          variant="outlined"
                          aria-label={`Aumentar cantidad de ${name}`}
                          disabled={quantity >= 100}
                          onClick={() => cart.updateQuantity(id, quantity + 1)}
                        >
                          +
                        </Button>
                        <Button aria-label={`Quitar ${name}`} onClick={() => cart.removeItem(id)}>
                          Quitar
                        </Button>
                      </Stack>
                    </Stack>
                  </CardContent>
                </Card>
              );
            })}
            {!!missing.length && (
              <Alert severity="warning">
                Hay productos que dejaron de estar publicados. Quitalos del carrito para continuar
                con los disponibles.
              </Alert>
            )}
          </Stack>
          <Card sx={{ position: { lg: 'sticky' }, top: 104 }}>
            <CardContent>
              <Stack spacing={2}>
                <Typography component="h2" variant="h6">
                  Resumen de tu selección
                </Typography>
                <Typography>
                  {cart.lineCount} productos · {cart.totalUnits} unidades
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>
                  Importe a confirmar por la farmacia
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  La disponibilidad y los precios se confirman en el presupuesto. Agregar al carrito
                  no reserva existencias ni genera un cobro.
                </Typography>
                {cart.preferredBranchId && (
                  <Box sx={{ p: 2, bgcolor: 'primary.light', borderRadius: 2 }}>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>
                      Preferencia de retiro
                    </Typography>
                    {preferred ? (
                      <>
                        <Typography variant="body2">{preferred.name}</Typography>
                        <Typography variant="body2">{preferred.address}</Typography>
                      </>
                    ) : (
                      <Typography variant="body2">
                        {branches.isPending
                          ? 'Consultando la sucursal…'
                          : 'La sucursal seleccionada ya no está disponible para retiro.'}
                      </Typography>
                    )}
                    <Typography variant="caption">
                      La farmacia y el lugar final de retiro se confirman en la propuesta que
                      aceptás.
                    </Typography>
                    <Button size="small" onClick={() => cart.setPreferredBranch('')}>
                      Quitar preferencia
                    </Button>
                  </Box>
                )}
                {branches.error && (
                  <Alert severity="warning">
                    No se pudo comprobar la sucursal.{' '}
                    <Button onClick={() => void branches.refetch()}>Reintentar</Button>
                  </Alert>
                )}
                <Button component={Link} to="/app/checkout" variant="contained" disabled={!ready}>
                  Continuar con la compra
                </Button>
                <Button color="secondary" onClick={cart.clearCart}>
                  Vaciar carrito
                </Button>
              </Stack>
            </CardContent>
          </Card>
        </Box>
      )}
    </Stack>
  );
}
