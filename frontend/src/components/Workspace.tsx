import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import {
  Box,
  Button,
  Divider,
  Menu,
  MenuItem,
  Stack,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { NavLink } from 'react-router-dom';
import type { Actor } from '../api';
import { useCart } from '../cart/CartContext';

function NavigationIcon({ name }: { name: string }) {
  const paths: Record<string, string> = {
    catalog: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    cart: 'M3 4h2l2 12h11l3-9H6M9 21h.01M18 21h.01',
    orders: 'M6 3h12v18l-3-2-3 2-3-2-3 2zM9 7h6M9 11h6M9 15h3',
    stock: 'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10M7 5l10 4',
    deliveries: 'M3 5h11v12H3zM14 10h4l3 4v3h-7M5 17a2 2 0 1 0 4 0M16 17a2 2 0 1 0 4 0',
    admin: 'M4 21V5h16v16M8 9h2M14 9h2M8 13h2M14 13h2M10 21v-4h4v4M2 21h20',
    reports: 'M4 3v17h17M8 16v-5M13 16V7M18 16v-8',
    promotions: 'M3 3h8l10 10-8 8L3 11zM7 7h.01',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
  };
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d={paths[name]}
        stroke="currentColor"
        strokeWidth="1.65"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function Workspace({ actor, children }: { actor: Actor; children: ReactNode }) {
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const desktopNavigation = useMediaQuery(useTheme().breakpoints.up('md'));
  useEffect(() => {
    if (desktopNavigation) setMenuAnchor(null);
  }, [desktopNavigation]);
  const cart = useCart();
  const has = (...roles: string[]) => actor.roles.some((role) => roles.includes(role));
  const pharmacy = has('PHARMACY', 'PHARMACIST');
  const links = [
    ...(has('BUYER')
      ? [
          { to: '/app/catalog', text: 'Catálogo', icon: 'catalog' },
          {
            to: '/app/cart',
            text: cart.totalUnits ? `Carrito (${cart.totalUnits})` : 'Carrito',
            mobileText: 'Carrito',
            icon: 'cart',
          },
        ]
      : []),
    ...(has('BUYER', 'PHARMACY', 'PHARMACIST')
      ? [
          {
            to: '/app',
            text: pharmacy ? 'Solicitudes y pedidos' : 'Mis compras',
            mobileText: pharmacy ? 'Pedidos' : 'Mis compras',
            icon: 'orders',
          },
        ]
      : []),
    ...(pharmacy
      ? [{ to: '/app/stock', text: 'Stock y precios', mobileText: 'Stock', icon: 'stock' }]
      : []),
    ...(has('DRIVER', 'PHARMACY', 'PHARMACIST')
      ? [{ to: '/app/deliveries', text: 'Entregas', icon: 'deliveries' }]
      : []),
    ...(has('ADMIN', 'PHARMACY_ADMIN')
      ? [{ to: '/app/admin', text: 'Administración', mobileText: 'Gestión', icon: 'admin' }]
      : []),
    ...(has('BUYER') ? [{ to: '/app/promotions', text: 'Promociones', icon: 'promotions' }] : []),
    ...(has('ADMIN', 'PHARMACY_ADMIN', 'PHARMACY', 'PHARMACIST')
      ? [
          { to: '/app/reports', text: 'Ventas y cobros', mobileText: 'Ventas', icon: 'reports' },
          {
            to: '/app/pharmacy-promotions',
            text: has('BUYER') ? 'Gestionar promociones' : 'Promociones',
            icon: 'promotions',
          },
        ]
      : []),
  ];
  const mobileHasMore = links.length > 5;
  return (
    <Box
      sx={{
        maxWidth: 1480,
        mx: 'auto',
        display: { md: 'grid' },
        gridTemplateColumns: '220px minmax(0,1fr)',
        minHeight: '100%',
      }}
    >
      <Box
        component="nav"
        aria-label="Navegación principal"
        sx={{
          borderRight: { md: '1px solid' },
          borderTop: { xs: '1px solid', md: 'none' },
          borderColor: 'divider',
          position: { xs: 'fixed', md: 'static' },
          bottom: 0,
          left: 0,
          right: 0,
          zIndex: (theme) => theme.zIndex.appBar,
          px: { xs: 0.75, md: 2 },
          pt: { xs: 0.75, md: 4 },
          pb: { xs: 'max(6px, env(safe-area-inset-bottom, 0px))', md: 4 },
          bgcolor: { xs: 'background.paper', md: 'transparent' },
          boxShadow: { xs: '0 -4px 20px #0b1f3308', md: 'none' },
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
          <Stack
            direction={{ xs: 'row', md: 'column' }}
            spacing={0.5}
            useFlexGap
            sx={{
              display: { xs: 'grid', md: 'flex' },
              gridTemplateColumns: `repeat(${Math.min(links.length, 5)}, minmax(0, 1fr))`,
              maxWidth: { xs: 640, md: 'none' },
              mx: 'auto',
            }}
          >
            {links.map((link, index) => (
              <Button
                key={link.to}
                component={NavLink}
                to={link.to}
                aria-label={link.text}
                end={link.to === '/app'}
                sx={{
                  display: { xs: mobileHasMore && index >= 4 ? 'none' : 'flex', md: 'flex' },
                  flexDirection: { xs: 'column', md: 'row' },
                  justifyContent: { xs: 'center', md: 'flex-start' },
                  gap: { xs: 0.6, md: 1.3 },
                  color: 'text.secondary',
                  whiteSpace: { xs: 'nowrap', md: 'normal' },
                  minWidth: 0,
                  minHeight: { xs: 58, md: 44 },
                  px: { xs: 0.5, md: 1.5 },
                  py: { xs: 0.75, md: 1.2 },
                  fontSize: { xs: '0.6875rem', md: '0.82rem' },
                  borderRadius: 2,
                  '&.active': { bgcolor: 'primary.light', color: 'primary.dark' },
                }}
              >
                <Box
                  component="span"
                  aria-hidden
                  sx={{ display: 'flex', position: 'relative', flexShrink: 0 }}
                >
                  <NavigationIcon name={link.icon} />
                  {link.icon === 'cart' && cart.totalUnits > 0 && (
                    <Box
                      component="span"
                      sx={{
                        position: 'absolute',
                        top: -7,
                        right: -12,
                        minWidth: 17,
                        height: 17,
                        px: 0.4,
                        borderRadius: 10,
                        bgcolor: 'primary.main',
                        color: 'white',
                        fontSize: 10,
                        lineHeight: '17px',
                        textAlign: 'center',
                        display: { md: 'none' },
                      }}
                    >
                      {cart.totalUnits > 99 ? '99+' : cart.totalUnits}
                    </Box>
                  )}
                </Box>
                <Box component="span" sx={{ display: { xs: 'none', md: 'inline' } }}>
                  {link.text}
                </Box>
                <Box component="span" sx={{ display: { xs: 'inline', md: 'none' } }}>
                  {'mobileText' in link
                    ? link.mobileText
                    : pharmacy && link.icon === 'promotions'
                      ? 'Ofertas'
                      : link.text}
                </Box>
              </Button>
            ))}
            {mobileHasMore && (
              <Button
                aria-label="Más secciones"
                aria-controls={menuAnchor ? 'workspace-more-menu' : undefined}
                aria-haspopup="menu"
                aria-expanded={!!menuAnchor}
                onClick={(event) => setMenuAnchor(event.currentTarget)}
                sx={{
                  display: { xs: 'flex', md: 'none' },
                  flexDirection: 'column',
                  gap: 0.6,
                  minWidth: 0,
                  minHeight: 58,
                  px: 0.5,
                  color: 'text.secondary',
                  fontSize: '0.6875rem',
                }}
              >
                <NavigationIcon name="more" />
                Más
              </Button>
            )}
          </Stack>
          <Menu
            id="workspace-more-menu"
            anchorEl={menuAnchor}
            open={!!menuAnchor && !desktopNavigation}
            onClose={() => setMenuAnchor(null)}
            anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
            transformOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            sx={{ display: { md: 'none' } }}
          >
            {links.slice(4).map((link) => (
              <MenuItem
                key={link.to}
                component={NavLink}
                to={link.to}
                end={link.to === '/app'}
                onClick={() => setMenuAnchor(null)}
                sx={{
                  minHeight: 48,
                  gap: 1.5,
                  '&.active': { color: 'primary.main', bgcolor: 'primary.light' },
                }}
              >
                <NavigationIcon name={link.icon} />
                {link.text}
              </MenuItem>
            ))}
          </Menu>
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
        sx={{
          minWidth: 0,
          px: { xs: 2, sm: 3, lg: 4 },
          pt: { xs: 2, md: 4 },
          pb: { xs: 'calc(88px + env(safe-area-inset-bottom, 0px))', md: 4 },
        }}
      >
        {children}
      </Box>
    </Box>
  );
}
