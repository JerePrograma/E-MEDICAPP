import { useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, money } from '../api';
import type { Actor } from '../api';
import type { Branch, Product } from '../catalog/types';
import { DocumentInput } from '../components/DocumentInput';
import { LotsPanel } from './LotsPanel';
import type {
  BranchInput,
  Chain,
  ChainInput,
  Member,
  MemberInput,
  Overview,
  Plan,
  PlanInput,
  Settings,
  Subscription,
  SubscriptionInput,
  ProductInput,
} from './types';

type Editor =
  | { kind: 'products'; value?: Product }
  | { kind: 'chains'; value?: Chain }
  | { kind: 'branches'; value?: Branch }
  | { kind: 'plans'; value?: Plan }
  | { kind: 'subscriptions'; value?: Subscription }
  | { kind: 'memberships'; value?: Member };
const titles = {
  products: 'Producto',
  chains: 'Cadena',
  branches: 'Sucursal',
  plans: 'Plan mensual',
  subscriptions: 'Suscripción',
  memberships: 'Permiso de sucursal',
};
const subscriptionLabels: Record<string, string> = {
  DRAFT: 'Borrador',
  ACTIVE: 'Vigente',
  CANCEL_AT_PERIOD_END: 'Cancela al finalizar el período pagado',
  SUSPENDED: 'Suspendida para compras nuevas',
  CANCELLED: 'Cancelada',
};
const priorityLabels: Record<string, string> = {
  PAID: 'Prioridad por período pagado',
  GRACE: 'Gracia sin prioridad',
  SUSPENDED: 'Sin solicitudes nuevas',
  NONE: 'Fallback con acuerdo vigente',
};
const roleLabels: Record<string, string> = {
  PHARMACY: 'Operador de farmacia',
  PHARMACIST: 'Farmacéutico',
  DRIVER: 'Cadete propio',
};

