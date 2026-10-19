# Operador de farmacia

Este acceso gestiona existencias, cobros, facturación y preparación de los pedidos de tu sucursal.

## Entrar a la demo pública

1. Abrí [E-MEDICAPP](https://tamper-raving-aversion.ngrok-free.dev).
2. Si ngrok muestra su aviso inicial, elegí **Visit Site**.
3. Ingresá con **farmacia@demo-publica.invalid** y **Farmacia123!**.
4. Abrí **Solicitudes y pedidos**. Para cambiar de cuenta, usá **Salir**.

Estas credenciales son temporales. La sucursal de ejemplo es **Nivalis · Ciudad · Demo pública**; primero puede necesitar carga de stock y una compra de prueba.

## Cargar stock y precios

1. Entrá en **Stock y precios** y elegí tu sucursal.
2. En **Actualizar una presentación**, seleccioná el producto.
3. Informá la existencia física total, incluidas las unidades reservadas.
4. Cargá el precio vigente por presentación, con hasta dos decimales y sin separadores de miles.
5. Escribí el motivo y la comprobación realizada, y elegí **Guardar actualización**.
6. Si hay lotes, gestioná sus cantidades y conservación desde **Lotes y conservación**.

Si otro usuario actualizó el stock, usá **Cargar datos actuales** antes de guardar. Las reservas vigentes reducen el saldo disponible para compras nuevas.

## Recibir una solicitud

1. Entrá en **Solicitudes y pedidos** y usá **Mostrar compras** para localizarla.
2. Abrí la compra y buscá el pedido de tu sucursal.
3. Revisá estado, cantidades y próxima acción indicada.
4. El farmacéutico verifica la solicitud, receta, autorización y cobertura; el comprador acepta el presupuesto completo.
5. Esperá esa aceptación antes de registrar un pago.

## Comprobar el pago

1. Cuando aparezca **Verificar pago**, revisá el comprobante informado y el medio de cobro de la farmacia.
2. Confirmá el importe efectivamente recibido, moneda, medio y referencia de la operación.
3. Adjuntá la evidencia requerida y registrá la comprobación.
4. Si fue parcial, el pedido conserva el saldo pendiente. La preparación espera que se completen los pagos de toda la compra.

El comprobante del comprador no confirma el cobro. El dinero lo recibe la farmacia y la aplicación documenta la verificación.

## Facturar y preparar

1. Emití la factura desde el sistema de la farmacia.
2. En el pedido, elegí **Adjuntar factura**, cargá su número o referencia y el archivo.
3. Si falla la emisión, elegí **Informar problema de facturación**, explicá el problema y adjuntá evidencia. Administración recibirá el aviso.
4. Elegí **Preparar** cuando se habilite y separá las presentaciones y cantidades aceptadas.
5. El farmacéutico registra la dispensación y los controles de receta, lote y conservación.

La factura y los controles pendientes pueden bloquear la salida. No confirmes pasos que todavía no se realizaron.

## Entrega o retiro

1. Para entrega, después de la dispensación elegí **Asignar cadete** y seleccioná un cadete disponible de la sucursal.
2. Consultá **Entregas** para seguir la tarea y su estado.
3. Para retiro, el farmacéutico usa **Confirmar retiro** y verifica al receptor y su código.
4. Ante ausencia o problemas, coordiná con el farmacéutico: el retorno físico y el único reintento deben quedar registrados.

## Cancelación, reintegros e incidencias

1. Antes de la dispensación, usá **Cancelar este pedido** si la farmacia no puede cumplir y registrá el motivo.
2. En alto valor, esperá los controles de dos farmacéuticos y luego **Confirmar cancelación autorizada**.
3. Si se canceló con un comprobante sin resolver, usá **Conciliar pago informado** y comprobá si ingresó dinero.
4. Para una devolución, usá **Solicitar reintegro parcial o total** y documentá el acuerdo y las líneas afectadas.
5. Emití la nota fiscal que corresponda y cargala con **Registrar nota fiscal**.
6. Realizá la devolución desde el medio de pago de la farmacia y usá **Registrar reintegro** con su evidencia.
7. Ante problemas de entrega o producto, usá **Informar incidencia**. El farmacéutico evalúa retorno, reposición o resolución.

La aplicación no transfiere dinero ni devuelve unidades al stock automáticamente. En una compra dividida, resolvé la parte pendiente con las otras farmacias y conservá lo ya cumplido.

## Si una acción no aparece

- Los botones dependen del estado y del permiso activo de tu sucursal.
- Revisión clínica, sustitución, dispensación, retiro y retorno físico corresponden al **Farmacéutico**.
- La administración de usuarios y sucursales necesita el permiso de administrador correspondiente.
- Leé la próxima acción y el aviso del pedido; si falta un permiso, solicitáselo al administrador.
