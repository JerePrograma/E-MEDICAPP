import { useEffect, useRef, useState } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

export type Point = { lat: number; lon: number };
export function LocationPicker({
  value,
  onChange,
  delivery,
}: {
  value: Point | null;
  onChange: (point: Point) => void;
  delivery: boolean;
}) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const marker = useRef<L.CircleMarker | null>(null);
  const change = useRef(onChange);
  change.current = onChange;
  const [notice, setNotice] = useState('');
  useEffect(() => {
    if (!element.current) return;
    const instance = L.map(element.current, { scrollWheelZoom: false }).setView(
      [-32.8895, -68.8458],
      13,
    );
    map.current = instance;
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(instance);
    instance.on('click', (e: L.LeafletMouseEvent) =>
      change.current({ lat: e.latlng.lat, lon: e.latlng.lng }),
    );
    return () => {
      instance.remove();
      map.current = null;
      marker.current = null;
    };
  }, []);
  useEffect(() => {
    if (value && map.current) {
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
      <Typography variant="caption" color={value ? 'primary' : 'text.secondary'}>
        {value ? 'Punto confirmado en el mapa.' : 'Todavía no confirmaste un punto.'} {notice}
      </Typography>
    </Stack>
  );
}