export function AdminPage({ actor }: { actor: Actor }) {
  const cache = useQueryClient();
  const [tab, setTab] = useState(0);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const query = useQuery({
    queryKey: ['adminOverview'],
    queryFn: () => api<Overview>('/api/admin/overview'),
  });
  if (!actor.roles.includes('ADMIN'))
    return <Alert severity="error">Tu cuenta no tiene permiso de administración.</Alert>;
  if (query.isPending) return <Typography>Cargando administración…</Typography>;
  if (query.error || !query.data)
    return (
      <Stack spacing={2}>
        <Alert severity="error">
          {query.error?.message ?? 'No se pudo cargar la administración.'}
        </Alert>
        <Button onClick={() => void query.refetch()}>Volver a intentar</Button>
      </Stack>
    );
  const data = query.data;
  const branchName = (id: string | null) => data.branches.find((x) => x.id === id)?.name ?? '—';
  const chainName = (id: string | null) =>
    data.chains.find((x) => x.id === id)?.name ?? 'Sin cadena';
  const start = (value: Editor) => {
    setError('');
    setEditor(value);
  };
  async function save(body: unknown) {
    if (!editor) return;
    setBusy(true);
    setError('');
    try {
      await api(`/api/admin/${editor.kind}${editor.value ? `/${editor.value.id}` : ''}`, {
        method: editor.value ? 'PUT' : 'POST',
        body,
      });
      setNotice('Cambios guardados. Las operaciones ya aceptadas conservan sus condiciones.');
      setEditor(null);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ['adminOverview'] }),
        cache.invalidateQueries({ queryKey: ['products'] }),
        cache.invalidateQueries({ queryKey: ['branches'] }),
        cache.invalidateQueries({ queryKey: ['session'] }),
      ]);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'No se pudo guardar.');
    } finally {
      setBusy(false);
    }
  }
  const rows = (
    items: { id: string; title: string; detail: ReactNode; active?: boolean; onEdit: () => void }[],
  ) => (
    <Stack spacing={1.5}>
      {items.length === 0 && (
        <Alert severity="info">Todavía no hay registros. Agregá el primero para comenzar.</Alert>
      )}
      {items.map((item) => (
        <Card key={item.id}>
          <CardContent>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              sx={{ justifyContent: 'space-between' }}
              spacing={2}
            >
              <Box>
                <Typography sx={{ fontWeight: 650 }}>{item.title}</Typography>
                <Box sx={{ mt: 1 }}>{item.detail}</Box>
                {item.active === false && <Chip size="small" label="Inactivo" sx={{ mt: 1 }} />}
              </Box>
              <Button onClick={item.onEdit}>Modificar</Button>
            </Stack>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Administración</Typography>
      <Typography color="text.secondary">
        Configurá la red y el negocio. Los cambios tienen responsable y versión; los pagos y
        presupuestos anteriores se conservan.
      </Typography>
      {notice && (
        <Alert severity="success" onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        scrollButtons="auto"
      >
        {[
          'Productos',
          'Cadenas',
          'Sucursales',
          'Planes',
          'Suscripciones',
          'Miembros',
          'Configuración',
          'Lotes',
        ].map((title) => (
          <Tab key={title} label={title} />
        ))}
      </Tabs>
      {tab === 0 && (
        <>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'products' })}
          >
            Agregar producto
          </Button>
          {rows(
            data.products.map((x) => ({
              id: x.id,
              title: `${x.name} · ${x.presentation}`,
              active: x.active,
              detail: (
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <Chip
                    size="small"
                    label={x.requiresPrescription ? 'Con receta' : 'Venta sin receta'}
                  />
                  {x.coldChain && <Chip size="small" label="Cadena de frío" />}
                  {x.restricted && <Chip size="small" label="Control especial" />}
                </Stack>
              ),
              onEdit: () => start({ kind: 'products', value: x }),
            })),
          )}
        </>
      )}
      {tab === 1 && (
        <>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'chains' })}
          >
            Agregar cadena
          </Button>
          {rows(
            data.chains.map((x) => ({
              id: x.id,
              title: x.name,
              active: x.active,
              detail: (
                <Typography variant="body2">
                  {data.branches.filter((b) => b.chainId === x.id).length} establecimientos
                  registrados
                </Typography>
              ),
              onEdit: () => start({ kind: 'chains', value: x }),
            })),
          )}
        </>
      )}
      {tab === 2 && (
        <>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'branches' })}
          >
            Agregar sucursal
          </Button>
          {rows(
            data.branches.map((x) => ({
              id: x.id,
              title: x.name,
              active: x.active,
              detail: (
                <Stack spacing={1}>
                  <Typography variant="body2">
                    {chainName(x.chainId)} · {x.address}
                  </Typography>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    <Chip
                      size="small"
                      label={x.verified ? 'Verificada' : 'Verificación pendiente'}
                    />
                    <Chip
                      size="small"
                      color={x.subscriptionEligible ? 'success' : 'default'}
                      label={priorityLabels[x.subscriptionState] ?? x.subscriptionState}
                    />
                    <Chip
                      size="small"
                      label={`${x.deliveryEnabled ? 'Entrega' : ''}${x.deliveryEnabled && x.pickupEnabled ? ' y ' : ''}${x.pickupEnabled ? 'retiro' : ''}`}
                    />
                  </Stack>
                </Stack>
              ),
              onEdit: () => start({ kind: 'branches', value: x }),
            })),
          )}
        </>
      )}
      {tab === 3 && (
        <>
          <Alert severity="info">
            Las U.M. describen niveles simbólicos. Para cobrar se requiere un importe contractual
            positivo y una moneda real.
          </Alert>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'plans' })}
          >
            Agregar plan mensual
          </Button>
          {rows(
            data.plans.map((x) => ({
              id: x.id,
              title: x.name,
              active: x.active,
              detail: (
                <Typography variant="body2">
                  {x.minBranches} a {x.maxBranches ?? 'más'} establecimientos ·{' '}
                  {x.symbolicUnits ?? 'A cotizar'} U.M. · Cuota real:{' '}
                  {money(x.monthlyPrice, x.currency ?? data.config.currency)}
                </Typography>
              ),
              onEdit: () => start({ kind: 'plans', value: x }),
            })),
          )}
        </>
      )}
      {tab === 4 && (
        <>
          <Alert severity="info">
            La prioridad se concede por un período pagado y verificado. La gracia permite fallback
            sin prioridad. La cancelación conserva el período ya abonado.
          </Alert>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'subscriptions' })}
          >
            Agregar suscripción
          </Button>
          {rows(
            data.subscriptions.map((x) => ({
              id: x.id,
              title: x.chainId ? chainName(x.chainId) : branchName(x.branchId),
              detail: (
                <Stack spacing={1}>
                  <Typography variant="body2">
                    {data.plans.find((p) => p.id === x.planId)?.name ?? 'Plan'} ·{' '}
                    {money(x.monthlyPrice, x.currency ?? data.config.currency)}
                  </Typography>
                  <Typography variant="body2">
                    {subscriptionLabels[x.status] ?? x.status} ·{' '}
                    {x.branchIds.map(branchName).join(', ')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {x.paidThroughAt
                      ? `Período pagado hasta ${new Date(x.paidThroughAt).toLocaleString('es-AR')}`
                      : 'Sin período pagado verificado'}
                  </Typography>
                </Stack>
              ),
              onEdit: () => start({ kind: 'subscriptions', value: x }),
            })),
          )}
        </>
      )}
      {tab === 5 && (
        <>
          <Button
            variant="contained"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => start({ kind: 'memberships' })}
          >
            Asignar permiso
          </Button>
          {rows(
            data.memberships.map((x) => ({
              id: x.id,
              title: x.userName,
              active: x.active,
              detail: (
                <Stack spacing={1}>
                  <Typography variant="body2">
                    {roleLabels[x.role] ?? x.role} · {branchName(x.branchId)} · {x.email}
                  </Typography>
                  {x.role === 'DRIVER' && (
                    <Typography variant="caption">
                      {x.coldChainCapable ? 'Cadena de frío acreditada. ' : ''}
                      {x.restrictedCapable ? 'Transporte de restringidos acreditado. ' : ''}
                      {x.capabilityVerifiedAt
                        ? `Verificado el ${new Date(x.capabilityVerifiedAt).toLocaleString('es-AR')}`
                        : 'Sin capacidades especiales acreditadas.'}
                    </Typography>
                  )}
                </Stack>
              ),
              onEdit: () => start({ kind: 'memberships', value: x }),
            })),
          )}
        </>
      )}
      {tab === 6 && (
        <ConfigForm
          key={data.config.version}
          value={data.config}
          onSaved={async () => {
            setNotice('Configuración publicada para las próximas operaciones.');
            await cache.invalidateQueries({ queryKey: ['adminOverview'] });
            await cache.invalidateQueries({ queryKey: ['bootstrap'] });
          }}
        />
      )}
      {tab === 7 && <LotsPanel branches={data.branches} products={data.products} />}
      <Dialog
        open={!!editor}
        onClose={() => {
          if (!busy) setEditor(null);
        }}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>
          {editor?.value ? 'Modificar' : 'Agregar'}{' '}
          {editor ? titles[editor.kind].toLocaleLowerCase('es-AR') : ''}
        </DialogTitle>
        <DialogContent>
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
          {editor?.kind === 'products' && (
            <ProductForm value={editor.value} busy={busy} save={save} />
          )}
          {editor?.kind === 'chains' && <ChainForm value={editor.value} busy={busy} save={save} />}
          {editor?.kind === 'branches' && (
            <BranchForm value={editor.value} data={data} busy={busy} save={save} />
          )}
          {editor?.kind === 'plans' && (
            <PlanForm
              value={editor.value}
              currency={data.config.currency}
              busy={busy}
              save={save}
            />
          )}
          {editor?.kind === 'subscriptions' && (
            <SubscriptionForm value={editor.value} data={data} busy={busy} save={save} />
          )}
          {editor?.kind === 'memberships' && (
            <MemberForm value={editor.value} data={data} busy={busy} save={save} />
          )}
          <Button sx={{ mt: 1 }} disabled={busy} onClick={() => setEditor(null)}>
            Cerrar
          </Button>
        </DialogContent>
      </Dialog>
    </Stack>
  );
}

