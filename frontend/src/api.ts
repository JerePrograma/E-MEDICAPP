import {
  apiUrl,
  authSession,
  backendUrl,
  clearAuthSession,
  crossOriginBackend,
  rememberAuthSession,
} from './connection';

export { apiUrl } from './connection';

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

const retryKeys = new Map<string, string>();

export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  if (!path.startsWith('/api/'))
    throw new ApiError(400, 'INVALID_API_PATH', 'La dirección del recurso no es válida.');
  const requestBackend = backendUrl();
  const session = authSession();
  const headers = new Headers(options.headers);
  if (session) headers.set('X-Auth-Session', session);
  if (new URL(apiUrl(path), window.location.origin).hostname.endsWith('.ngrok-free.app'))
    headers.set('ngrok-skip-browser-warning', 'true');
  let response: Response;
  try {
    response = await fetch(apiUrl(path), {
      ...options,
      headers: Object.fromEntries(headers.entries()),
      credentials: crossOriginBackend() ? 'omit' : 'same-origin',
    });
  } catch {
    throw new ApiError(
      0,
      'NETWORK_ERROR',
      'No se pudo conectar con el servidor. Revisá la conexión y volvé a intentar.',
    );
  }
  rememberAuthSession(response, session, requestBackend);
  return response;
}

export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; idempotent?: boolean; form?: boolean } = {},
): Promise<T> {
  const requestBackend = backendUrl();
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  const isFile = options.body instanceof FormData;
  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    if (isFile) body = options.body as FormData;
    else if (options.form) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      body = new URLSearchParams(options.body as Record<string, string>);
    } else {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.body);
    }
  }
  let operationKey: string | undefined;
  if (method !== 'GET') {
    const csrfResponse = await apiFetch('/api/auth/csrf', {
      headers: { Accept: 'application/json' },
    });
    if (!csrfResponse.ok)
      throw new ApiError(
        csrfResponse.status,
        'CSRF_UNAVAILABLE',
        'No se pudo preparar la operación. Intentá nuevamente.',
      );
    const csrf = (await csrfResponse.json()) as { token: string; headerName: string };
    headers[csrf.headerName] = csrf.token;
    if (options.idempotent) {
      operationKey = `${method}:${path}:${body}`;
      const key = retryKeys.get(operationKey) ?? crypto.randomUUID();
      retryKeys.set(operationKey, key);
      headers['Idempotency-Key'] = key;
    }
  }
  const response = await apiFetch(path, { method, headers, body });
  const data: unknown =
    response.status === 204 ? undefined : await response.json().catch(() => undefined);
  if (!response.ok) {
    const problem = data as { code?: string; detail?: string; message?: string } | undefined;
    if (operationKey && response.status < 500) retryKeys.delete(operationKey);
    throw new ApiError(
      response.status,
      problem?.code ?? 'REQUEST_FAILED',
      problem?.detail ?? problem?.message ?? 'No se pudo completar la operación.',
    );
  }
  if (operationKey) retryKeys.delete(operationKey);
  if (path === '/api/auth/logout') clearAuthSession(requestBackend);
  if (response.status !== 204 && data === undefined)
    throw new ApiError(
      502,
      'INVALID_RESPONSE',
      'El servidor no devolvió una respuesta válida. Revisá la conexión de la demostración.',
    );
  return data as T;
}

export type Actor = {
  id: string;
  email: string;
  name: string;
  birthDate?: string;
  emailVerified?: boolean;
  dni?: string;
  phone?: string;
  address?: string;
  roles: string[];
  branchIds: string[];
  branchRoles?: Record<string, string[]>;
};

export function canOperateBranch(actor: Actor, branchId: string, ...roles: string[]): boolean {
  return roles.some(
    (role) => actor.roles.includes(role) && actor.branchRoles?.[branchId]?.includes(role),
  );
}
export type Bootstrap = { mode: 'DEMO' | 'MANUAL'; features?: Record<string, unknown> };

export function money(value: string | number | null | undefined, currency = 'ARS'): string {
  if (value === null || value === undefined) return '—';
  const [integer = '0', fraction = ''] = String(value).split('.');
  return `${currency} ${integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${fraction.padEnd(2, '0').slice(0, 2)}`;
}

export const labels: Record<string, string> = {
  BUYER: 'Comprador',
  ADMIN: 'Administración',
  PHARMACY: 'Farmacia',
  PHARMACIST: 'Farmacéutico',
  DRIVER: 'Cadete',
  ROUTING: 'En revisión de farmacias',
  PROPOSED: 'Solicitud por revisar',
  FULFILLING: 'En preparación y cumplimiento',
  COMPENSATING: 'Resolución pendiente',
  REVIEW_PENDING: 'Revisión pendiente',
  QUOTED: 'Presupuesto disponible',
  ACCEPTED: 'Aceptada',
  PAYMENT_PENDING: 'Esperando pago',
  PAID: 'Pago verificado',
  PARTIALLY_PAID: 'Pagos incompletos',
  PREPARING: 'En preparación',
  DISPENSED: 'Dispensado',
  ASSIGNED: 'Cadete asignado',
  ACCEPTED_BY_DRIVER: 'Tarea aceptada',
  IN_TRANSIT: 'En camino',
  DELIVERED: 'Entregado',
  PICKED_UP: 'Retirado',
  COMPLETED: 'Completado',
  CANCELLED: 'Cancelado',
  REFUND_PENDING: 'Reintegro pendiente',
  REFUNDED: 'Reintegrado',
  VERIFIED: 'Verificado',
  REQUESTED: 'Solicitado',
  PENDING: 'Pendiente',
  PARTIALLY_REFUNDED: 'Reintegrado parcialmente',
  INCIDENT: 'Con incidencia',
  RESOLVED: 'Cerrada con resolución',
  UNAVAILABLE: 'Sin disponibilidad',
  REJECTED: 'Rechazado',
  RETURN_REQUIRED: 'Retorno a farmacia pendiente',
  RETURNED: 'Retorno recibido',
  PHARMACY_ADMIN: 'Administrador de sucursal',
  DISPATCH: 'Salida física',
  REFUND: 'Reintegro',
  APPROVED: 'Aprobada',
  REVOKED: 'Revocada',
  EXPIRED: 'Vencida',
  CANCEL: 'Cancelación',
  DELIVERY: 'Entrega a domicilio',
  PICKUP: 'Retiro en farmacia',
};

export function label(value: string) {
  return labels[value] ?? value.replaceAll('_', ' ').toLowerCase();
}

export function dateTime(value: string): string {
  return new Intl.DateTimeFormat('es-AR', {
    timeZone: 'America/Argentina/Mendoza',
    dateStyle: 'short',
    timeStyle: 'medium',
    hourCycle: 'h23',
  }).format(new Date(value));
}
