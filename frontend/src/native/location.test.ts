import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { currentLocation } from './location';

const native = vi.hoisted(() => ({ isNativePlatform: vi.fn() }));
const gps = vi.hoisted(() => ({
  checkPermissions: vi.fn(),
  requestPermissions: vi.fn(),
  getCurrentPosition: vi.fn(),
}));
vi.mock('@capacitor/core', () => ({ Capacitor: native }));
vi.mock('@capacitor/geolocation', () => ({ Geolocation: gps }));
beforeEach(() => vi.resetAllMocks());
afterEach(() => vi.unstubAllGlobals());

describe('Ubicación desde navegador y Android', () => {
  it('usa la ubicación del navegador sin pedir permisos nativos', async () => {
    native.isNativePlatform.mockReturnValue(false);
    vi.stubGlobal('navigator', {
      geolocation: {
        getCurrentPosition: (success: (position: unknown) => void) =>
          success({ coords: { latitude: -32.89, longitude: -68.84 } }),
      },
    });
    expect(await currentLocation()).toEqual({ lat: -32.89, lon: -68.84 });
    expect(gps.checkPermissions).not.toHaveBeenCalled();
  });
  it('solicita permiso nativo antes de consultar GPS y acepta ubicación aproximada', async () => {
    native.isNativePlatform.mockReturnValue(true);
    gps.checkPermissions.mockResolvedValue({ location: 'prompt', coarseLocation: 'prompt' });
    gps.requestPermissions.mockResolvedValue({ location: 'denied', coarseLocation: 'granted' });
    gps.getCurrentPosition.mockResolvedValue({ coords: { latitude: -32, longitude: -68 } });
    expect(await currentLocation()).toEqual({ lat: -32, lon: -68 });
    expect(gps.requestPermissions).toHaveBeenCalledOnce();
  });
  it('un permiso denegado no inicia GPS y permite volver al mapa manual', async () => {
    native.isNativePlatform.mockReturnValue(true);
    gps.checkPermissions.mockResolvedValue({ location: 'denied', coarseLocation: 'denied' });
    await expect(currentLocation()).rejects.toThrow('mapa');
    expect(gps.getCurrentPosition).not.toHaveBeenCalled();
  });
});
