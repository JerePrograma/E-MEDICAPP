import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  InputAdornment,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Actor, api, money } from '../api';
import { useCart } from '../cart/CartContext';
import { EmptyState } from '../components/PageElements';
import { LocationPicker } from '../components/LocationPicker';
import { PromotionsPage } from '../promotions/PromotionsPage';
import type { Product } from './types';
import type { ProductAvailability } from './availability';
import {
  catalogCategories,
  catalogProducts,
  categoryDetails,
  featuredCategories,
  type CatalogCategory,
  type CatalogSelection,
} from './categories';

function CategoryIllustration({ category }: { category: CatalogCategory }) {
  return (
    <svg width="112" height="112" viewBox="0 0 112 112" fill="none" aria-hidden="true">
      <circle cx="56" cy="56" r="49" fill="currentColor" opacity="0.07" />
      <circle cx="93" cy="24" r="5" fill="currentColor" opacity="0.16" />
      <path d="M20 84h10M25 79v10" stroke="currentColor" strokeWidth="2" opacity="0.35" />
      {category === 'PRESCRIPTION' || category === 'NON_PRESCRIPTION' ? (
        <g transform="rotate(-36 56 56)">
          <rect x="39" y="23" width="34" height="66" rx="17" fill="white" />
          <path d="M39 56h34v16a17 17 0 0 1-34 0Z" fill="currentColor" opacity="0.75" />
          <rect
            x="39"
            y="23"
            width="34"
            height="66"
            rx="17"
            stroke="currentColor"
            strokeWidth="3"
          />
          <path d="M39 56h34" stroke="currentColor" strokeWidth="3" />
          <path
            d="M48 36v9"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
            opacity="0.4"
          />
        </g>
      ) : category === 'SKIN_CARE' || category === 'BEAUTY' ? (
        <>
          <path d="M35 25h42l-4 53H39Z" fill="white" stroke="currentColor" strokeWidth="3" />
          <rect x="39" y="78" width="34" height="11" rx="3" fill="currentColor" opacity="0.75" />
          <path d="M36 34h40" stroke="currentColor" strokeWidth="3" opacity="0.25" />
          {category === 'SKIN_CARE' ? (
            <path
              d="M56 43c-5 7-11 13-11 18a11 11 0 0 0 22 0c0-5-6-11-11-18Z"
              fill="currentColor"
              opacity="0.55"
            />
          ) : (
            <path
              d="m56 43 4 10 11 4-11 4-4 10-4-10-11-4 11-4Z"
              fill="currentColor"
              opacity="0.55"
            />
          )}
        </>
      ) : category === 'VITAMINS_SUPPLEMENTS' ? (
        <>
          <rect x="40" y="21" width="32" height="14" rx="4" fill="currentColor" opacity="0.7" />
          <rect
            x="33"
            y="35"
            width="46"
            height="54"
            rx="13"
            fill="white"
            stroke="currentColor"
            strokeWidth="3"
          />
          <path d="M34 50h44v22H34Z" fill="currentColor" opacity="0.12" />
          <path
            d="M56 52v18M47 61h18"
            stroke="currentColor"
            strokeWidth="4"
            strokeLinecap="round"
          />
        </>
      ) : (
        <>
          <path
            d="M56 84S27 67 27 48a17 17 0 0 1 29-12 17 17 0 0 1 29 12c0 19-29 36-29 36Z"
            fill="white"
            stroke="currentColor"
            strokeWidth="3"
          />
          <path
            d="M56 47v20M46 57h20"
            stroke="currentColor"
            strokeWidth="4"
            strokeLinecap="round"
            opacity="0.6"
          />
        </>
      )}
    </svg>
  );
}

function PrescriptionBadge({ product }: { product: Product }) {
  return (
    <Chip
      size="small"
      variant="outlined"
      color={product.requiresPrescription ? 'default' : 'success'}
      label={product.requiresPrescription ? 'Requiere receta' : 'Sin requisito de receta'}
      sx={{ alignSelf: 'flex-start', fontSize: 11, bgcolor: 'background.paper' }}
    />
  );
}

