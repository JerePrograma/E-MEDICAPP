import { useEffect, useRef, useState } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import L from 'leaflet';
import type { Branch } from '../catalog/types';
import { apiFetch, apiUrl } from '../api';
import { crossOriginBackend } from '../connection';
import 'leaflet/dist/leaflet.css';
import './LocationPicker.css';

export type Point = { lat: number; lon: number };
const NO_BRANCHES: Branch[] = [];

function branchIcon(branch: Branch, objectUrls: string[], active: () => boolean) {
  const container = document.createElement('div');
  container.className = 'emedicapp-branch-marker__face';
  container.setAttribute('aria-hidden', 'true');
  const fallback = document.createElement('span');
  fallback.className = 'emedicapp-branch-marker__fallback';
  fallback.textContent = '+';
  container.append(fallback);
  if (branch.chainLogoUrl) {
    const logo = document.createElement('img');
    logo.alt = '';
    logo.hidden = true;
    logo.onload = () => {
      logo.hidden = false;
      fallback.hidden = true;
    };
    logo.onerror = () => {
      logo.hidden = true;
      fallback.hidden = false;
    };
    if (crossOriginBackend()) {
      void apiFetch(branch.chainLogoUrl)
        .then(async (response) => {
          if (!response.ok) return;
          const blob = await response.blob();
          if (!active()) return;
          const url = URL.createObjectURL(blob);
          objectUrls.push(url);
          logo.src = url;
        })
        .catch(() => {
          logo.hidden = true;
          fallback.hidden = false;
        });
    } else logo.src = apiUrl(branch.chainLogoUrl);
    container.append(logo);
  }
  return L.divIcon({
    html: container,
    className: 'emedicapp-branch-marker',
    iconSize: [48, 58],
    iconAnchor: [24, 58],
    popupAnchor: [0, -56],
  });
}

function branchPopup(branch: Branch) {
  const container = document.createElement('div');
  const name = document.createElement('strong');
  name.textContent = branch.name;
  const address = document.createElement('div');
  address.textContent = branch.address;
  container.append(name, address);
  return container;
}

