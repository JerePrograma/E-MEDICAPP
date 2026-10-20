import type { SalesPeriod } from './types';

export const reportPeriods: { value: SalesPeriod; label: string }[] = [
  { value: 'DAY', label: 'Diario' },
  { value: 'WEEK', label: 'Semanal' },
  { value: 'MONTH', label: 'Mensual' },
  { value: 'YEAR', label: 'Anual' },
];

export function businessDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Argentina/Mendoza',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function validReportDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T12:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().startsWith(value);
}

export function reportDateTime(value: string, timeZone: string): string {
  return new Intl.DateTimeFormat('es-AR', {
    timeZone,
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

export function reportRange(from: string, toExclusive: string, timeZone: string): string {
  const format = new Intl.DateTimeFormat('es-AR', {
    timeZone,
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
  const first = format.format(new Date(from));
  const last = format.format(new Date(new Date(toExclusive).getTime() - 1));
  return first === last ? first : `${first} al ${last}`;
}

export function bucketLabel(from: string, period: SalesPeriod, timeZone: string): string {
  return new Intl.DateTimeFormat('es-AR', {
    timeZone,
    ...(period === 'DAY'
      ? { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' as const }
      : period === 'YEAR'
        ? { month: 'long' as const }
        : { weekday: 'short' as const, day: 'numeric' as const, month: 'short' as const }),
  }).format(new Date(from));
}
