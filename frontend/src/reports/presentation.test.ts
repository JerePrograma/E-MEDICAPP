import { describe, expect, it } from 'vitest';
import { bucketLabel, businessDate, reportRange, validReportDate } from './presentation';

const zone = 'America/Argentina/Mendoza';

describe('fechas de reportes', () => {
  it('usa el día comercial de Mendoza aunque UTC ya haya cambiado de día', () => {
    expect(businessDate(new Date('2026-10-21T01:30:00Z'))).toBe('2026-10-20');
    expect(businessDate(new Date('2026-10-21T03:00:00Z'))).toBe('2026-10-21');
  });

  it('rechaza fechas inexistentes y admite años bisiestos', () => {
    expect(validReportDate('2026-02-29')).toBe(false);
    expect(validReportDate('2028-02-29')).toBe(true);
    expect(validReportDate('2026-13-01')).toBe(false);
    expect(validReportDate('')).toBe(false);
  });

  it('muestra el último día incluido y evita sumar un día al período', () => {
    const range = reportRange('2026-10-01T03:00:00Z', '2026-11-01T03:00:00Z', zone);
    expect(range).toContain('1 de octubre de 2026');
    expect(range).toContain('31 de octubre de 2026');
    expect(range).not.toContain('noviembre');
  });

  it('muestra la medianoche comercial como hora inicial del reporte diario', () => {
    expect(bucketLabel('2026-10-20T03:00:00Z', 'DAY', zone)).toBe('00:00');
  });
});