function Form({
  busy,
  submit,
  children,
}: {
  busy: boolean;
  submit: () => void;
  children: ReactNode;
}) {
  return (
    <Stack
      component="form"
      spacing={2}
      sx={{ pt: 1 }}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      {children}
      <Button variant="contained" type="submit" disabled={busy}>
        {busy ? 'Guardando…' : 'Guardar cambios'}
      </Button>
    </Stack>
  );
}
function Field({
  label,
  value,
  set,
  required = false,
  type = 'text',
  help,
}: {
  label: string;
  value: string;
  set: (value: string) => void;
  required?: boolean;
  type?: string;
  help?: string;
}) {
  return (
    <TextField
      fullWidth
      label={label}
      value={value}
      required={required}
      type={type}
      onChange={(e) => set(e.target.value)}
      helperText={help}
      slotProps={
        type === 'date' || type === 'datetime-local' ? { inputLabel: { shrink: true } } : undefined
      }
    />
  );
}
function Toggle({
  label,
  value,
  set,
  disabled = false,
}: {
  label: string;
  value: boolean;
  set: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <FormControlLabel
      control={
        <Checkbox checked={value} disabled={disabled} onChange={(e) => set(e.target.checked)} />
      }
      label={label}
    />
  );
}
function Choice({
  label,
  value,
  set,
  options,
  required = false,
}: {
  label: string;
  value: string;
  set: (value: string) => void;
  options: { id: string; name: string }[];
  required?: boolean;
}) {
  return (
    <TextField
      fullWidth
      select
      required={required}
      label={label}
      value={value}
      onChange={(e) => set(e.target.value)}
    >
      <MenuItem value="">Seleccionar</MenuItem>
      {options.map((x) => (
        <MenuItem key={x.id} value={x.id}>
          {x.name}
        </MenuItem>
      ))}
    </TextField>
  );
}

