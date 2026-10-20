import type { Branch } from '../catalog/types';

export type Promotion = {
  id: string;
  title: string;
  description: string;
  startsAt: string;
  endsAt: string;
  featured: boolean;
  active: boolean;
  version: number;
  chainId: string | null;
  chainName: string;
  branchIds: string[];
  branches: Branch[];
};

export function promotionState(promotion: Promotion, now = Date.now()) {
  if (!promotion.active) return 'Pausada';
  if (Date.parse(promotion.endsAt) <= now) return 'Finalizada';
  if (Date.parse(promotion.startsAt) > now) return 'Programada';
  return 'Vigente';
}

export function mendozaDateTime(value: string | Date) {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'America/Argentina/Mendoza',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
    .format(new Date(value))
    .replace(' ', 'T');
}

export function promotionDate(value: string) {
  return new Intl.DateTimeFormat('es-AR', {
    timeZone: 'America/Argentina/Mendoza',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