function CategoryTile({
  item,
  selected,
  onSelect,
}: {
  item: (typeof featuredCategories)[number];
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <ButtonBase
      onClick={onSelect}
      aria-label={item.value === 'PROMOTIONS' ? 'PROMOCIONES' : item.label}
      aria-pressed={selected}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'stretch',
        width: '100%',
        borderRadius: 2.5,
        '&.Mui-focusVisible': {
          outline: '3px solid',
          outlineColor: 'primary.main',
          outlineOffset: 4,
        },
        '&:hover .catalog-category-image': { boxShadow: '0 4px 15px #253d3714' },
      }}
    >
      <Box
        className="catalog-category-image"
        sx={{
          position: 'relative',
          overflow: 'hidden',
          display: 'grid',
          placeItems: 'center',
          width: '100%',
          aspectRatio: { xs: '1.4', sm: '1.75' },
          bgcolor: item.value === 'PROMOTIONS' ? '#ffef63' : '#f1f2f3',
          borderRadius: 2.5,
          border: '2px solid',
          borderColor: selected ? 'primary.main' : 'transparent',
          transition: 'box-shadow .2s, border-color .2s',
        }}
      >
        {item.image ? (
          <Box
            component="img"
            src={`${import.meta.env.BASE_URL}catalog/category-${item.image}.webp`}
            alt=""
            loading="eager"
            sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
          />
        ) : (
          <svg
            width="170"
            height="120"
            viewBox="0 0 170 120"
            aria-hidden="true"
            style={{ maxWidth: '85%', height: 'auto' }}
          >
            <g transform="translate(25 18) rotate(-12 55 42)">
              <path d="M12 10h85l22 32-22 32H12Z" fill="#b08be0" />
              <circle cx="101" cy="42" r="5" fill="#ffef63" />
              <text
                x="25"
                y="56"
                fill="#503080"
                fontFamily="Arial,sans-serif"
                fontSize="37"
                fontWeight="800"
              >
                2×1
              </text>
            </g>
            <g transform="translate(92 60) rotate(13)">
              <path d="M0 0h42l13 23-13 23H0Z" fill="#683baa" />
              <circle cx="43" cy="23" r="3" fill="#ffef63" />
              <text
                x="9"
                y="33"
                fill="white"
                fontFamily="Arial,sans-serif"
                fontSize="29"
                fontWeight="700"
              >
                %
              </text>
            </g>
          </svg>
        )}
      </Box>
      <Typography
        component="span"
        sx={{
          display: 'block',
          textAlign: 'center',
          px: 0.5,
          pt: { xs: 1, sm: 1.4 },
          minHeight: { xs: 52, sm: 48 },
          fontSize: { xs: 12, sm: 15 },
          lineHeight: 1.4,
          fontWeight: selected ? 750 : 600,
          color: selected ? 'primary.main' : 'text.primary',
        }}
      >
        {item.label}
      </Typography>
    </ButtonBase>
  );
}

