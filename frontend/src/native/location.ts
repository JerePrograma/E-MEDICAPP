import { Capacitor } from '@capacitor/core';
import { Geolocation } from '@capacitor/geolocation';

export async function currentLocation(): Promise<{ lat: number; lon: number }> {
  if (Capacitor.isNativePlatform()) {
    let permission = await Geolocation.checkPermissions();
    if (permission.location === 'prompt' || permission.location === 'prompt-with-rationale') {
      permission = await Geolocation.requestPermissions({ permissions: ['location'] });
    }
    if (permission.location !== 'granted' && permission.coarseLocation !== 'granted') {
      throw new Error('No se autorizó la ubicación. Marcá el punto en el mapa.');
    }
    const position = await Geolocation.getCurrentPosition({
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 60000,
    });
    return { lat: position.coords.latitude, lon: position.coords.longitude };
  }
  if (!navigator.geolocation) throw new Error('La ubicación no está disponible.');
  const position = await new Promise<GeolocationPosition>((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      timeout: 10000,
      maximumAge: 60000,
    });
  });
  return { lat: position.coords.latitude, lon: position.coords.longitude };
}
