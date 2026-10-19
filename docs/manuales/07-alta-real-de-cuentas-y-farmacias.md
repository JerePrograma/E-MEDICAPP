# Alta real de cuentas y farmacias

Esta guía explica cómo incorporar personas y establecimientos para una operación real. El primer administrador de E-MEDICAPP ya existe: el alta de una nueva farmacia se realiza desde su panel.

Una **cuenta** representa a una persona. Una **sucursal** representa al establecimiento que recibe pedidos, cobra y factura. Crear una sucursal no crea una cuenta ni una contraseña.

## Preparar el registro de personas

El envío de correos quedó preparado, pero todavía está pendiente de configuración. Para habilitar registros reales, configurá primero el correo siguiendo la [guía de configuración](../configuracion-correo.txt).

Hasta configurar el envío y confirmar el correo, las cuentas nuevas permanecen pendientes y no pueden ingresar. La administración no confirma correos desde el panel.

Las **7 cuentas temporales de la demo pública** se habilitan expresamente para la demostración. Esa preparación no cambia el registro normal ni habilita automáticamente a las personas que se registren después. En la operación real, cada persona utiliza su propia cuenta y contraseña.

## Registrar a cada persona

1. La persona abre E-MEDICAPP y elige registrarse.
2. Completa nombre, DNI, fecha de nacimiento, correo, teléfono y domicilio. Debe ser mayor de edad.
3. Elige una contraseña propia de al menos 12 caracteres.
4. Confirma su correo mediante el enlace recibido. Si vence, solicita otro enlace.
5. Inicia sesión con su correo y contraseña.

Toda cuenta registrada comienza como **Comprador**. El registro no otorga permisos de farmacia ni de administración. Este mismo procedimiento sirve para clientes, empleados, farmacéuticos, cadetes y futuros administradores.

No se crean personas desde el panel de administración ni se envían invitaciones automáticas. Primero debe existir la cuenta individual.

## Incorporar otro administrador de la plataforma

1. La persona se registra y confirma su correo.
2. El administrador actual entra en **Administración → Usuarios**.
3. Busca la cuenta y elige **Modificar acceso**.
4. Agrega **Administración**, conserva los demás roles que correspondan y guarda.
5. La persona vuelve a iniciar sesión.

Este acceso permite administrar toda la plataforma. Los cambios de acceso cierran las sesiones anteriores. La aplicación protege al último administrador activo; para reemplazarlo, primero debe quedar otro administrador habilitado.

El primer administrador se preparó durante el primer inicio del entorno de operación. Agregar uno nuevo desde **Usuarios** no reemplaza esa cuenta existente.

## Dar de alta una cadena y sus sucursales

Estos pasos corresponden al administrador de la plataforma.

1. Si la farmacia pertenece a una cadena, entra en **Cadenas → Agregar cadena** y guarda su nombre. Para una farmacia independiente, omite este paso.
2. Entra en **Sucursales → Agregar sucursal**.
3. Selecciona la cadena, si corresponde, y completa nombre, domicilio, latitud y longitud.
4. Indica la farmacia que cobra y factura, y sus instrucciones de pago directo.
5. Define entrega a domicilio, retiro o ambos; radio de entrega, horarios y disponibilidad de cadete propio.
6. Comprueba los datos, la habilitación y el acuerdo de adhesión antes de marcar las verificaciones correspondientes.
7. Guarda la sucursal.
8. Repite el alta por cada establecimiento.

En esta versión, una farmacia independiente es una sucursal sin cadena. No hay un alta adicional separada de empresa farmacéutica. La cadena agrupa establecimientos; no tiene una cuenta compartida ni un permiso automático sobre todos ellos.

El horario se repite diariamente. Si apertura y cierre quedan vacíos, la disponibilidad se controla manualmente. El radio es una referencia de distancia; la farmacia confirma la cobertura real de cada entrega.

## Designar al administrador de sucursal y su equipo

1. Cada integrante se registra con su propia cuenta y confirma su correo.
2. El administrador de la plataforma entra en **Administración → Miembros → Asignar permiso**.
3. Elige persona y sucursal.
4. Elige la función que debe cumplir.
5. Deja **Permiso activo** marcado y guarda.

| Función | Qué habilita |
|---|---|
| Administrador de sucursal | Actualizar datos operativos de las sucursales asignadas y organizar sus miembros. |
| Operador de farmacia | Gestionar la operación comercial, pedidos, cobros, facturas y stock según las acciones disponibles. |
| Farmacéutico | Revisar recetas y documentación, y realizar las actuaciones profesionales del circuito. |
| Cadete propio | Trabajar con las tareas de entrega asignadas. |