export function LocationPicker({
  value,
  onChange,
  delivery,
  branches = NO_BRANCHES,
  readOnly = false,
  readOnlyTitle = 'Sucursales participantes en el mapa',
  readOnlyDescription = 'Tocá una sucursal para consultar su dirección. Sólo se muestran las sucursales de esta promoción.',
  readOnlyMapLabel = 'Mapa de sucursales participantes',
}: {
  value: Point | null;
  onChange: (point: Point) => void;
  delivery: boolean;
  branches?: Branch[];
  readOnly?: boolean;
  readOnlyTitle?: string;
  readOnlyDescription?: string;
  readOnlyMapLabel?: string;
}) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const marker = useRef<L.CircleMarker | null>(null);
  const change = useRef(onChange);
  change.current = onChange;
  const currentValue = useRef(value);
  currentValue.current = value;
  const fittedBranches = useRef(false);
  const interacted = useRef(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    if (!element.current) return;
    fittedBranches.current = false;
    interacted.current = false;
    const instance = L.map(element.current, {
      scrollWheelZoom: false,
      zoomAnimation: !readOnly,
    }).setView([-32.8895, -68.8458], 13);
    map.current = instance;
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(instance);
    instance.on('click', (e: L.LeafletMouseEvent) => {
      if (readOnly) return;
      interacted.current = true;
      change.current({ lat: e.latlng.lat, lon: e.latlng.lng });
    });
    instance.on('movestart zoomstart', () => {
      interacted.current = true;
    });
    return () => {
      instance.remove();
      map.current = null;
      marker.current = null;
    };
  }, [readOnly]);
  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    const layer = L.layerGroup().addTo(instance);
    const objectUrls: string[] = [];
    let active = true;
    const points: L.LatLngExpression[] = [];
    for (const branch of branches) {
      if (!String(branch.latitude ?? '').trim() || !String(branch.longitude ?? '').trim()) continue;
      const lat = Number(branch.latitude);
      const lon = Number(branch.longitude);
      if (
        !Number.isFinite(lat) ||
        !Number.isFinite(lon) ||
        Math.abs(lat) > 90 ||
        Math.abs(lon) > 180
      )
        continue;
      const point: L.LatLngExpression = [lat, lon];
      points.push(point);
      const branchMarker = L.marker(point, {
        icon: branchIcon(branch, objectUrls, () => active),
        title: `Sucursal ${branch.name}`,
        alt: `Sucursal ${branch.name}`,
        keyboard: true,
        bubblingMouseEvents: false,
        riseOnHover: true,
      })
        .bindPopup(
          branchPopup(branch),
          readOnly
            ? { maxWidth: Math.max(80, Math.min(240, instance.getSize().x - 80)) }
            : undefined,
        )
        .addTo(layer);
      const icon = branchMarker.getElement();
      icon?.setAttribute('aria-label', `Sucursal ${branch.name}`);
      if (readOnly && icon) {
        // Leaflet's default mouse focus scrolls a containing dialog between
        // mousedown and mouseup. Keep focus in place so the marker receives the click.
        icon.addEventListener('mousedown', (event) => {
          event.preventDefault();
          event.stopPropagation();
          icon.focus({ preventScroll: true });
        });
      }
      branchMarker.on('click', () => {
        interacted.current = true;
      });
    }
    const fitBranches = () => {
      instance.fitBounds(L.latLngBounds(points), {
        ...(readOnly
          ? {
              paddingTopLeft: [56, 82] as L.PointExpression,
              paddingBottomRight: [56, 36] as L.PointExpression,
            }
          : { padding: [36, 36] as L.PointExpression }),
        maxZoom: 15,
        animate: false,
      });
    };
    if (points.length && !currentValue.current && !fittedBranches.current && !interacted.current) {
      fittedBranches.current = true;
      fitBranches();
    }
    const observer = readOnly
      ? new ResizeObserver(() => {
          if (map.current !== instance || !element.current?.clientWidth) return;
          instance.invalidateSize({ pan: false, animate: false });
          instance.closePopup();
          layer.eachLayer((branchLayer) => {
            const popup = branchLayer.getPopup();
            if (popup)
              popup.options.maxWidth = Math.max(80, Math.min(240, instance.getSize().x - 80));
          });
          if (points.length) fitBranches();
        })
      : null;
    if (observer && element.current) observer.observe(element.current);
    return () => {
      observer?.disconnect();
      active = false;
      objectUrls.forEach((url) => URL.revokeObjectURL(url));
      if (map.current === instance) layer.remove();
    };
  }, [branches, readOnly]);
  useEffect(() => {
    if (!value) {
      marker.current?.remove();
      marker.current = null;
    } else if (map.current) {
      const point: L.LatLngExpression = [value.lat, value.lon];
      if (marker.current) marker.current.setLatLng(point);
      else
        marker.current = L.circleMarker(point, {
          radius: 10,
          color: '#14766f',
          fillOpacity: 0.8,
        }).addTo(map.current);
      map.current.panTo(point);
    }
  }, [value, readOnly]);
  return (
    <Stack spacing={1.5}>
      <Typography sx={{ fontWeight: 650 }}>
        {readOnly
          ? readOnlyTitle
          : delivery
            ? 'Ubicación del domicilio de entrega'
            : 'Tu ubicación actual para buscar el retiro'}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {readOnly
          ? readOnlyDescription
          : delivery
            ? 'Marcá el domicilio exacto en el mapa y completá la dirección.'
            : 'Usá tu ubicación actual o confirmá en el mapa dónde estás.'}
      </Typography>
      {!readOnly && (
        <Button
          variant="outlined"
          onClick={() => {
            if (!navigator.geolocation) {
              setNotice('No se pudo consultar la ubicación. Marcá el punto en el mapa.');
              return;
            }
            setNotice('Buscando ubicación…');
            navigator.geolocation.getCurrentPosition(
              (p) => {
                onChange({ lat: p.coords.latitude, lon: p.coords.longitude });
                setNotice('Ubicación marcada. Confirmá que el punto sea correcto.');
              },
              () => setNotice('No se pudo consultar la ubicación. Marcá el punto en el mapa.'),
              { timeout: 10000, maximumAge: 60000 },
            );
          }}
        >
          Usar mi ubicación actual
        </Button>
      )}
      <Box
        ref={element}
        role="region"
        aria-label={readOnly ? readOnlyMapLabel : 'Mapa para confirmar ubicación'}
        sx={{
          height: 280,
          borderRadius: 2,
          overflow: 'hidden',
          border: '1px solid #dfe8e7',
          zIndex: 0,
        }}
      />
      <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography variant="caption" color="text.secondary">
          <span className="emedicapp-map-legend__branch" aria-hidden="true">
            +
          </span>{' '}
          Sucursales: logo de la cadena o cruz de farmacia. Tocá un ícono para ver la dirección.
        </Typography>
        {!readOnly && (
          <Typography variant="caption" color="text.secondary">
            <span className="emedicapp-map-legend__point" aria-hidden="true" /> Tu punto confirmado.
          </Typography>
        )}
      </Stack>
      {!readOnly && (
        <Typography variant="caption" color={value ? 'primary' : 'text.secondary'}>
          {value ? 'Punto confirmado en el mapa.' : 'Todavía no confirmaste un punto.'} {notice}
        </Typography>
      )}
    </Stack>
  );
}
