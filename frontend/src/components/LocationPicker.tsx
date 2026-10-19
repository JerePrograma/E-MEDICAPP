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
}: {
  value: Point | null;
  onChange: (point: Point) => void;
  delivery: boolean;
  branches?: Branch[];
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
    const instance = L.map(element.current, { scrollWheelZoom: false }).setView(
      [-32.8895, -68.8458],
      13,
    );
    map.current = instance;
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(instance);
    instance.on('click', (e: L.LeafletMouseEvent) => {
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
  }, []);
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
        .bindPopup(branchPopup(branch))
        .addTo(layer);
      branchMarker.getElement()?.setAttribute('aria-label', `Sucursal ${branch.name}`);
      branchMarker.on('click', () => {
        interacted.current = true;
      });
    }
    if (points.length && !currentValue.current && !fittedBranches.current && !interacted.current) {
      fittedBranches.current = true;
      instance.fitBounds(L.latLngBounds(points), { padding: [36, 36], maxZoom: 15 });
    }
    return () => {
      active = false;
      objectUrls.forEach((url) => URL.revokeObjectURL(url));
      if (map.current === instance) layer.remove();
    };
  }, [branches]);
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
  }, [value]);
  return (
    <Stack spacing={1.5}>
      <Typography sx={{ fontWeight: 650 }}>
        {delivery
          ? 'Ubicación del domicilio de entrega'
          : 'Tu ubicación actual para buscar el retiro'}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {delivery
          ? 'Marcá el domicilio exacto en el mapa y completá la dirección.'
          : 'Usá tu ubicación actual o confirmá en el mapa dónde estás.'}
      </Typography>
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
      <Box
        ref={element}
        role="region"
        aria-label="Mapa para confirmar ubicación"
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
        <Typography variant="caption" color="text.secondary">
          <span className="emedicapp-map-legend__point" aria-hidden="true" /> Tu punto confirmado.
        </Typography>
      </Stack>
      <Typography variant="caption" color={value ? 'primary' : 'text.secondary'}>
        {value ? 'Punto confirmado en el mapa.' : 'Todavía no confirmaste un punto.'} {notice}
      </Typography>
    </Stack>
  );
}
