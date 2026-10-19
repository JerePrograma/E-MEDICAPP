import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  Step,
  StepLabel,
  Stepper,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Actor, api, Bootstrap } from '../api';
import { Branch, Product } from '../catalog/types';
import { DocumentInput } from '../components/DocumentInput';
import { LocationPicker, Point } from '../components/LocationPicker';
import { PageHeader } from '../components/PageElements';
import { filterProducts } from './productSearch';

function adultBirthLimit() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Argentina/Mendoza',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const value = (type: string) => Number(parts.find((x) => x.type === type)?.value);
  const year = value('year') - 18;
  const month = value('month');
  const day = Math.min(value('day'), new Date(Date.UTC(year, month, 0)).getUTCDate());
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function CreatePurchase({
  actor,
  onCreated,
}: {
  actor: Actor;
  onCreated: (id: string) => void;
}) {
  const [items, setItems] = useState<Record<string, number>>({});
  const [search, setSearch] = useState('');
  const [mode, setMode] = useState('DELIVERY');
  const [point, setPoint] = useState<Point | null>(null);
  const [address, setAddress] = useState(actor.address ?? '');
  const [step, setStep] = useState(0);
  const [sameReceiver, setSameReceiver] = useState(true);
  const [receiverName, setReceiverName] = useState('');
  const [receiverIdentity, setReceiverIdentity] = useState('');
  const [branch, setBranch] = useState('');
  const [thirdParty, setThirdParty] = useState(false);
  const [name, setName] = useState(actor.name);
  const ownBirthDate = actor.birthDate ?? '';
  const [birthDate, setBirthDate] = useState(ownBirthDate);
  const [beneficiaryIdentity, setBeneficiaryIdentity] = useState(actor.dni ?? '');
  const [authorization, setAuthorization] = useState('');
  const [prescription, setPrescription] = useState('');
  const [coverageRequested, setCoverageRequested] = useState(false);
  const [coverageProvider, setCoverageProvider] = useState('');
  const [coveragePlan, setCoveragePlan] = useState('');
  const [coverageMemberNumber, setCoverageMemberNumber] = useState('');
  const [coverageDocument, setCoverageDocument] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const noticeElement = useRef<HTMLDivElement>(null);
  const birthLimit = adultBirthLimit();
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api<Product[]>('/api/catalog/products'),
  });
  const branches = useQuery({
    queryKey: ['branches'],
    queryFn: () => api<Branch[]>('/api/catalog/branches'),
  });
  const bootstrap = useQuery({
    queryKey: ['bootstrap'],
    queryFn: () => api<Bootstrap>('/api/bootstrap'),
  });
  const deliveryEnabled = bootstrap.data?.features?.delivery !== false;
  const pickupEnabled = bootstrap.data?.features?.pickup !== false;
  useEffect(() => {
    if (mode === 'DELIVERY' && !deliveryEnabled) {
      setMode('PICKUP');
      setPoint(null);
    } else if (mode === 'PICKUP' && !pickupEnabled) {
      setMode('DELIVERY');
      setPoint(null);
    }
  }, [mode, deliveryEnabled, pickupEnabled]);
  const selected = products.data?.filter((x) => (items[x.id] ?? 0) > 0) ?? [];
  const visibleProducts = filterProducts(products.data ?? [], search);
  const requiresPrescription = selected.some((x) => x.requiresPrescription);
  function updateQuantity(product: Product, quantity: number) {
    const nextQuantity = Number.isFinite(quantity)
      ? Math.max(0, Math.min(100, Math.trunc(quantity)))
      : 0;
    if (nextQuantity > 0 && !items[product.id] && selected.length >= 30) {
      setNotice(
        'Podés agregar hasta treinta presentaciones diferentes. Quitá una para agregar otra.',
      );
      return;
    }
    setItems((current) => ({ ...current, [product.id]: nextQuantity }));
    setNotice('');
  }
  useEffect(() => {
    if (notice) noticeElement.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [notice]);
  async function submit() {
    setNotice('');
    if (!name.trim() || !birthDate || birthDate > birthLimit) {
      setNotice(
        'Indicá el nombre completo y la fecha de nacimiento de una persona de 18 años o más.',
      );
      return;
    }
    if (thirdParty && !/^\d{7,12}$/.test(beneficiaryIdentity.replace(/[.\s]/g, ''))) {
      setNotice('Indicá el DNI del familiar adulto beneficiario.');
      return;
    }
    if ((thirdParty || !sameReceiver) && !authorization) {
      setNotice(
        'Adjuntá una nota firmada para esta compra, autorizando a comprar y recibir los medicamentos.',
      );
      return;
    }
    if (
      !sameReceiver &&
      (!receiverName.trim() || !/^\d{7,12}$/.test(receiverIdentity.replace(/[.\s]/g, '')))
    ) {
      setNotice('Indicá el nombre y el DNI de la persona autorizada a recibir.');
      return;
    }
    if (mode === 'DELIVERY' && address.trim().length < 5) {
      setNotice('Completá calle, número y localidad del domicilio de entrega.');
      return;
    }
    if (!point) {
      setNotice('Confirmá la ubicación en el mapa.');
      return;
    }
    if (selected.length === 0 || selected.length > 30) {
      setNotice('Agregá entre una y treinta presentaciones diferentes a la solicitud.');
      return;
    }
    if (requiresPrescription && !prescription) {
      setNotice(
        'La compra incluye medicamentos con receta. Adjuntá el original para que lo revise la farmacia.',
      );
      return;
    }
    if (
      coverageRequested &&
      (!coverageProvider.trim() || (!coverageMemberNumber.trim() && !coverageDocument))
    ) {
      setNotice(
        'Para solicitar cobertura, indicá la obra social o prepaga y el número de afiliado, o adjuntá la credencial del beneficiario.',
      );
      return;
    }
    setBusy(true);
    try {
      const result = await api<{ id: string; message?: string }>('/api/purchases', {
        method: 'POST',
        body: {
          beneficiaryName: name.trim(),
          beneficiaryBirthDate: birthDate,
          ...(thirdParty ? { beneficiaryIdentity: beneficiaryIdentity.replace(/[.\s]/g, '') } : {}),
          thirdParty,
          ...(!sameReceiver
            ? {
                receiverName: receiverName.trim(),
                receiverIdentity: receiverIdentity.replace(/[.\s]/g, ''),
              }
            : {}),
          authorizationDocumentId: authorization || null,
          mode,
          destinationLat: point.lat,
          destinationLon: point.lon,
          destinationAddress: mode === 'DELIVERY' ? address.trim() : '',
          preferredBranchId: branch || null,
          prescriptionDocumentId: prescription || null,
          ...(coverageRequested
            ? {
                coverageProvider: coverageProvider.trim(),
                ...(coveragePlan.trim() ? { coveragePlan: coveragePlan.trim() } : {}),
                ...(coverageMemberNumber.trim()
                  ? { coverageMemberNumber: coverageMemberNumber.trim() }
                  : {}),
                ...(coverageDocument ? { coverageDocumentId: coverageDocument } : {}),
              }
            : {}),
          items: selected.map((x) => ({ productId: x.id, quantity: items[x.id] })),
        },
        idempotent: true,
      });
      onCreated(result.id);
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'No se pudo enviar la solicitud.');
    } finally {
      setBusy(false);
    }
  }
  function continueStep() {
    setNotice('');
    if (step === 0) {
      if (!name.trim() || !birthDate || birthDate > birthLimit) {
        setNotice('Indicá el nombre y la fecha de nacimiento de una persona adulta.');
        return;
      }
      if (thirdParty && !/^\d{7,12}$/.test(beneficiaryIdentity.replace(/[.\s]/g, ''))) {
        setNotice('Indicá el DNI del familiar adulto beneficiario.');
        return;
      }
      if ((thirdParty || !sameReceiver) && !authorization) {
        setNotice('Adjuntá la nota firmada de autorización para esta compra.');
        return;
      }
      if (
        !sameReceiver &&
        (!receiverName.trim() || !/^\d{7,12}$/.test(receiverIdentity.replace(/[.\s]/g, '')))
      ) {
        setNotice('Completá nombre y DNI de la persona autorizada a recibir.');
        return;
      }
      if (mode === 'DELIVERY' && address.trim().length < 5) {
        setNotice('Completá calle, número y localidad del domicilio de entrega.');
        return;
      }
      if (!point) {
        setNotice('Confirmá la ubicación en el mapa.');
        return;
      }
    }
    if (step === 1 && (!selected.length || selected.length > 30)) {
      setNotice('Seleccioná entre una y treinta presentaciones diferentes.');
      return;
    }
    if (step === 1 && requiresPrescription && !prescription) {
      setNotice('Adjuntá la receta original para los medicamentos seleccionados.');
      return;
    }
    if (
      step === 2 &&
      coverageRequested &&
      (!coverageProvider.trim() || (!coverageMemberNumber.trim() && !coverageDocument))
    ) {
      setNotice(
        'Indicá la obra social o prepaga y el número de afiliado, o adjuntá la credencial.',
      );
      return;
    }
    setStep(step + 1);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  return (
    <Stack spacing={3}>
      <PageHeader
        eyebrow="Tu próxima compra"
        title="Nueva compra"
        description="La farmacia confirma disponibilidad, receta y cobertura. Recibís una propuesta para aceptar antes de pagar."
      />
      <Stepper activeStep={step} alternativeLabel sx={{ py: 1 }}>
        {['Persona y recepción', 'Medicamentos', 'Cobertura', 'Revisión'].map((title) => (
          <Step key={title}>
            <StepLabel>{title}</StepLabel>
          </Step>
        ))}
      </Stepper>
      {notice && (
        <Alert ref={noticeElement} severity="warning">
          {notice}
        </Alert>
      )}
      {!deliveryEnabled && !pickupEnabled && (
        <Alert severity="warning">
          No hay modalidades de compra habilitadas. Intentá nuevamente más tarde.
        </Alert>
      )}
      {step === 0 && (
        <Card>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h6">1. Beneficiario y modalidad</Typography>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={thirdParty}
                    onChange={(e) => {
                      setThirdParty(e.target.checked);
                      setName(e.target.checked ? '' : actor.name);
                      setBirthDate(e.target.checked ? '' : ownBirthDate);
                      setBeneficiaryIdentity(e.target.checked ? '' : (actor.dni ?? ''));
                      setAuthorization('');
                      setPrescription('');
                      setCoverageRequested(false);
                      setCoverageProvider('');
                      setCoveragePlan('');
                      setCoverageMemberNumber('');
                      setCoverageDocument('');
                    }}
                  />
                }
                label="Compro para un familiar adulto"
              />
              <TextField
                label="Nombre del beneficiario"
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                slotProps={{ input: { readOnly: !thirdParty } }}
                helperText={
                  thirdParty
                    ? 'Debe coincidir con el titular de la receta y la autorización.'
                    : 'La compra queda a nombre de tu cuenta.'
                }
              />
              <TextField
                label="Fecha de nacimiento del beneficiario"
                type="date"
                required
                value={birthDate}
                onChange={(e) => setBirthDate(e.target.value)}
                slotProps={{
                  inputLabel: { shrink: true },
                  input: { readOnly: !thirdParty && !!ownBirthDate },
                  htmlInput: { max: birthLimit },
                }}
                helperText="Las compras están disponibles para personas adultas."
              />
              {thirdParty && (
                <TextField
                  required
                  label="DNI del beneficiario"
                  value={beneficiaryIdentity}
                  onChange={(e) => setBeneficiaryIdentity(e.target.value)}
                  slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                  helperText="Debe coincidir con la persona adulta que firma la autorización."
                />
              )}
              <FormControlLabel
                control={
                  <Checkbox
                    checked={sameReceiver}
                    onChange={(e) => {
                      setSameReceiver(e.target.checked);
                      setAuthorization('');
                    }}
                  />
                }
                label="Recibe o retira la persona beneficiaria"
              />
              {!sameReceiver && (
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                    gap: 2,
                  }}
                >
                  <TextField
                    required
                    label="Nombre de quien recibe o retira"
                    value={receiverName}
                    onChange={(e) => setReceiverName(e.target.value)}
                  />
                  <TextField
                    required
                    label="DNI de quien recibe o retira"
                    value={receiverIdentity}
                    onChange={(e) => setReceiverIdentity(e.target.value)}
                    slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 16 } }}
                  />
                </Box>
              )}
              {(thirdParty || !sameReceiver) && (
                <Stack spacing={1}>
                  <Alert severity="info">
                    Adjuntá una nota nueva para esta compra. Debe identificar al titular y a las
                    personas autorizadas a comprar y recibir. La farmacia verifica manualmente la
                    firma y el alcance de la autorización.
                  </Alert>
                  <DocumentInput
                    label="Autorización firmada por el titular"
                    kind="AUTORIZACION"
                    value={authorization}
                    onChange={setAuthorization}
                  />
                </Stack>
              )}
              <TextField
                select
                label="Cómo querés recibir la compra"
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value);
                  setPoint(null);
                }}
              >
                {deliveryEnabled && (
                  <MenuItem value="DELIVERY">Entrega a domicilio sin costo</MenuItem>
                )}
                {pickupEnabled && <MenuItem value="PICKUP">Retiro en farmacia</MenuItem>}
              </TextField>
              {mode === 'DELIVERY' && (
                <TextField
                  label="Dirección completa de entrega"
                  required
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                  helperText="Calle, número, localidad y referencias."
                />
              )}
              <LocationPicker
                value={point}
                onChange={setPoint}
                delivery={mode === 'DELIVERY'}
                branches={branches.data}
              />
              <TextField
                select
                label="Farmacia de preferencia"
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                helperText="Buscamos primero en esa sucursal y su cadena, priorizando la red suscrita. Si hace falta dividir la compra, verás cada farmacia antes de aceptar."
              >
                <MenuItem value="">Buscar según ubicación</MenuItem>
                {branches.data?.map((x) => (
                  <MenuItem key={x.id} value={x.id}>
                    {x.name} · {x.address}
                  </MenuItem>
                ))}
              </TextField>
              {branches.error && (
                <Alert severity="warning">
                  No se pudo cargar la preferencia de farmacia. Podés buscar por ubicación o volver
                  a cargar la página.
                </Alert>
              )}
            </Stack>
          </CardContent>
        </Card>
      )}
      {step === 1 && (
        <Card>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h6">2. Medicamentos</Typography>
              <Typography variant="body2" color="text.secondary">
                Agregá cada presentación que necesitás y elegí la cantidad de envases. Podés buscar
                otra presentación sin perder las que ya agregaste.
              </Typography>
              {bootstrap.data?.mode === 'DEMO' && (
                <Alert severity="info">
                  Este catálogo de demostración usa marcas ficticias. Elegí una de las
                  presentaciones de la lista para recorrer la compra.
                </Alert>
              )}
              <Box component="section" aria-label="Medicamentos seleccionados">
                <Typography sx={{ fontWeight: 650 }}>
                  Tu solicitud: {selected.length} de 30 presentaciones
                </Typography>
                {selected.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    Todavía no agregaste medicamentos. Usá Agregar o indicá una cantidad en la
                    presentación que querés solicitar.
                  </Typography>
                ) : (
                  <Stack spacing={1} sx={{ mt: 1 }}>
                    {selected.map((x) => (
                      <Stack
                        key={x.id}
                        direction="row"
                        spacing={1}
                        sx={{ justifyContent: 'space-between', alignItems: 'center' }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>
                            {x.name}
                          </Typography>
                          <Typography variant="body2" color="text.secondary">
                            {x.presentation} · {items[x.id]}{' '}
                            {items[x.id] === 1 ? 'envase' : 'envases'}
                          </Typography>
                        </Box>
                        <Button
                          size="small"
                          onClick={() => updateQuantity(x, 0)}
                          aria-label={`Quitar ${x.name} · ${x.presentation}`}
                        >
                          Quitar
                        </Button>
                      </Stack>
                    ))}
                  </Stack>
                )}
              </Box>
              <Divider />
              <TextField
                label="Buscar por nombre o presentación"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                helperText="También podés buscar por código. Revisá el nombre, la concentración y el envase antes de agregar."
              />
              {products.error && (
                <Alert
                  severity="error"
                  action={
                    <Button disabled={products.isFetching} onClick={() => void products.refetch()}>
                      Volver a intentar
                    </Button>
                  }
                >
                  No se pudo cargar el catálogo de medicamentos. {products.error.message}
                </Alert>
              )}
              {products.isPending && (
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <CircularProgress size={24} aria-label="Cargando catálogo de medicamentos" />
                  <Typography variant="body2">Cargando presentaciones…</Typography>
                </Stack>
              )}
              {products.data && products.data.length === 0 && (
                <Alert severity="warning">
                  Todavía no hay medicamentos publicados. La administración debe cargar las
                  presentaciones y la farmacia debe registrar sus existencias para poder cotizarlas.
                </Alert>
              )}
              {products.data && products.data.length > 0 && visibleProducts.length === 0 && (
                <Alert severity="info">
                  <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
                    <Typography variant="body2">
                      No hay presentaciones que coincidan con «{search.trim()}» en este catálogo.
                      Probá con otra parte del nombre o consultá a la farmacia si falta el
                      medicamento que necesitás.
                    </Typography>
                    <Button size="small" onClick={() => setSearch('')}>
                      Ver todas las presentaciones
                    </Button>
                  </Stack>
                </Alert>
              )}
              <Box
                sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 2 }}
              >
                {visibleProducts.map((x) => (
                  <Box
                    key={x.id}
                    role="group"
                    aria-label={`${x.name} · ${x.presentation}`}
                    sx={{ p: 2, border: '1px solid #dfe8e7', borderRadius: 2 }}
                  >
                    <Typography sx={{ fontWeight: 650 }}>{x.name}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {x.presentation}
                    </Typography>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ my: 1, flexWrap: 'wrap' }}>
                      {x.requiresPrescription && <Chip size="small" label="Con receta" />}
                      {x.coldChain && <Chip size="small" label="Conservación en frío" />}
                      {x.restricted && <Chip size="small" label="Condiciones especiales" />}
                    </Stack>
                    <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
                      <TextField
                        label={`Cantidad de ${x.name}`}
                        type="number"
                        size="small"
                        value={items[x.id] ?? 0}
                        onChange={(e) => updateQuantity(x, Number(e.target.value))}
                        slotProps={{ htmlInput: { min: 0, max: 100, step: 1 } }}
                        helperText="Envases, de 1 a 100. Cero quita la presentación."
                      />
                      {(items[x.id] ?? 0) > 0 ? (
                        <Chip size="small" color="primary" label="Agregado a tu solicitud" />
                      ) : (
                        <Button
                          variant="outlined"
                          size="small"
                          onClick={() => updateQuantity(x, 1)}
                          disabled={selected.length >= 30}
                          aria-label={`Agregar ${x.name} · ${x.presentation}`}
                        >
                          Agregar
                        </Button>
                      )}
                    </Stack>
                  </Box>
                ))}
              </Box>
              {requiresPrescription && (
                <Alert severity="info">
                  Las presentaciones seleccionadas requieren una receta que revisará la farmacia.
                </Alert>
              )}
              <DocumentInput
                label="Receta externa, si corresponde"
                kind="RECETA"
                value={prescription}
                onChange={setPrescription}
              />
              <Typography variant="body2" color="text.secondary">
                La receta y la autorización se comparten sólo con las personas habilitadas para
                revisar esta compra.
              </Typography>
            </Stack>
          </CardContent>
        </Card>
      )}
      {step === 2 && (
        <Card>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h6">3. Forma de compra y cobertura</Typography>
              <TextField
                select
                label="Cómo querés cotizar los medicamentos"
                value={coverageRequested ? 'COVERAGE' : 'PARTICULAR'}
                onChange={(e) => setCoverageRequested(e.target.value === 'COVERAGE')}
              >
                <MenuItem value="PARTICULAR">Compra particular</MenuItem>
                <MenuItem value="COVERAGE">Solicitar cobertura de obra social o prepaga</MenuItem>
              </TextField>
              {coverageRequested && (
                <>
                  <Typography variant="body2">
                    La cobertura debe corresponder a {name.trim() || 'la persona beneficiaria'}. La
                    farmacia revisará tu afiliación y las autorizaciones necesarias antes de
                    calcular el importe.
                  </Typography>
                  <TextField
                    required
                    label="Obra social o prepaga del beneficiario"
                    value={coverageProvider}
                    onChange={(e) => setCoverageProvider(e.target.value)}
                    slotProps={{ htmlInput: { maxLength: 160 } }}
                  />
                  <TextField
                    label="Plan, si corresponde"
                    value={coveragePlan}
                    onChange={(e) => setCoveragePlan(e.target.value)}
                    slotProps={{ htmlInput: { maxLength: 160 } }}
                  />
                  <TextField
                    label="Número de afiliado del beneficiario"
                    value={coverageMemberNumber}
                    onChange={(e) => setCoverageMemberNumber(e.target.value)}
                    slotProps={{ htmlInput: { maxLength: 120 } }}
                    helperText="Si no lo tenés a mano, adjuntá una credencial legible. Conservá letras y ceros iniciales."
                  />
                  <DocumentInput
                    kind="COBERTURA"
                    label="Credencial de obra social o prepaga"
                    value={coverageDocument}
                    onChange={setCoverageDocument}
                  />
                  <Alert severity="info">
                    Solicitar cobertura no confirma un convenio ni un descuento. La farmacia
                    informará la cobertura comprobada y el importe final para que los aceptes antes
                    de pagar.
                  </Alert>
                  <Typography variant="body2" color="text.secondary">
                    Los datos de afiliación y la credencial se comparten sólo con el farmacéutico
                    autorizado para revisar esta compra.
                  </Typography>
                </>
              )}
            </Stack>
          </CardContent>
        </Card>
      )}
      {step === 3 && (
        <Card>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h6">4. Revisá tu solicitud</Typography>
              <Typography>
                {name.trim() || 'Beneficiario pendiente'} ·{' '}
                {thirdParty ? 'Compra autorizada para un familiar' : 'Compra para mí'}
              </Typography>
              <Typography>
                {mode === 'DELIVERY'
                  ? `Entrega sin costo: ${address.trim() || 'completá el domicilio'}`
                  : 'Retiro presencial en la farmacia que aceptes en el presupuesto.'}
              </Typography>
              <Typography>
                {coverageRequested
                  ? `Cobertura solicitada: ${coverageProvider.trim() || 'obra social o prepaga pendiente'}${coveragePlan.trim() ? ` · ${coveragePlan.trim()}` : ''}. Su aprobación está pendiente de revisión farmacéutica.`
                  : 'Compra particular, sin cobertura solicitada.'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Recibe o retira:{' '}
                {sameReceiver
                  ? name.trim() || 'persona beneficiaria'
                  : receiverName.trim() || 'persona autorizada pendiente'}
                .
              </Typography>
              {(thirdParty || !sameReceiver) && (
                <Alert severity="info">
                  La nota firmada se utilizará únicamente en esta compra. La farmacia debe aprobarla
                  antes de dispensar.
                </Alert>
              )}
              <Divider />
              {selected.length === 0 ? (
                <Typography color="text.secondary">Todavía no agregaste medicamentos.</Typography>
              ) : (
                selected.map((x) => (
                  <Stack
                    key={x.id}
                    direction="row"
                    spacing={2}
                    sx={{ justifyContent: 'space-between', alignItems: 'center' }}
                  >
                    <Box>
                      <Typography sx={{ fontWeight: 600 }}>{x.name}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {x.presentation} · {items[x.id]} {items[x.id] === 1 ? 'unidad' : 'unidades'}
                      </Typography>
                    </Box>
                    <Button
                      size="small"
                      onClick={() => setItems({ ...items, [x.id]: 0 })}
                      aria-label={`Quitar ${x.name}`}
                    >
                      Quitar
                    </Button>
                  </Stack>
                ))
              )}
              <Alert severity="info">
                Solicitar la propuesta no genera un cobro. Primero la farmacia confirma los
                medicamentos y el importe; después aceptás expresamente las condiciones y pagás
                directamente a cada farmacia.
              </Alert>
            </Stack>
          </CardContent>
        </Card>
      )}
      <Stack
        direction="row"
        spacing={2}
        sx={{
          justifyContent: 'space-between',
          position: 'sticky',
          bottom: 0,
          py: 2,
          bgcolor: 'background.default',
          borderTop: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Button
          disabled={step === 0 || busy}
          onClick={() => {
            setStep(step - 1);
            setNotice('');
          }}
        >
          Volver
        </Button>
        {step < 3 ? (
          <Button
            variant="contained"
            disabled={!deliveryEnabled && !pickupEnabled}
            onClick={continueStep}
          >
            {['Continuar a medicamentos', 'Continuar a cobertura', 'Revisar solicitud'][step]}
          </Button>
        ) : (
          <Button
            variant="contained"
            disabled={busy || !selected.length || (!deliveryEnabled && !pickupEnabled)}
            onClick={() => void submit()}
          >
            {busy ? 'Buscando farmacias…' : 'Solicitar propuesta a la farmacia'}
          </Button>
        )}
      </Stack>
    </Stack>
  );
}
