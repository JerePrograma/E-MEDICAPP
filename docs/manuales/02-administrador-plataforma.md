# Administrador de la plataforma

Este acceso organiza toda la red: sucursales, personas, catálogo y suscripciones.

## Entrar a la demo pública

1. Abrí [E-MEDICAPP](https://tamper-raving-aversion.ngrok-free.dev).
2. Si ngrok muestra su aviso inicial, elegí **Visit Site**.
3. Ingresá con **administrador@demo-publica.invalid** y **Administrador123!**.
4. Abrí **Administración**. Para cambiar de cuenta, usá **Salir**.

Estas credenciales son temporales para la demostración. La sucursal de ejemplo es **Nivalis · Ciudad · Demo pública**. No supongas que ya tiene stock o pedidos.

## Incorporar una farmacia

1. Si pertenece a una red, entrá en **Cadenas → Agregar cadena** y guardá su nombre.
2. Entrá en **Sucursales → Agregar sucursal**. Una farmacia independiente puede quedar sin cadena.
3. Completá nombre, domicilio, latitud, longitud, farmacia que cobra y factura e instrucciones de pago directo.
4. Indicá entrega o retiro, radio, horarios y disponibilidad del cadete propio.
5. Tras comprobar los datos y el acuerdo, habilitá las marcas de verificación y adhesión que correspondan.
6. Guardá. Si ofrece medicamentos con condiciones especiales, volvé a **Modificar**, adjuntá evidencia y acreditá las capacidades comprobadas.

En esta versión, la farmacia se representa mediante sus sucursales y, cuando corresponde, una cadena. No hay un alta separada de empresa farmacéutica.

## Dar acceso a las personas

1. Pedile a cada persona que cree su propia cuenta y confirme el correo.
2. En **Miembros → Asignar permiso**, elegí persona y sucursal.
3. Elegí **Administrador de sucursal**, **Operador de farmacia**, **Farmacéutico** o **Cadete propio**.
4. Dejá **Permiso activo** marcado y guardá.
5. Si necesita más de una función o sucursal, creá un permiso adicional por cada combinación.

Asignar un permiso incorpora también el rol a la cuenta. Para operar se necesitan el rol y el permiso activo de la sucursal concreta.

## Incorporar otro administrador de la plataforma

1. La persona debe tener una cuenta propia con correo confirmado.
2. Entrá en **Usuarios**, buscala y elegí **Modificar acceso**.
3. Agregá **Administración**, conservá los demás roles que corresponden y elegí **Guardar acceso**.
4. Pedile que vuelva a iniciar sesión: los cambios de acceso cierran las sesiones anteriores.

El primer administrador se configura en el primer inicio del entorno de operación. Este procedimiento agrega otros administradores; no reemplaza al dueño existente.

## Preparar catálogo, stock y suscripción

1. En **Productos → Agregar producto**, registrá la presentación, receta y condiciones de conservación o control. En **Foto del producto → Seleccionar imagen**, podés adjuntar una foto opcional PNG o JPG de hasta 2 MB y 4096 × 4096 píxeles. Revisá la vista previa y guardá. Desde **Modificar**, podés reemplazarla o usar **Quitar foto** y guardar; la imagen se muestra en el catálogo y en el detalle de esa presentación.
2. Asigná un operador o farmacéutico para que cargue las existencias y los precios de cada sucursal desde **Stock y precios**.
3. Desde **Lotes**, registrá o corregí los lotes y su conservación cuando corresponda.
4. En **Planes → Agregar plan mensual**, definí el nivel por cantidad de establecimientos. Las U.M. son simbólicas; una cuota real requiere importe y moneda.
5. En **Suscripciones → Agregar suscripción**, elegí cadena o sucursal, plan y establecimientos incluidos.
6. Registrá el contrato y el período efectivamente pagado tras comprobarlos fuera de la aplicación.

La prioridad comercial depende del período pagado y verificado. Marcar una suscripción no realiza un cobro automático.

## Administrar la operación

1. En **Configuración**, ajustá plazos de presupuesto, reserva, tareas y recepción, y los demás parámetros disponibles.
2. Publicá los cambios. Las compras aceptadas conservan sus condiciones; los permisos de acceso se aplican de inmediato.
3. En **Miembros**, modificá turnos y cupos de cadetes. Para acreditar transporte especial, adjuntá la evidencia correspondiente.
4. Revisá los avisos de **Facturación pendiente · prioridad alta** y pedile a la sucursal que resuelva la emisión y adjunte la factura válida.
5. En **Usuarios → Modificar acceso**, activá o desactivá cuentas y ajustá los roles necesarios.
6. Para retirar una función local, desactivá también su permiso en **Miembros**.

La aplicación protege al último administrador activo. El acceso de administrador por sí solo no habilita revisión clínica, dispensación ni operación comercial de pedidos.

## Si falta una opción

- Para operar pedidos, necesitás además el permiso de operador o farmacéutico de esa sucursal.
- No se crean personas ni se envían invitaciones desde este panel: cada persona se registra.
- El envío de correos sigue pendiente de configuración; los registros nuevos esperan la confirmación. Las cuentas temporales de esta demo se prepararon para ingresar.
- Las verificaciones de habilitación, pago y facturación se documentan manualmente; cargá sólo resultados que hayas comprobado.