function ProductForm({
  value,
  busy,
  save,
}: {
  value?: Product;
  busy: boolean;
  save: (body: ProductInput) => Promise<void>;
}) {
  const [draft, set] = useState<ProductInput>(
    value ?? {
      code: '',
      name: '',
      presentation: '',
      requiresPrescription: false,
      coldChain: false,
      restricted: false,
      active: true,
      version: 0,
      metadata: {},
    },
  );
  const [minimum, setMinimum] = useState(String(value?.metadata.minTemperatureC ?? ''));
  const [maximum, setMaximum] = useState(String(value?.metadata.maxTemperatureC ?? ''));
  const patch = (part: Partial<ProductInput>) => set((previous) => ({ ...previous, ...part }));
  return (
    <Form
      busy={busy}
      submit={() => {
        const metadata = { ...draft.metadata };
        if (minimum !== '') metadata.minTemperatureC = Number(minimum);
        else delete metadata.minTemperatureC;
        if (maximum !== '') metadata.maxTemperatureC = Number(maximum);
        else delete metadata.maxTemperatureC;
        void save({ ...draft, metadata });
      }}
    >
      <Field label="Código comercial" value={draft.code} set={(code) => patch({ code })} required />
      <Field label="Nombre" value={draft.name} set={(name) => patch({ name })} required />
      <Field
        label="Presentación vendible"
        value={draft.presentation}
        set={(presentation) => patch({ presentation })}
        required
        help="Ejemplo: caja de 30 comprimidos. El precio y stock se cargan por sucursal."
      />
      <Toggle
        label="Requiere receta"
        value={draft.requiresPrescription}
        set={(requiresPrescription) => patch({ requiresPrescription })}
      />
      <Toggle
        label="Requiere cadena de frío"
        value={draft.coldChain}
        set={(coldChain) => patch({ coldChain })}
      />
      <Toggle
        label="Requiere controles especiales"
        value={draft.restricted}
        set={(restricted) => patch({ restricted })}
      />
      {draft.coldChain && (
        <>
          <Field
            label="Temperatura mínima permitida (°C)"
            type="number"
            value={minimum}
            set={setMinimum}
            required
          />
          <Field
            label="Temperatura máxima permitida (°C)"
            type="number"
            value={maximum}
            set={setMaximum}
            required
          />
          <Typography variant="caption">
            Usá los límites comprobados de la presentación. La farmacia debe aportar evidencia de
            conservación.
          </Typography>
        </>
      )}
      <Toggle
        label="Activo para nuevas solicitudes"
        value={draft.active}
        set={(active) => patch({ active })}
      />
    </Form>
  );
}
function ChainForm({
  value,
  busy,
  save,
}: {
  value?: Chain;
  busy: boolean;
  save: (body: ChainInput) => Promise<void>;
}) {
  const [name, setName] = useState(value?.name ?? '');
  const [active, setActive] = useState(value?.active ?? true);
  return (
    <Form busy={busy} submit={() => void save({ name, active, version: value?.version ?? 0 })}>
      <Field label="Nombre de la cadena" value={name} set={setName} required />
      <Toggle label="Activa" value={active} set={setActive} />
    </Form>
  );
}
function BranchForm({
  value,
  data,
  busy,
  save,
}: {
  value?: Branch;
  data: Overview;
  busy: boolean;
  save: (body: BranchInput) => Promise<void>;
}) {
  const [draft, set] = useState<BranchInput>(
    value ?? {
      chainId: null,
      name: '',
      address: '',
      latitude: '',
      longitude: '',
      active: true,
      verified: false,
      agreementAccepted: false,
      deliveryEnabled: true,
      pickupEnabled: true,
      coldChainCapable: false,
      restrictedCapable: false,
      driverAvailable: false,
      merchantName: '',
      paymentInstructions: '',
      deliveryRadiusKm: '20.00',
      opensAt: null,
      closesAt: null,
      capabilities: {},
      version: 0,
    },
  );
  const [evidence, setEvidence] = useState(
    typeof value?.capabilities.capabilityEvidenceDocumentId === 'string'
      ? value.capabilities.capabilityEvidenceDocumentId
      : '',
  );
  const patch = (part: Partial<BranchInput>) => set((previous) => ({ ...previous, ...part }));
  return (
    <Form
      busy={busy}
      submit={() =>
        void save({
          ...draft,
          capabilities: {
            ...draft.capabilities,
            ...(evidence ? { capabilityEvidenceDocumentId: evidence } : {}),
          },
        })
      }
    >
      <Choice
        label="Cadena (opcional)"
        value={draft.chainId ?? ''}
        set={(chainId) => patch({ chainId: chainId || null })}
        options={data.chains.map((x) => ({ id: x.id, name: x.name }))}
      />
      <Field
        label="Nombre de la sucursal"
        value={draft.name}
        set={(name) => patch({ name })}
        required
      />
      <Field
        label="Domicilio"
        value={draft.address}
        set={(address) => patch({ address })}
        required
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Field
          label="Latitud"
          value={draft.latitude}
          set={(latitude) => patch({ latitude })}
          required
        />
        <Field
          label="Longitud"
          value={draft.longitude}
          set={(longitude) => patch({ longitude })}
          required
        />
      </Stack>
      <Field
        label="Farmacia que cobra y factura"
        value={draft.merchantName}
        set={(merchantName) => patch({ merchantName })}
        required
      />
      <Field
        label="Instrucciones de pago directo"
        value={draft.paymentInstructions}
        set={(paymentInstructions) => patch({ paymentInstructions })}
        help="Las recibe el comprador en el circuito de compra. No se muestran en el catálogo público."
      />
      <Field
        label="Radio de entrega (km)"
        value={draft.deliveryRadiusKm}
        set={(deliveryRadiusKm) => patch({ deliveryRadiusKm })}
        required
        help="Distancia en línea recta desde esta sucursal. La farmacia confirma la cobertura real de la entrega."
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Field
          label="Apertura (hora de Mendoza)"
          type="time"
          value={draft.opensAt ?? ''}
          set={(opensAt) => patch({ opensAt: opensAt || null })}
        />
        <Field
          label="Cierre (hora de Mendoza)"
          type="time"
          value={draft.closesAt ?? ''}
          set={(closesAt) => patch({ closesAt: closesAt || null })}
        />
      </Stack>
      <Typography variant="caption" color="text.secondary">
        El horario se repite diariamente y permite cruzar medianoche. Dejá ambos campos vacíos para
        controlar la disponibilidad manualmente. No incluye feriados ni polígonos de reparto.
      </Typography>
      <Toggle
        label="Activa para nuevas solicitudes"
        value={draft.active}
        set={(active) => patch({ active })}
      />
      <Toggle
        label="Habilitación y datos verificados"
        value={draft.verified}
        set={(verified) => patch({ verified })}
      />
      <Toggle
        label="Acuerdo de adhesión aceptado"
        value={draft.agreementAccepted}
        set={(agreementAccepted) => patch({ agreementAccepted })}
      />
      <Toggle
        label="Ofrece entrega a domicilio"
        value={draft.deliveryEnabled}
        set={(deliveryEnabled) => patch({ deliveryEnabled })}
      />
      <Toggle
        label="Ofrece retiro en sucursal"
        value={draft.pickupEnabled}
        set={(pickupEnabled) => patch({ pickupEnabled })}
      />
      <Toggle
        label="Tiene cadete propio disponible"
        value={draft.driverAvailable}
        set={(driverAvailable) => patch({ driverAvailable })}
      />
      <Toggle
        label="Capacidad comprobada para cadena de frío"
        value={draft.coldChainCapable}
        set={(coldChainCapable) => patch({ coldChainCapable })}
        disabled={!value}
      />
      <Toggle
        label="Capacidad comprobada para controles especiales"
        value={draft.restrictedCapable}
        set={(restrictedCapable) => patch({ restrictedCapable })}
        disabled={!value}
      />
      {value ? (
        <DocumentInput
          label="Evidencia de habilitación y capacidad"
          kind="CAPACIDAD"
          branchId={value.id}
          value={evidence}
          onChange={setEvidence}
        />
      ) : (
        <Alert severity="info">
          Guardá la sucursal para adjuntar su evidencia y habilitar capacidades especiales.
        </Alert>
      )}
      {(draft.coldChainCapable || draft.restrictedCapable) && (
        <Alert severity="info">
          La evidencia se verifica al guardar y conserva quién confirmó la capacidad. La
          habilitación requiere los controles aplicables a cada medicamento.
        </Alert>
      )}
    </Form>
  );
}
function PlanForm({
  value,
  currency,
  busy,
  save,
}: {
  value?: Plan;
  currency: string;
  busy: boolean;
  save: (body: PlanInput) => Promise<void>;
}) {
  const [draft, set] = useState<PlanInput>(
    value ?? {
      name: '',
      minBranches: 1,
      maxBranches: 1,
      symbolicUnits: null,
      monthlyPrice: null,
      currency: null,
      active: true,
      version: 0,
    },
  );
  const patch = (part: Partial<PlanInput>) => set((previous) => ({ ...previous, ...part }));
  return (
    <Form
      busy={busy}
      submit={() =>
        void save({ ...draft, currency: draft.monthlyPrice ? (draft.currency ?? currency) : null })
      }
    >
      <Field label="Nombre del nivel" value={draft.name} set={(name) => patch({ name })} required />
      <Field
        label="Mínimo de establecimientos"
        type="number"
        value={String(draft.minBranches)}
        set={(minBranches) => patch({ minBranches: Number(minBranches) })}
        required
      />
      <Field
        label="Máximo de establecimientos"
        type="number"
        value={draft.maxBranches === null ? '' : String(draft.maxBranches)}
        set={(maxBranches) =>
          patch({ maxBranches: maxBranches === '' ? null : Number(maxBranches) })
        }
        help="Dejalo vacío para un nivel abierto a cotización."
      />
      <Field
        label="Unidades simbólicas mensuales (U.M.)"
        value={draft.symbolicUnits ?? ''}
        set={(symbolicUnits) => patch({ symbolicUnits: symbolicUnits || null })}
        help="No son dinero ni habilitan el cobro."
      />
      <Field
        label="Cuota real mensual"
        value={draft.monthlyPrice ?? ''}
        set={(monthlyPrice) =>
          patch({
            monthlyPrice: monthlyPrice || null,
            currency: monthlyPrice ? (draft.currency ?? currency) : null,
          })
        }
        help="Dejala vacía mientras no haya precio comercial definido."
      />
      {draft.monthlyPrice && (
        <Field
          label="Moneda real (código de tres letras)"
          value={draft.currency ?? currency}
          set={(currencyValue) => patch({ currency: currencyValue.toUpperCase() })}
          required
        />
      )}
      <Toggle label="Plan activo" value={draft.active} set={(active) => patch({ active })} />
    </Form>
  );
}

