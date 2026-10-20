import { useState } from 'react';
import { Alert, Button, CircularProgress, Stack } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { Actor, api } from '../api';
import type { Branch, Product } from '../catalog/types';
import { EmptyState } from '../components/PageElements';
import { CreatePurchase } from '../purchases/CreatePurchase';
import { useCart } from './CartContext';

export function CartCheckoutPage({ actor }: { actor: Actor }) {
  const cart = useCart();
  const navigate = useNavigate();
  const [snapshot] = useState(() => ({
    items: { ...cart.items },
    preferredBranchId: cart.preferredBranchId,
  }));
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
    staleTime: 0,
  });
  const branches = useQuery({
    queryKey: ['branches'],
    queryFn: () => api<Branch[]>('/api/catalog/branches'),
    enabled: !!snapshot.preferredBranchId,
    staleTime: 0,
  });
  if (!Object.keys(snapshot.items).length)
    return (
      <EmptyState
        title="Tu carrito está vacío"
        description="Elegí los productos que querés solicitar antes de continuar."
        action={
          <Button component={Link} to="/app/catalog">
            Explorar catálogo
          </Button>
        }
      />
    );
  if (products.isPending || (snapshot.preferredBranchId && branches.isPending))
    return <CircularProgress aria-label="Revisando tu carrito" />;
  const missing =
    products.data &&
    Object.keys(snapshot.items).some(
      (id) => !products.data.some((product) => product.id === id && product.active),
    );
  const unavailableBranch =
    snapshot.preferredBranchId &&
    branches.data &&
    !branches.data.some(
      (branch) => branch.id === snapshot.preferredBranchId && branch.pickupEnabled,
    );
  if (
    !products.data ||
    products.error ||
    (snapshot.preferredBranchId && branches.error) ||
    missing ||
    unavailableBranch
  )
    return (
      <Stack spacing={2}>
        <Alert severity="warning">
          {missing
            ? 'Hay productos que dejaron de estar publicados. Revisá el carrito antes de continuar.'
            : unavailableBranch
              ? 'La sucursal preferida ya no está disponible para retiro. Revisá tu preferencia.'
              : 'No se pudo comprobar el carrito. Tu selección se conserva.'}
        </Alert>
        <Button component={Link} to="/app/cart">
          Volver al carrito
        </Button>
        <Button
          onClick={() => {
            void products.refetch();
            if (snapshot.preferredBranchId) void branches.refetch();
          }}
        >
          Volver a intentar
        </Button>
      </Stack>
    );
  return (
    <Stack spacing={2}>
      <Button component={Link} to="/app/cart" sx={{ alignSelf: 'flex-start' }}>
        ← Volver al carrito
      </Button>
      <CreatePurchase
        actor={actor}
        initialItems={snapshot.items}
        initialPreferredBranchId={snapshot.preferredBranchId}
        initialMode={snapshot.preferredBranchId ? 'PICKUP' : 'DELIVERY'}
        onCreated={(id, submittedItems) => {
          cart.removeSubmitted(submittedItems);
          navigate('/app', { replace: true, state: { purchaseId: id } });
        }}
      />
    </Stack>
  );
}