Asignar el permiso de sucursal agrega también el rol correspondiente a la cuenta. Para actuar sobre un establecimiento se necesitan **la cuenta activa con correo confirmado, el rol y el permiso activo de esa sucursal**.

El permiso **Administración** de plataforma se otorga desde **Usuarios**, no desde **Miembros**. El administrador de sucursal no puede concederlo.

## Una persona con varias funciones o sucursales

- Para administrar varias sucursales, asigná un permiso de **Administrador de sucursal** por cada establecimiento.
- Para administrar y operar pedidos en la misma sucursal, asigná además **Operador de farmacia** o **Farmacéutico**, según la tarea real de esa persona.
- Para trabajar en dos establecimientos, asigná los permisos que correspondan en cada uno.
- Para retirar una responsabilidad local, desactivá su permiso en **Miembros**. Para suspender toda la cuenta, el administrador de plataforma utiliza **Usuarios → Modificar acceso**.

Las funciones de administración por sí solas no habilitan revisión clínica, dispensación u operación comercial de pedidos. No existe un permiso separado de administrador de cadena: se asignan las sucursales concretas.

La aplicación protege al último administrador activo de cada sucursal. Antes de retirar ese permiso, asigná otra persona habilitada.

## Delegar la incorporación del equipo

Una vez designado, el administrador de sucursal puede incorporar personas en los establecimientos a su cargo.

1. Entra en **Miembros → Asignar permiso**.
2. Escribe el correo exacto de la persona y elige **Buscar cuenta confirmada**.
3. Selecciona la cuenta encontrada y una sucursal a su cargo.
4. Elige la función, deja el permiso activo y guarda.

Puede asignar otro administrador de sucursal, operadores, farmacéuticos y cadetes. No crea cadenas ni nuevas sucursales, no modifica permisos en establecimientos ajenos y no acredita capacidades especiales de transporte o conservación. Esas tareas corresponden a la administración de plataforma.

## Preparar catálogo, existencias y capacidades

1. El administrador de plataforma registra los productos en **Productos → Agregar producto**, con presentación, necesidad de receta y condiciones especiales.
2. El operador o farmacéutico asignado carga los precios y las existencias de cada sucursal desde **Stock y precios**.
3. Registra los lotes, vencimientos y condiciones de conservación cuando correspondan.
4. Para capacidades especiales de una sucursal, la administración vuelve a **Sucursales → Modificar**, adjunta la evidencia y acredita las condiciones comprobadas. La sucursal debe estar guardada antes de adjuntar esa documentación.
5. Para cadetes, se configura disponibilidad durante el turno y cantidad máxima de tareas simultáneas, entre 1 y 20.
6. Para transporte con frío o controles especiales, la administración acredita también la capacidad del cadete para esa sucursal, con su evidencia.

La carga de un permiso o una marca de capacidad no reemplaza la comprobación real de habilitación, formación y condiciones de operación. Las verificaciones se documentan manualmente en esta versión.

## Preparar el contrato y el pago mensual

Estos pasos corresponden al administrador de la plataforma.

1. En **Planes → Agregar plan mensual**, define el nivel según la cantidad de establecimientos.
2. En **Suscripciones → Agregar suscripción**, elige la cadena o farmacia contratante, el plan y las sucursales incluidas.
3. Registra el precio contractual y la moneda. Las unidades monetarias simbólicas sirven para definir planes; una suscripción activa requiere un importe real.
4. Comprueba el pago fuera de la aplicación.
5. Registra el período mensual abonado con inicio, fin, referencia y comprobante.
6. Repite la acreditación cuando se abone un nuevo período.

Guardar una suscripción no cobra dinero ni realiza débitos automáticos. La prioridad comercial se determina con los períodos pagados y verificados y las reglas vigentes de suscripción.

## Comprobar el alta antes de operar

1. El equipo ingresa con sus cuentas individuales y comprueba las funciones y sucursales asignadas.
2. La farmacia revisa ubicación, medios de entrega o retiro, horarios e instrucciones de pago.
3. Confirma precios, existencias, lotes y documentación de capacidades.
4. Comprueba que los profesionales y cadetes necesarios estén asignados y disponibles.
5. Revisa el contrato y el período abonado cuando corresponda.
6. Realiza una compra de prueba acordada con la farmacia y verifica el circuito completo antes de abrir la operación habitual.

Para consultar cada tarea del equipo, usá los manuales de [administración de plataforma](02-administrador-plataforma.md), [administración de sucursal](03-administrador-farmacia.md), [farmacéutico](04-farmaceutico.md), [operador](05-operador-farmacia.md) y [cadete](06-cadete.md).
