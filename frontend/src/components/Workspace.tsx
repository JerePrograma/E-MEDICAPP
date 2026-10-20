import type { ReactNode } from 'react';
import { Box, Button, Divider, Stack, Typography } from '@mui/material';
import { NavLink } from 'react-router-dom';
import type { Actor } from '../api';
import { useCart } from '../cart/CartContext';

export function Workspace({ actor, children }: { actor: Actor; children: ReactNode }) {
  const cart = useCart();
  const has = (...roles: string[]) => actor.roles.some((role) => roles.includes(role));
  const pharmacy = has('PHARMACY', 'PHARMACIST');
  const links = [
    ...(has('BUYER')
      ? [
          { to: '/app/catalog', text: 'Catálogo', glyph: '01' },
          {
            to: '/app/cart',
            text: cart.totalUnits ? `Carrito (${cart.totalUnits})` : 'Carrito',
            glyph: '02',
          },
        ]
      : []),
    ...(has('BUYER', 'PHARMACY', 'PHARMACIST')
      ? [{ to: '/app', text: pharmacy ? 'Solicitudes y pedidos' : 'Mis compras', glyph: '01' }]
      : []),
    ...(pharmacy ? [{ to: '/app/stock', text: 'Stock y precios', glyph: '02' }] : []),
    ...(has('DRIVER', 'PHARMACY', 'PHARMACIST')
      ? [{ to: '/app/deliveries', text: 'Entregas', glyph: pharmacy ? '03' : '01' }]
      : []),
    ...(has('ADMIN', 'PHARMACY_ADMIN')
      ? [{ to: '/app/admin', text: 'Administración', glyph: '01' }]
      : []),
    ...(has('BUYER') ? [{ to: '/app/promotions', text: 'Promociones', glyph: '02' }] : []),
    ...(has('ADMIN', 'PHARMACY_ADMIN', 'PHARMACY', 'PHARMACIST')
      ? [
          { to: '/app/reports', text: 'Ventas y cobros', glyph: '04' },
          { to: '/app/pharmacy-promotions', text: 'Promociones', glyph: '05' },
        ]
      : []),
  ];
  return (
    <Box
      sx={{
        maxWidth: 1480,
        mx: 'auto',
        display: { md: 'grid' },
        gridTemplateColumns: '220px minmax(0,1fr)',
        minHeight: 'calc(100vh - 76px)',
      }}
    >
      <Box
        component="nav"
        aria-label="Navegación principal"
        sx={{
          borderRight: { md: '1px solid' },
          borderBottom: { xs: '1px solid', md: 'none' },
          borderColor: 'divider',
          px: { xs: 1.5, md: 2 },
          py: { xs: 1, md: 4 },
          bgcolor: { xs: 'background.paper', md: 'transparent' },
        }}
      >
        <Box sx={{ position: { md: 'sticky' }, top: 104 }}>
          <Typography
            variant="overline"
            color="text.secondary"
            sx={{ px: 1.5, display: { xs: 'none', md: 'block' }, mb: 1.5 }}
          >
            {pharmacy
              ? 'Operación de farmacia'
              : has('ADMIN', 'PHARMACY_ADMIN')
                ? 'Gestión de la red'
                : has('DRIVER')
                  ? 'Tu jornada'
                  : 'Tu farmacia conectada'}
          </Typography>
          <Stack direction={{ xs: 'row', md: 'column' }} spacing={0.5} sx={{ overflowX: 'auto' }}>
            {links.map((link) => (
              <Button
                key={link.to}
                component={NavLink}
                to={link.to}
                end={link.to === '/app'}
                sx={{
                  justifyContent: 'flex-start',
                  color: 'text.secondary',
                  whiteSpace: 'nowrap',
                  px: 1.5,
                  py: 1.2,
                  fontSize: '0.82rem',
                  '&.active': { bgcolor: 'primary.light', color: 'primary.dark' },
                }}
              >
                <Box
                  component="span"
                  aria-hidden
                  sx={{ mr: 1.3, opacity: 0.55, fontSize: '0.68rem' }}
                >
                  {link.glyph}
                </Box>
                {link.text}
              </Button>
            ))}
          </Stack>
          <Box sx={{ display: { xs: 'none', md: 'block' }, px: 1.5, mt: 5 }}>
            <Divider sx={{ mb: 2 }} />
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', lineHeight: 1.8 }}
            >
              Provincia de Mendoza
              <br />
              {has('BUYER')
                ? 'La farmacia cobra y factura. El envío no tiene costo.'
                : 'Cada registro conserva su responsable y sus condiciones.'}
            </Typography>
          </Box>
        </Box>
      </Box>
      <Box
        component="main"
        id="main-content"
        sx={{ minWidth: 0, px: { xs: 2, sm: 3, lg: 4 }, py: { xs: 3, md: 4 } }}
      >
        {children}
      </Box>
    </Box>
  );
}