function utcFromLocal(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}
function nextMonthLocal(value: string): string {
  if (!value) return '';
  const d = new Date(value);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + 1);
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  d.setDate(Math.min(day, last));
  const offset = d.getTimezoneOffset() * 60000;
  return new Date(d.getTime() - offset).toISOString().slice(0, 16);
}
function SubscriptionForm({
  value,
  data,
  busy,
  save,
}: {
  value?: Subscription;
  data: Overview;
  busy: boolean;
  save: (body: SubscriptionInput) => Promise<void>;
}) {
  const [scope, setScope] = useState<'CHAIN' | 'BRANCH'>(value?.chainId ? 'CHAIN' : 'BRANCH');
  const [scopeId, setScopeId] = useState(value?.chainId ?? value?.branchId ?? '');
  const [planId, setPlan] = useState(value?.planId ?? '');
  const [branchIds, setBranches] = useState<string[]>(value?.branchIds ?? []);
  const [price, setPrice] = useState(value?.monthlyPrice ?? '');
  const [currency, setCurrency] = useState(value?.currency ?? data.config.currency);
  const [status, setStatus] = useState(value?.status ?? 'DRAFT');
  const [recordPayment, setRecordPayment] = useState(false);
  const [starts, setStarts] = useState('');
  const [ends, setEnds] = useState('');
  const [reference, setReference] = useState('');
  const [evidence, setEvidence] = useState('');
  const branches = data.branches.filter((x) =>
    scope === 'CHAIN' ? x.chainId === scopeId : x.id === scopeId,
  );
  return (
    <Form
      busy={busy}
      submit={() =>
        void save({
          planId,
          chainId: scope === 'CHAIN' ? scopeId : null,
          branchId: scope === 'BRANCH' ? scopeId : null,
          branchIds,
          monthlyPrice: price || null,
          currency: price ? currency : null,
          status,
          version: value?.version ?? 0,
          startsAt: value?.startsAt ?? null,
          periodStartsAt: recordPayment ? utcFromLocal(starts) : null,
          periodEndsAt: recordPayment ? utcFromLocal(ends) : null,
          paymentReference: recordPayment ? reference : null,
          evidenceDocumentId: recordPayment ? evidence : null,
        })
      }
    >
      <Choice
        label="Contratante"
        value={scope}
        set={(v) => {
          setScope(v === 'CHAIN' ? 'CHAIN' : 'BRANCH');
          setScopeId('');
          setBranches([]);
        }}
        required
        options={[
          { id: 'BRANCH', name: 'Una farmacia' },
          { id: 'CHAIN', name: 'Una cadena' },
        ]}
      />
      <Choice
        label={scope === 'CHAIN' ? 'Cadena' : 'Farmacia'}
        value={scopeId}
        set={(id) => {
          setScopeId(id);
          setBranches(scope === 'BRANCH' ? [id] : []);
        }}
        required
        options={(scope === 'CHAIN' ? data.chains : data.branches).map((x) => ({
          id: x.id,
          name: x.name,
        }))}
      />
      {scope === 'CHAIN' && (
        <Box>
          <Typography variant="body2">Establecimientos incluidos</Typography>
          {branches.map((branch) => (
            <Toggle
              key={branch.id}
              label={branch.name}
              value={branchIds.includes(branch.id)}
              set={(checked) =>
                setBranches((previous) =>
                  checked ? [...previous, branch.id] : previous.filter((id) => id !== branch.id),
                )
              }
            />
          ))}
        </Box>
      )}
      <Choice
        label="Nivel mensual"
        value={planId}
        set={setPlan}
        required
        options={data.plans
          .filter((p) => p.active || p.id === value?.planId)
          .map((p) => ({ id: p.id, name: `${p.name} · ${p.symbolicUnits ?? 'A cotizar'} U.M.` }))}
      />
      <Field
        label="Cuota contractual real mensual"
        value={price}
        set={setPrice}
        help="Importe real con punto decimal. U.M. y cuota real son valores separados."
      />
      <Field
        label="Moneda contractual"
        value={currency}
        set={(v) => setCurrency(v.toUpperCase())}
        required={!!price}
      />
      <Choice
        label="Estado del contrato"
        value={status}
        set={setStatus}
        required
        options={Object.entries(subscriptionLabels).map(([id, name]) => ({ id, name }))}
      />
      <Toggle
        label="Registrar un período cobrado y verificado"
        value={recordPayment}
        set={setRecordPayment}
      />
      {recordPayment && (
        <>
          <Alert severity="warning">
            Confirmá el abono real antes de acreditar el período. Adjuntar un comprobante por sí
            solo no acredita el pago.
          </Alert>
          <Field
            label="Inicio del período"
            type="datetime-local"
            value={starts}
            set={(v) => {
              setStarts(v);
              setEnds(nextMonthLocal(v));
            }}
            required
          />
          <Field
            label="Fin del período mensual"
            type="datetime-local"
            value={ends}
            set={setEnds}
            required
          />
          <Field
            label="Referencia del cobro verificado"
            value={reference}
            set={setReference}
            required
          />
          <DocumentInput
            label="Comprobante verificado de suscripción"
            kind="EVIDENCIA"
            value={evidence}
            onChange={setEvidence}
          />
        </>
      )}
      {value?.paidThroughAt && (
        <Typography variant="caption">
          Los establecimientos y el contratante se conservan hasta finalizar el período ya abonado.
          La nueva cuota se aplica a períodos futuros.
        </Typography>
      )}
    </Form>
  );
}
function MemberForm({
  value,
  data,
  busy,
  save,
}: {
  value?: Member;
  data: Overview;
  busy: boolean;
  save: (body: MemberInput) => Promise<void>;
}) {
  const [draft, set] = useState<MemberInput>(
    value ?? {
      userId: '',
      branchId: '',
      role: 'PHARMACY',
      active: true,
      coldChainCapable: false,
      restrictedCapable: false,
      capabilityEvidenceDocumentId: null,
      version: 0,
    },
  );
  const patch = (part: Partial<MemberInput>) => set((previous) => ({ ...previous, ...part }));
  const resetScope = {
    coldChainCapable: false,
    restrictedCapable: false,
    capabilityEvidenceDocumentId: null,
  };
  return (
    <Form busy={busy} submit={() => void save(draft)}>
      <Choice
        label="Persona"
        value={draft.userId}
        set={(userId) => patch({ userId, ...resetScope })}
        required
        options={data.users.map((x) => ({ id: x.id, name: `${x.name} · ${x.email}` }))}
      />
      <Choice
        label="Sucursal autorizada"
        value={draft.branchId}
        set={(branchId) => patch({ branchId, ...resetScope })}
        required
        options={data.branches.map((x) => ({ id: x.id, name: x.name }))}
      />
      <Choice
        label="Permiso"
        value={draft.role}
        set={(role) => patch({ role, ...resetScope })}
        required
        options={Object.entries(roleLabels).map(([id, name]) => ({ id, name }))}
      />
      <Toggle label="Permiso activo" value={draft.active} set={(active) => patch({ active })} />
      {draft.role === 'DRIVER' && (
        <>
          <Alert severity="info">
            Acreditá la formación, el equipamiento y las condiciones de transporte de esta persona
            para la sucursal seleccionada. Guardar deja tu verificación y el documento en la
            auditoría.
          </Alert>
          <Toggle
            label="Acreditar transporte con cadena de frío"
            value={draft.coldChainCapable}
            set={(coldChainCapable) => patch({ coldChainCapable })}
          />
          <Toggle
            label="Acreditar transporte de medicamentos restringidos"
            value={draft.restrictedCapable}
            set={(restrictedCapable) => patch({ restrictedCapable })}
          />
          {draft.branchId && (
            <DocumentInput
              key={draft.branchId + draft.userId}
              label="Evidencia de capacidad del cadete"
              kind="CAPACIDAD"
              branchId={draft.branchId}
              value={draft.capabilityEvidenceDocumentId ?? undefined}
              onChange={(capabilityEvidenceDocumentId) => patch({ capabilityEvidenceDocumentId })}
            />
          )}
          {(draft.coldChainCapable || draft.restrictedCapable) &&
            !draft.capabilityEvidenceDocumentId && (
              <Alert severity="warning">
                Adjuntá evidencia antes de acreditar las capacidades especiales.
              </Alert>
            )}
          {value?.capabilityVerifiedAt && (
            <Typography variant="caption">
              Última acreditación: {new Date(value.capabilityVerifiedAt).toLocaleString('es-AR')}.
              La próxima modificación registrará al administrador que la verifica.
            </Typography>
          )}
        </>
      )}
      <Typography variant="caption">
        Una persona puede tener varios permisos. Cada rol se comprueba para la sucursal concreta.
      </Typography>
    </Form>
  );
}
function ConfigForm({ value, onSaved }: { value: Settings; onSaved: () => Promise<void> }) {
  const [draft, set] = useState(value);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const patch = (part: Partial<Settings>) => set((previous) => ({ ...previous, ...part }));
  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 2 }}>
          Configuración operativa
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Form
          busy={busy}
          submit={() =>
            void (async () => {
              setBusy(true);
              setError('');
              try {
                await api('/api/admin/settings', { method: 'PUT', body: draft });
                await onSaved();
              } catch (failure) {
                setError(failure instanceof Error ? failure.message : 'No se pudo publicar.');
              } finally {
                setBusy(false);
              }
            })()
          }
        >
          <Alert severity="info">
            Publicar aplica a operaciones nuevas. Una compra aceptada conserva sus valores. Un
            cambio de moneda exige revisar explícitamente los precios; no realiza conversión
            automática.
          </Alert>
          <Field
            label="Moneda para nuevas operaciones"
            value={draft.currency}
            set={(currency) => patch({ currency: currency.toUpperCase() })}
            required
          />
          <Field
            label="Vigencia de presupuestos (minutos)"
            type="number"
            value={String(draft.quoteMinutes)}
            set={(v) => patch({ quoteMinutes: Number(v) })}
            required
          />
          <Field
            label="Reserva temporal de stock (minutos)"
            type="number"
            value={String(draft.reservationMinutes)}
            set={(v) => patch({ reservationMinutes: Number(v) })}
            required
          />
          <Field
            label="Gracia de suscripción sin prioridad (días)"
            type="number"
            value={String(draft.graceDays)}
            set={(v) => patch({ graceDays: Number(v) })}
            required
          />
          <Field
            label={`Umbral de doble revisión (${draft.currency})`}
            value={draft.highValueThreshold}
            set={(highValueThreshold) => patch({ highValueThreshold })}
            required
            help="No limita el derecho ni el importe de reintegro."
          />
          <Field
            label="Límite de archivo privado (MB)"
            type="number"
            value={String(draft.maxUploadMb)}
            set={(v) => patch({ maxUploadMb: Number(v) })}
            required
          />
          <Toggle
            label="Entrega a domicilio habilitada"
            value={draft.deliveryEnabled}
            set={(deliveryEnabled) => patch({ deliveryEnabled })}
          />
          <Toggle
            label="Retiro en farmacia habilitado"
            value={draft.pickupEnabled}
            set={(pickupEnabled) => patch({ pickupEnabled })}
          />
          <Toggle
            label="Fallback de farmacias verificadas con acuerdo"
            value={draft.fallbackEnabled}
            set={(fallbackEnabled) => patch({ fallbackEnabled })}
          />
          <Field
            label="Mensaje cuando no hay disponibilidad"
            value={typeof draft.messages.unavailable === 'string' ? draft.messages.unavailable : ''}
            set={(unavailable) => patch({ messages: { ...draft.messages, unavailable } })}
          />
          <Field
            label="Mensaje de confirmación de propuesta"
            value={
              typeof draft.messages.quoteConsent === 'string' ? draft.messages.quoteConsent : ''
            }
            set={(quoteConsent) => patch({ messages: { ...draft.messages, quoteConsent } })}
          />
          <Field
            label="Mensaje de pago pendiente"
            value={
              typeof draft.messages.paymentPending === 'string' ? draft.messages.paymentPending : ''
            }
            set={(paymentPending) => patch({ messages: { ...draft.messages, paymentPending } })}
          />
        </Form>
      </CardContent>
    </Card>
  );
}