export function MarketplacePage({ actor }: { actor: Actor }) {
  const cart = useCart();
  const [category, setCategory] = useState<CatalogSelection>('ALL');
  const [expandedCategories, setExpandedCategories] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [focusedBranch, setFocusedBranch] = useState('');
  const [notice, setNotice] = useState('');
  const [cartError, setCartError] = useState('');
  const catalog = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
  });
  const availability = useQuery({
    queryKey: ['product-availability', actor.id, selectedId],
    queryFn: () => api<ProductAvailability>(`/api/catalog/products/${selectedId}/availability`),
    enabled: !!selectedId,
    refetchInterval: selectedId ? 30_000 : false,
  });
  const visibleProducts = catalogProducts(catalog.data ?? [], category, search);
  const selected = catalog.data?.find((product) => product.id === selectedId && product.active);
  const offers = availability.data?.offers ?? [];
  const activeBranchId = offers.some((offer) => offer.branch.id === focusedBranch)
    ? focusedBranch
    : '';
  useEffect(() => {
    if (availability.data && focusedBranch && !activeBranchId) setFocusedBranch('');
  }, [availability.data, focusedBranch, activeBranchId]);
  const shownBranches = useMemo(
    () =>
      offers
        .filter((offer) => !activeBranchId || offer.branch.id === activeBranchId)
        .map((offer) => offer.branch),
    [offers, activeBranchId],
  );
  const quantityNumber = Number(quantity);
  const validQuantity = /^\d+$/.test(quantity) && quantityNumber >= 1 && quantityNumber <= 100;
  const currentCategory = catalogCategories.find((item) => item.value === category);
  const medicineSelected = ['MEDICINES', 'PRESCRIPTION', 'NON_PRESCRIPTION'].includes(category);

  function add(product: Product, amount = 1, preferredBranchId?: string) {
    if (!cart.addItem(product.id, amount)) {
      setCartError(
        'No se pudo agregar. El carrito admite 30 productos y hasta 100 unidades de cada uno.',
      );
      return false;
    }
    if (preferredBranchId) cart.setPreferredBranch(preferredBranchId);
    setCartError('');
    setNotice(
      `${amount === 1 ? 'Agregaste' : `Agregaste ${amount} unidades de`} ${product.name} al carrito.${
        preferredBranchId ? ' Seleccionaste retiro en esa sucursal.' : ''
      }`,
    );
    return true;
  }

  function openProduct(product: Product) {
    setSelectedId(product.id);
    setFocusedBranch('');
    setQuantity('1');
    setCartError('');
    setNotice('');
  }

  return (
    <Stack spacing={3}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: 'center' }}
      >
        <Box>
          <Typography component="h1" variant="h4" sx={{ fontWeight: 750 }}>
            Catálogo
          </Typography>
          <Typography color="text.secondary" sx={{ mt: 0.7, display: { xs: 'none', sm: 'block' } }}>
            Encontrá tus productos y elegí dónde retirarlos.
          </Typography>
        </Box>
        <Button
          component={Link}
          to="/app/cart"
          variant="contained"
          sx={{ flexShrink: 0, gap: 1, borderRadius: 10, px: { xs: 1.8, sm: 2.5 }, py: 1.3 }}
        >
          Mi carrito
          <Chip
            size="small"
            label={cart.totalUnits}
            sx={{ bgcolor: '#ffffff24', color: 'inherit' }}
          />
        </Button>
      </Stack>
      <Box>
        <TextField
          label="Buscar productos"
          placeholder="Nombre, presentación o código"
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
            if (category === 'PROMOTIONS') setCategory('ALL');
          }}
          fullWidth
          sx={{ '& .MuiOutlinedInput-root': { borderRadius: 10, bgcolor: '#f5f6f7' } }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
                    <path
                      d="m16 16 4.5 4.5"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                    />
                  </svg>
                </InputAdornment>
              ),
            },
          }}
        />
      </Box>
      <Box
        component="section"
        sx={{ bgcolor: 'background.paper', borderRadius: 3, p: { xs: 2, sm: 3 } }}
      >
        <Stack
          direction="row"
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: 'baseline', mb: 2.5 }}
        >
          <Typography
            component="h2"
            variant="h5"
            sx={{ fontSize: { xs: 20, sm: 24 }, fontWeight: 700 }}
          >
            Categorías destacadas
          </Typography>
          <Button
            aria-label="Todos"
            onClick={() => {
              setCategory('ALL');
              setSearch('');
            }}
            sx={{ fontSize: 12, flexShrink: 0 }}
          >
            Ver todos
          </Button>
        </Stack>
        <Box
          id="catalog-category-grid"
          role="region"
          aria-label="Categorías del catálogo"
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(3,minmax(0,1fr))',
            gap: { xs: 1, sm: 2.5 },
          }}
        >
          {featuredCategories.slice(0, expandedCategories ? 12 : 6).map((item) => (
            <CategoryTile
              key={item.value}
              item={item}
              selected={item.value === 'MEDICINES' ? medicineSelected : category === item.value}
              onSelect={() => {
                setCategory(item.value);
                setSearch('');
              }}
            />
          ))}
        </Box>
        <Box sx={{ textAlign: 'center', mt: 1 }}>
          <Button
            aria-expanded={expandedCategories}
            aria-controls="catalog-category-grid"
            onClick={() => setExpandedCategories((previous) => !previous)}
            sx={{ gap: 1.2, fontWeight: 650 }}
          >
            {expandedCategories ? 'Menos categorías' : 'Más categorías'}
            <svg
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              aria-hidden="true"
              style={{ transform: expandedCategories ? 'rotate(180deg)' : undefined }}
            >
              <path
                d="m4 6 4 4 4-4"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </Button>
        </Box>
      </Box>
      {medicineSelected && (
        <Stack
          direction="row"
          spacing={1}
          useFlexGap
          sx={{ flexWrap: 'wrap' }}
          aria-label="Filtros de medicamentos"
        >
          <Chip
            component="button"
            type="button"
            clickable
            aria-label="Todos los medicamentos"
            label="Todos los medicamentos"
            color={category === 'MEDICINES' ? 'primary' : 'default'}
            onClick={() => setCategory('MEDICINES')}
          />
          <Chip
            component="button"
            type="button"
            clickable
            aria-label="Medicamentos con receta"
            label="Con receta"
            color={category === 'PRESCRIPTION' ? 'primary' : 'default'}
            onClick={() => setCategory('PRESCRIPTION')}
          />
          <Chip
            component="button"
            type="button"
            clickable
            aria-label="Medicamentos sin receta"
            label="Sin receta"
            color={category === 'NON_PRESCRIPTION' ? 'primary' : 'default'}
            onClick={() => setCategory('NON_PRESCRIPTION')}
          />
        </Stack>
      )}
      {notice && (
        <Alert
          severity="success"
          onClose={() => setNotice('')}
          action={
            <Button component={Link} to="/app/cart">
              Ver carrito
            </Button>
          }
        >
          {notice}
        </Alert>
      )}
      {cartError && !selected && (
        <Alert severity="warning" onClose={() => setCartError('')}>
          {cartError}
        </Alert>
      )}
      {category === 'ALL' && !search.trim() && <PromotionsPage compact />}
      {category === 'PROMOTIONS' ? (
        <PromotionsPage />
      ) : (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'baseline', justifyContent: 'space-between', gap: 2 }}
          >
            <Typography component="h2" variant="h5">
              {category === 'MEDICINES'
                ? 'Medicamentos'
                : (currentCategory?.label ?? 'Todos los productos')}
            </Typography>
            {catalog.data && (
              <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0 }}>
                {visibleProducts.length} {visibleProducts.length === 1 ? 'producto' : 'productos'}
              </Typography>
            )}
          </Stack>
          {catalog.error && (
            <Alert
              severity="error"
              action={<Button onClick={() => void catalog.refetch()}>Reintentar</Button>}
            >
              {catalog.error.message}
            </Alert>
          )}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2,minmax(0,1fr))',
                lg: 'repeat(3,minmax(0,1fr))',
              },
              gap: 2.5,
            }}
          >
            {catalog.isPending &&
              [0, 1, 2, 3].map((index) => (
                <Card key={index}>
                  <Skeleton variant="rectangular" height={170} />
                  <CardContent>
                    <Skeleton />
                    <Skeleton width="65%" />
                    <Skeleton height={50} />
                  </CardContent>
                </Card>
              ))}
            {visibleProducts.map((product) => {
              const details = categoryDetails(product);
              return (
                <Card
                  key={product.id}
                  sx={{
                    display: 'flex',
                    flexDirection: 'column',
                    transition: 'box-shadow .2s',
                    '&:hover': { boxShadow: '0 10px 28px #153a3210' },
                  }}
                >
                  <Box
                    sx={{
                      bgcolor: details.tint,
                      color: details.color,
                      minHeight: 155,
                      position: 'relative',
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    <Typography
                      variant="overline"
                      sx={{
                        position: 'absolute',
                        top: 14,
                        left: 18,
                        letterSpacing: '.08em',
                        fontSize: 10,
                      }}
                    >
                      {details.label}
                    </Typography>
                    <Box sx={{ mt: 2 }}>
                      <CategoryIllustration category={details.value} />
                    </Box>
                    {cart.items[product.id] && (
                      <Chip
                        size="small"
                        label={`${cart.items[product.id]} en carrito`}
                        sx={{
                          position: 'absolute',
                          bottom: 12,
                          right: 12,
                          bgcolor: 'background.paper',
                        }}
                      />
                    )}
                  </Box>
                  <CardContent
                    sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', gap: 1.3, p: 2.5 }}
                  >
                    <PrescriptionBadge product={product} />
                    <Typography component="h3" variant="h6" sx={{ fontSize: 17, lineHeight: 1.4 }}>
                      {product.name}
                    </Typography>
                    <Typography color="text.secondary" variant="body2" sx={{ flexGrow: 1 }}>
                      {product.presentation}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Precio y disponibilidad por sucursal
                    </Typography>
                    <Stack direction="row" spacing={1}>
                      <Button fullWidth variant="outlined" onClick={() => openProduct(product)}>
                        Ver disponibilidad
                      </Button>
                      <Button
                        variant="contained"
                        aria-label={`Agregar ${product.name} al carrito`}
                        onClick={() => add(product)}
                        sx={{ minWidth: 48, fontSize: 23, px: 1 }}
                      >
                        <span aria-hidden="true">＋</span>
                      </Button>
                    </Stack>
                  </CardContent>
                </Card>
              );
            })}
          </Box>
          {!catalog.isPending && !catalog.error && visibleProducts.length === 0 && (
            <EmptyState
              title="No encontramos productos con esos filtros"
              description="Probá otro nombre, revisá la presentación o explorá todas las categorías."
              action={
                <Button
                  onClick={() => {
                    setSearch('');
                    setCategory('ALL');
                  }}
                >
                  Ver todo el catálogo
                </Button>
              }
            />
          )}
          <Typography variant="caption" color="text.secondary">
            Agregar al carrito no reserva existencias. La farmacia revisa la solicitud y confirma el
            presupuesto antes del pago.
          </Typography>
        </>
      )}
      <Dialog open={!!selected} onClose={() => setSelectedId('')} fullWidth maxWidth="md">
        {selected && (
          <>
            <DialogTitle>{selected.name}</DialogTitle>
            <DialogContent>
              <Stack spacing={2.5} sx={{ pt: 1 }}>
                <Typography color="text.secondary">{selected.presentation}</Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <PrescriptionBadge product={selected} />
                  {selected.coldChain && (
                    <Chip size="small" label="Requiere conservación en frío" />
                  )}
                  {selected.restricted && (
                    <Chip size="small" label="Dispensación con control especial" />
                  )}
                </Stack>
                {selected.requiresPrescription && (
                  <Alert severity="info">
                    Para solicitar este producto vas a necesitar adjuntar la receta. La farmacia
                    verifica su validez antes de cotizar.
                  </Alert>
                )}
                {cartError && <Alert severity="warning">{cartError}</Alert>}
                {notice && <Alert severity="success">{notice}</Alert>}
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={2}
                  sx={{ alignItems: { sm: 'flex-start' } }}
                >
                  <TextField
                    label="Cantidad a agregar"
                    type="number"
                    value={quantity}
                    onChange={(event) => setQuantity(event.target.value)}
                    error={!validQuantity}
                    helperText={
                      !validQuantity
                        ? 'Elegí entre 1 y 100 unidades.'
                        : 'Unidades de esta presentación'
                    }
                    slotProps={{ htmlInput: { min: 1, max: 100, step: 1 } }}
                    sx={{ width: { sm: 180 } }}
                  />
                  <Button
                    variant="contained"
                    disabled={!validQuantity}
                    onClick={() => add(selected, quantityNumber)}
                    sx={{ minHeight: 56 }}
                  >
                    Agregar al carrito
                  </Button>
                  <Button component={Link} to="/app/cart" sx={{ minHeight: 56 }}>
                    Ver carrito
                  </Button>
                </Stack>
                <Divider />
                <Stack
                  direction="row"
                  sx={{ justifyContent: 'space-between', alignItems: 'center', gap: 2 }}
                >
                  <Typography component="h3" variant="h6">
                    Disponibilidad por sucursal
                  </Typography>
                  <Button
                    disabled={availability.isFetching}
                    onClick={() => void availability.refetch()}
                  >
                    Actualizar
                  </Button>
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  Consultá precios informados y unidades disponibles. La sucursal confirma el stock
                  físico y el importe final al revisar tu compra.
                </Typography>
                {availability.isPending && (
                  <CircularProgress
                    size={28}
                    aria-label="Consultando disponibilidad del producto"
                  />
                )}
                {availability.error && <Alert severity="error">{availability.error.message}</Alert>}
                {!availability.isPending && !availability.error && offers.length === 0 && (
                  <Alert severity="info">
                    Por ahora no hay sucursales con disponibilidad informada para este producto.
                    Podés volver a consultar más tarde.
                  </Alert>
                )}
                {offers.map((offer) => (
                  <Card
                    key={offer.branch.id}
                    variant="outlined"
                    role="group"
                    aria-labelledby={`product-offer-${selected.id}-${offer.branch.id}`}
                  >
                    <CardContent>
                      <Stack spacing={1.2}>
                        <Typography variant="overline" color="text.secondary">
                          {offer.chainName}
                        </Typography>
                        <Typography
                          component="h4"
                          variant="h6"
                          id={`product-offer-${selected.id}-${offer.branch.id}`}
                        >
                          {offer.branch.name}
                        </Typography>
                        <Typography variant="body2">{offer.branch.address}</Typography>
                        <Stack
                          direction="row"
                          spacing={2}
                          sx={{ justifyContent: 'space-between', alignItems: 'baseline' }}
                        >
                          <Typography variant="h6" color="primary">
                            {money(offer.price, offer.currency)}
                          </Typography>
                          <Typography variant="body2" color="text.secondary">
                            {offer.availableQuantity} unidades informadas
                          </Typography>
                        </Stack>
                        <Typography variant="caption" color="text.secondary">
                          Actualizado:{' '}
                          {new Intl.DateTimeFormat('es-AR', {
                            timeZone: 'America/Argentina/Mendoza',
                            dateStyle: 'short',
                            timeStyle: 'short',
                          }).format(new Date(offer.observedAt))}{' '}
                          · hora de Mendoza
                        </Typography>
                        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                          <Button
                            variant="outlined"
                            onClick={() => setFocusedBranch(offer.branch.id)}
                          >
                            Ver sucursal en el mapa
                          </Button>
                          {offer.branch.pickupEnabled ? (
                            <Button
                              variant="contained"
                              disabled={!validQuantity || quantityNumber > offer.availableQuantity}
                              onClick={() => {
                                if (add(selected, quantityNumber, offer.branch.id))
                                  setSelectedId('');
                              }}
                            >
                              Retirar en esta sucursal
                            </Button>
                          ) : (
                            <Typography
                              variant="body2"
                              color="text.secondary"
                              sx={{ alignSelf: 'center' }}
                            >
                              Sin retiro habilitado
                            </Typography>
                          )}
                        </Stack>
                        {validQuantity && quantityNumber > offer.availableQuantity && (
                          <Typography variant="caption" color="text.secondary">
                            La cantidad elegida supera la disponibilidad informada de esta sucursal.
                          </Typography>
                        )}
                      </Stack>
                    </CardContent>
                  </Card>
                ))}
                {offers.length > 0 && (
                  <>
                    {activeBranchId && (
                      <Button onClick={() => setFocusedBranch('')}>
                        Mostrar todas las sucursales con disponibilidad
                      </Button>
                    )}
                    <LocationPicker
                      key={`${selected.id}-${activeBranchId}`}
                      value={null}
                      onChange={() => {}}
                      delivery={false}
                      readOnly
                      readOnlyTitle="Sucursales con disponibilidad"
                      readOnlyDescription="Consultá la dirección de cada sucursal. La farmacia confirma las existencias antes de cotizar."
                      readOnlyMapLabel="Mapa de disponibilidad del producto"
                      branches={shownBranches}
                    />
                  </>
                )}
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setSelectedId('')}>Cerrar</Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </Stack>
  );
}
