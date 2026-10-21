# E-MEDICAPP — MVP de operación manual

La web pública está en **[scalaris.com.ar/e-medicapp/](https://scalaris.com.ar/e-medicapp/)** y el backend en esta PC. Cloudflare sirve la publicación de [GitHub Pages](https://jereprograma.github.io/E-MEDICAPP/) bajo el dominio de Scalaris. Para presentar desde otras computadoras, abrir **`AbrirDemoPages.cmd`**, esperar la confirmación y compartir el enlace de Scalaris. La conexión se administra desde la publicación y no puede cambiarse desde la interfaz pública, el enlace ni los ajustes guardados del navegador. **`DetenerDemoPages.cmd`** cierra el backend y su túnel; conserva los datos. Ver [guía de GitHub Pages y demo](docs/github-pages-demo.txt).

La [verificación del 19 de octubre](docs/verificacion-pages-demo-20261019.txt) registra las correcciones, los datos cargados y las pruebas de esta entrega.

Aplicación Java + React con PostgreSQL. La autoridad funcional actual es `CASOS DE USO - E-MEDICAPP.pdf` de QA, con las respuestas posteriores del dueño: confirmación de correo sin aprobación de identidad, nota nueva por compra para comprar y recibir, un solo reintento coordinado y resolución farmacéutica de compras divididas. El concepto V3 y las decisiones anteriores completan los puntos compatibles que QA no especifica. La trazabilidad actual se documenta en [flujo del producto según QA](docs/flujo-producto-QA.txt); el relevamiento V3 del directorio superior se conserva como antecedente.

[Manuales breves por rol, cuentas temporales públicas y guía de alta real](docs/manuales/README.md). Cada perfil tiene su propio archivo para compartirlo por separado.

## Qué se puede demostrar

- **Ventas y cobros:** reportes diarios, semanales (lunes a domingo), mensuales y anuales, con selección de sucursal. Muestran cobros, reintegros verificados, neto y pedidos con cobro, separados por moneda, con detalle por fecha, sucursal y movimiento. Los períodos usan la hora de Mendoza y la fecha de verificación del movimiento. Incluyen únicamente operaciones de E-MEDICAPP; el neto no es una ganancia contable.
- **Promociones:** las farmacias y sus administradores publican, programan, destacan o pausan ofertas con condiciones, vigencia y sucursales participantes de una misma cadena. El cliente encuentra destacadas en Mis compras y todas las vigentes en Promociones; el detalle muestra cadena, sucursal, dirección, disponibilidad de retiro y mapa. Las promociones informan las condiciones anunciadas; la farmacia confirma el importe en el presupuesto habitual.
- **Catálogo:** las cuentas de comprador exploran seis accesos iniciales y amplían a doce con Más categorías. Medicamentos permite filtrar con receta o sin receta; las demás categorías incluyen salud sexual, piel, belleza, vitaminas, primeros auxilios, bebés, cuidado personal, insumos médicos, alimentos y hogar. Cada producto muestra las sucursales con disponibilidad registrada, su cadena, dirección, precio, moneda, unidades libres de reservas, fecha de actualización y mapa. Se conservan los requisitos de receta y las capacidades necesarias para productos especiales; la farmacia confirma stock físico e importe al cotizar.
- **Carrito:** permite agregar productos, modificar cantidades, quitarlos y elegir una preferencia de retiro. La selección se conserva en el navegador por cuenta y servidor, con hasta 30 productos distintos y 100 unidades de cada presentación. Guarda identificadores, cantidades y sucursal preferida; la documentación y los datos del beneficiario se completan en el flujo habitual de compra. Agregar al carrito no reserva stock ni genera un cobro. La sucursal y el importe definitivos se confirman en la propuesta; las reservas se realizan al emitir el presupuesto.

La [guía de ventas, promociones, catálogo y carrito](docs/ventas-y-promociones.txt) explica el alcance y los pasos de uso.

- Un comprador adulto crea una solicitud para sí mismo o para un familiar adulto con autorización firmada. Elige entrega o retiro y confirma el punto geográfico correspondiente.
- El alta solicita DNI y confirmación del correo, sin aprobación manual de la cuenta. La nota de representación se verifica manualmente por compra; se puede pedir más información, rechazarla, reemplazarla o revocarla.
- El comprador elige compra particular o solicita cobertura con obra social/prepaga, plan opcional y número de afiliado o credencial privada. Esos datos corresponden al beneficiario; la farmacia comprueba la cobertura externamente antes de cotizar.
- Se prioriza la red con suscripción pagada. Se intenta una sucursal con toda la cesta, se mantiene la preferencia por la cadena y, si hace falta, se divide en pedidos por farmacia. Las alternativas sin suscripción requieren acuerdo y habilitación, y quedan después de la red suscrita.
- La farmacia comprueba stock físico, identidad, receta externa, cobertura y capacidades. El farmacéutico deja una revisión con responsable y evidencia. Los pedidos de alto valor exigen dos profesionales distintos.
- El comprador acepta expresamente la propuesta completa. Los importes, productos, farmacias, cobertura y condiciones aceptadas se conservan. Un precio cambiado o una propuesta vencida requiere nueva revisión y aceptación.
- El presupuesto emitido reserva stock, lote y saldo de receta antes de la aceptación. Su vencimiento libera las reservas. La farmacia puede proponer una presentación alternativa, con comprobación profesional cuando requiere receta; el comprador acepta o rechaza el cambio y después revisa el presupuesto nuevo.
- El stock, lote y saldo de receta se reservan transaccionalmente. La farmacia recibe el dinero directamente y registra un cobro comprobado con referencia y evidencia; E-MEDICAPP no ejecuta la transferencia.
- El comprador puede informar el pago y adjuntar su comprobante. La farmacia consulta ese documento y confirma los fondos en su medio de pago. En una compra dividida, ninguna sucursal comienza a preparar hasta que todos los pagos estén comprobados.
- La farmacia emite y adjunta su factura, prepara y registra la dispensación. El pedido se retira presencialmente o se asigna a un cadete propio.
- Se registra la custodia y la recepción mediante un código temporal. En categorías especiales se exigen capacidades y evidencia; para frío se verifica el rango del producto en los hitos del cumplimiento.
- La asignación automática respeta sucursal, disponibilidad y capacidad del cadete. Los rechazos y tareas vencidas buscan otro cadete elegible. La recepción verifica identidad observada, nota vigente cuando corresponde y coincidencia del paquete; un código incorrecto conserva la tarea pendiente.
- Una ausencia requiere documentar el regreso físico a la farmacia y coordinar un único reintento. La farmacia decide la resolución posterior, sin borrar partes de una compra dividida que ya se entregaron.
- La farmacia puede proponer una reposición sin cargo después de evaluar una incidencia. El cliente acepta sus condiciones y aporta documentos nuevos cuando corresponden; se reservan y dispensan unidades nuevas, con un pedido vinculado que conserva la historia original.
- Una cancelación anterior a la dispensación libera las reservas y solicita el reintegro completo de lo registrado, sin tope arbitrario. La farmacia confirma el reintegro externo. Los problemas posteriores se resuelven como incidencias farmacéuticas; no reingresan medicamentos automáticamente al stock.
- Si se cancela un pedido después de informar un comprobante y antes de verificar el cobro, la farmacia debe conciliarlo. Si comprueba fondos recibidos, registra el cobro y solicita el reintegro completo; si no recibió el pago, documenta el resultado. Esa conciliación no reactiva reservas ni preparación.
- El panel administra productos, cadenas, sucursales, stock, lotes, planes, suscripciones, miembros, capacidades y parámetros operativos. Cada corrección conserva su responsable y versión.
- Los pagos registran importe y moneda realmente comprobados. Un pago parcial no habilita preparación. Factura, notas de crédito/débito y reintegros parciales conservan evidencia y referencia; cancelación, despacho y reintegro de alto valor requieren dos profesionales distintos. Una cancelación solicitada detiene el pedido mientras se completa ese control.
- El administrador de farmacia gestiona sus sucursales y personal dentro de su alcance. La plataforma conserva la acreditación de capacidades y la gestión de accesos globales. No se puede retirar al último administrador activo de plataforma o sucursal.

El cliente no paga comisión de plataforma ni envío. La farmacia absorbe el envío. La suscripción es una cuota mensual fija; las U.M. de los planes son referencias simbólicas, no una moneda de cobro.

Un período mensual se calcula por mes calendario en el horario de Mendoza, conservando la hora del contrato y ajustando el día cuando el mes siguiente es más corto. Las condiciones aceptadas de cada pedido y los períodos ya registrados no cambian cuando se modifica la configuración.

## Stack y estructura

La aplicación incorpora **Ionic React 9 y Capacitor 8** como base compartida para
la web y Android. El proyecto nativo está en `frontend/android`; el flujo Android
de GitHub genera un APK de prueba y un AAB sin firma. Los pasos de compilación,
conexión y preparación para Play Store están en la [guía Ionic y Android](docs/ionic-android.txt).

| Parte | Elección |
|---|---|
| Backend | Java 25 LTS, Spring Boot 4.1.1, Spring MVC, Spring Security, JdbcClient |
| Datos | PostgreSQL 17 o superior (17.5 en esta PC), Flyway, SQL explícito, migraciones aditivas |
| Frontend | React 19.3.0, TypeScript 6.0.3, Vite 8.3.4, MUI 9.4.0 |
| Formularios y consultas | React Hook Form, Zod, TanStack Query |
| Ubicación | Leaflet 1.9.4 y mapa OpenStreetMap con atribución |
| Verificación | JUnit, pruebas HTTP con PostgreSQL, Vitest y Playwright |

Un único backend organizado por función: `auth`, `documents`, `catalog`, `purchase`, `payments`, `fulfillment`, `replacements`, `promotions`, `reports`, `config`. Las transacciones quedan en los servicios; el servidor decide precios, reservas, estados y permisos. La web compartida se sirve desde GitHub Pages. El JAR también incluye la SPA para usarla como alternativa local en esta PC. Vite se utiliza durante desarrollo.

Se aplican transacciones, estados explícitos, control de versión, idempotencia y separación por rol y sucursal donde resuelven problemas reales. No se introduce un motor editable de flujos. Los parámetros cambian desde el panel; una etapa nueva se implementa en código y se verifica.

## Arranque en esta PC

El proyecto está en `C:\laburo\E-MEDICAPP`.

Requisitos: JDK 25, Node.js 24 LTS y PostgreSQL 17 o superior. Maven se obtiene mediante el wrapper. Los scripts descubren las herramientas instaladas, incluido un JDK portable en `.tools/java/`. La primera compilación necesita Internet para dependencias. Para compartir la demo se utiliza `cloudflared`, que proporciona HTTPS hacia el backend local.

```powershell
cd C:\laburo\E-MEDICAPP
.\scripts\Build.ps1
.\scripts\Start-DemoPages.ps1 -OpenBrowser
```

Después de compilar, también se puede abrir `AbrirDemoPages.cmd` con doble clic. El acceso comprueba el backend HTTPS y su configuración publicada antes de mostrar el enlace normal de GitHub Pages para compartirlo.

Como alternativa de uso exclusivo en esta PC, detener primero la demo de Pages y abrir `AbrirDemo.cmd`: sirve la web incluida en el JAR en `http://127.0.0.1:8082`. Ambos accesos utilizan el mismo escenario DEMO y el mismo puerto; se inicia uno por vez.

Los scripts utilizan el JDK 25 del proyecto sin cambiar el Java predeterminado de otros proyectos. Para otra instalación, configurar `EMEDICAPP_JAVA_HOME` o pasar `-JavaHome`. Para PostgreSQL, configurar `EMEDICAPP_PG_BIN`. No se adopta ni se detiene automáticamente un proceso ajeno que ocupe los puertos.

La base local propia escucha en `127.0.0.1:5544` en esta PC; los scripts leen el puerto persistido en `.runtime/local-db.json`. Se inicializa en `.runtime/postgres`, con contraseña aleatoria protegida mediante DPAPI para el usuario de Windows. No se imprime la contraseña ni se guarda en el repositorio. `.runtime` contiene los datos persistidos, archivos privados y registros: conservarla; no es una caché descartable.

### Cuentas exclusivamente de demostración

Contraseña pública sólo DEMO: `DemoMedicapp2026!`.

| Perfil | Persona del escenario | Correo |
|---|---|---|
| Comprador | Lucía Fernández | comprador@demo.invalid |
| Operador de farmacia | Paula Benítez | farmacia@demo.invalid |
| Farmacéutica | Valentina Ríos | farmaceutico@demo.invalid |
| Segundo farmacéutico | Gabriel Ponce | farmaceutico2@demo.invalid |
| Cadete | Santiago Molina | cadete@demo.invalid |
| Administrador de farmacia | Sofía Méndez | adminfarmacia@demo.invalid |
| Administración | Marina Suárez | admin@demo.invalid |

El selector de ingreso muestra el perfil y la persona. El segundo farmacéutico permite completar la revisión de alto valor con dos cuentas distintas. Lucía tiene fecha de nacimiento `1990-01-01`; para comprar para sí misma, su identidad se toma de la cuenta.

El escenario actualizado contiene 7 personas, 18 presentaciones, 4 sucursales, 72 registros de stock y 72 lotes. Las cantidades varían por sucursal e incluyen faltantes para demostrar alternativas y división. Los precios incluyen centavos, diferencias entre sucursales y un medicamento de alto valor. Los 4 documentos iniciales respaldan capacidades simuladas; la base de presentación se inicia sin compras ni pagos de pruebas.

Las sucursales del escenario son **Nivalis · Ciudad**, **Nivalis · Dorrego**, **Valnera · Godoy Cruz** y **Luma Cuyo · Las Heras**, con direcciones de Mendoza y condiciones operativas diferenciadas. Los medicamentos tienen nombre, concentración y presentación completos; las marcas comerciales, comercios y personas son ficticios. Los importes y requisitos del escenario no son una lista de precios ni una guía de dispensación real.

Un único aviso visible identifica el modo DEMO. Los documentos de muestra conservan su identificación como simulaciones. Los registros que realices en la demostración persisten; las pruebas automatizadas de navegador utilizan una base aparte para mantener limpio el escenario de presentación.

El [guion de demostración](docs/guion-demostracion.txt) guía una presentación de 10 a 15 minutos con perfiles separados, importes y PDF coherentes: retiro completo de una caja, alternativa de entrega y caso opcional de familiar con receta, cobertura, doble revisión y reintegro.

Para renovar el escenario de demostración:

```powershell
.\scripts\Stop-DemoPages.ps1
.\scripts\ResetDemo.ps1 -NoStart
.\scripts\Start-DemoPages.ps1 -OpenBrowser
```

Con el túnel detenido, el reinicio del escenario genera un respaldo, detiene únicamente la aplicación DEMO propia y archiva su base anterior con un nombre fechado. También conserva los documentos en su directorio de archivo y crea una nueva base DEMO. No ejecuta `DROP` ni modifica MANUAL. La ubicación del archivo queda en `.runtime/demo-reset.json`; los respaldos se guardan en `.runtime/backups`. `-NoStart` prepara la nueva base sin iniciar el acceso local; el último paso vuelve a publicar el backend de Pages.

Para detener el backend y el túnel de Pages, abrir `DetenerDemoPages.cmd` o ejecutar:

```powershell
.\scripts\Stop-DemoPages.ps1
```

## Operación manual con datos propios

Detener la demo de Pages con `DetenerDemoPages.cmd` antes de usar el mismo puerto. Si se utilizaba la alternativa local, detenerla con `scripts\Stop.ps1 -Environment Demo`. Abrir `AbrirOperacionManual.cmd` o ejecutar:

```powershell
.\scripts\Start.ps1 -Environment Manual -OpenBrowser
```

El primer inicio solicita correo y contraseña propios del administrador. También acepta `EMEDICAPP_ADMIN_EMAIL` y `EMEDICAPP_ADMIN_PASSWORD` en el entorno. La contraseña se captura de forma oculta y no se guarda en los scripts. MANUAL utiliza `emedicapp_manual` y archivos separados, sin insertar farmacias, medicamentos ni credenciales DEMO.

Configuración inicial:

1. Ingresar como administrador y cargar cadenas y sucursales reales, responsables, horarios, modalidades, zonas operativas y medios de pago de cada farmacia.
2. Configurar el envío de correo con `ConfigurarCorreo.cmd` cuando se disponga del proveedor. Las personas se registran como compradores adultos y confirman su correo; el administrador les asigna los permisos de sucursal que corresponden. Mientras SMTP siga pendiente, la cuenta nueva queda sin habilitar. Ver [configuración de correo](docs/configuracion-correo.txt).
3. Registrar las capacidades verificadas de farmacia y cadete cuando la categoría lo requiera, con documentación que las respalde.
4. Cargar presentaciones comerciales y sus condiciones documentadas; cargar precios y existencias por sucursal, y lotes para categorías que requieren trazabilidad.
5. Configurar los planes y contratos con importes reales positivos y moneda. Registrar períodos mensuales efectivamente cobrados con referencia y evidencia. Las U.M. no activan cobros reales.
6. Revisar los parámetros operativos y hacer una primera compra controlada entre usuarios habilitados.

En **Administración → Cadenas → Agregar cadena / Modificar**, el administrador puede cargar,
reemplazar o quitar el logo de una cadena y guardar los cambios. Se aceptan imágenes PNG o JPG
de hasta 2 MB y 4096 × 4096 píxeles. Cada sucursal de esa cadena muestra el logo en su ubicación
en el mapa de la compra; al tocar el marcador se consulta su nombre y dirección. Sin logo se
muestra un marcador común. El punto de entrega o ubicación del comprador se confirma por separado.

En **Administración → Productos → Agregar producto / Modificar**, puede cargar una foto
opcional de cada presentación, previsualizarla, reemplazarla o quitarla y guardar los cambios.
Acepta PNG o JPG de hasta 2 MB y 4096 × 4096 píxeles. La foto aparece en el catálogo y
en el detalle del producto; sin foto se conserva la ilustración de la categoría.
La imagen pertenece al producto compartido; precios y existencias siguen siendo por sucursal.
Ver [verificación de fotos de productos](docs/verificacion-fotos-productos-20261020.txt).

Este MVP coordina operaciones y conserva comprobaciones manuales. No confirma por sí mismo la autenticidad de una receta, autorización de obra social, habilitación sanitaria, factura fiscal o transferencia bancaria. Esas verificaciones las realiza el responsable en su fuente externa y registra la evidencia.

## Pruebas

Los resultados de esta fase de QA y sus recorridos están en
[la verificación del producto según QA](docs/verificacion-producto-QA.txt).
La [revisión de DEMO y flujos](docs/revision-demo-flujos.txt) conserva la evidencia del 8/10.
El [reporte de verificación inicial](docs/verificacion-mvp.txt) se conserva como
evidencia histórica de la primera fase; sus cantidades y capturas pertenecen al
escenario anterior.

```powershell
.\scripts\Test.ps1
npm.cmd --prefix frontend test
# Compilar también la aplicación completa y verificar contra una base QA nueva:
.\scripts\Test-QA.ps1 -Package
# Con la base local propia iniciada y el proyecto compilado:
.\scripts\Test-Workflow.ps1
```

`Test.ps1` delega en `Test-QA.ps1`, que crea una base `emedicapp_qa_*_demo` nueva y documentos propios en cada ejecución. No borra ni reutiliza datos de operación o de QA anterior. `-Package` también compila e incluye el frontend en un JAR propio de QA; su ruta queda en el campo `jar` del estado `.runtime/qa-*.json` y se puede pasar a `Test-Workflow.ps1 -JarPath`. Así no reemplaza el ejecutable de una demo abierta. Las pruebas de integración requieren PostgreSQL real; una ejecución de Maven sin las variables de prueba informa esos casos como omitidos. La compilación sola no prueba la operación de la base.

`Test-Workflow.ps1` crea una base QA nueva y un directorio de documentos propios en cada invocación. Inicia la aplicación en `http://127.0.0.1:8083`, espera disponibilidad, ejecuta los recorridos Playwright y detiene sólo el proceso que inició. La base y las evidencias QA se conservan para revisión; el estado queda en `.runtime/workflow-*.json` y la salida del navegador en `.runtime/verification-browser.log`.

Sin parámetros ejecuta todos los recorridos de navegador. Para verificar un cambio puntual se puede pasar `-TestName 'registro sin correo'`: selecciona nombres que contienen ese texto literal y conserva la misma protección de base, documentos y puerto. El estado registra la selección y el hash del JAR; una ejecución parcial no se considera aprobación del conjunto completo.

El puerto 8083 debe estar libre. El script no adopta ni detiene otra aplicación. La ejecución directa de `npm.cmd --prefix frontend run test:e2e` se niega a operar contra la base de presentación de 8082: el recorrido exige el entorno aislado preparado por el script.

La primera instalación del navegador de prueba se hace con `npx.cmd playwright install chromium` dentro de `frontend`.

## Respaldo

```powershell
.\scripts\Backup.ps1 -Environment Manual
```

Se genera un directorio con dump PostgreSQL, documentos privados, índice del dump y manifiesto SHA-256. Para otra ubicación usar `-Destination`. El respaldo contiene datos de la operación y debe guardarse en un destino privado con acceso restringido. Las credenciales DPAPI locales no forman parte del respaldo.

La restauración se realiza sobre una base nueva mediante `pg_restore`, con PostgreSQL de la misma versión principal y los documentos en su directorio privado. No se incluyen acciones automáticas de borrado o reemplazo de bases existentes. Para una prueba de recuperación, comparar el número de compras y documentos, comprobar los hashes y abrir los documentos referenciados desde la aplicación antes de utilizar el entorno restaurado.

## Desarrollo y despliegue posterior

La web se publica con [el flujo de GitHub Pages](.github/workflows/pages.yml).
Compila `frontend/` con recursos relativos (`./`) y carga solamente sus archivos
estáticos. Configurar **Settings → Pages → Source → GitHub Actions** en el
repositorio. Un push a `main` con cambios del frontend ejecuta el flujo.
La función `functions/e-medicapp/[[path]].js` del repositorio `JerePrograma/scalaris`
sirve esos mismos archivos en `/e-medicapp/`, sin redirigir al visitante a GitHub.
El backend admite los orígenes de Scalaris y GitHub Pages.
El origen HTTPS autorizado se fija en la variable de publicación `VITE_API_BASE_URL`.
La web pública ignora `?api=...` y cualquier servidor guardado anteriormente; no
ofrece opciones para cambiarlo. Los enlaces antiguos se limpian conservando su
pantalla de destino. La configuración manual queda disponible sólo en localhost
cuando no hay un servidor fijado en la compilación.

`AbrirDemoPages.cmd` sincroniza la dirección del túnel y espera que Pages confirme
el commit remoto y el servidor de la configuración publicada, mediante `deployment.json`.
Una publicación fallida
no se considera actualizada sólo porque la variable ya coincida. La sincronización
se puede repetir con `scripts/Sync-DemoPages.ps1 -Wait`; `-Force` solicita otra
publicación. Se necesita GitHub CLI con acceso al repositorio. Para un backend
permanente corresponde utilizar una dirección estable. La autenticación y los
permisos de cada acción siguen controlados por el backend.

La [guía de Pages y demo](docs/github-pages-demo.txt) reúne los accesos, cuentas,
actualización y diagnóstico. El despliegue anterior de ngrok/MANUAL y sus
[verificaciones](docs/verificacion-publicacion-pc.txt) son antecedentes históricos;
no acreditan un despliegue activo en esta máquina.

Durante desarrollo: iniciar el backend local con `scripts\Start.ps1 -Environment Demo` y, en otra terminal, `npm.cmd --prefix frontend run dev`. Vite escucha en `127.0.0.1:5174` y envía `/api` al backend local. Para la presentación compartida se utiliza GitHub Pages; el frontend incluido en el JAR permite el acceso alternativo desde esta PC.

El mismo JAR puede desplegarse en un servidor Java 25 con PostgreSQL 17 o superior y almacenamiento persistente para documentos. Los parámetros se suministran desde el entorno:

| Variable | Uso |
|---|---|
| EMEDICAPP_MODE | DEMO o MANUAL; la base debe tener el sufijo correspondiente |
| EMEDICAPP_DB_URL / EMEDICAPP_DB_USER / EMEDICAPP_DB_PASSWORD | Conexión a PostgreSQL |
| EMEDICAPP_DOCUMENTS_DIR | Directorio privado persistente, fuera del frontend |
| EMEDICAPP_ADDRESS / EMEDICAPP_PORT | Dirección y puerto de escucha |
| EMEDICAPP_SECURE_COOKIES | `true` para acceso HTTPS |
| EMEDICAPP_ALLOWED_ORIGINS | Orígenes exactos admitidos; para Pages: `https://jereprograma.github.io` |
| EMEDICAPP_EMAIL_BASE_URL | Dirección del frontend para enlaces de correo, incluyendo `/E-MEDICAPP/` |
| EMEDICAPP_BUSINESS_ZONE | Zona de fechas del negocio; por defecto `America/Argentina/Mendoza` |
| EMEDICAPP_ADMIN_EMAIL / EMEDICAPP_ADMIN_PASSWORD | Primer administrador de un entorno MANUAL nuevo |

El acceso de presentación utiliza GitHub Pages, un túnel HTTPS al backend DEMO y documentos privados servidos por endpoints autenticados. La conexión se fija en la publicación y la sesión se conserva por pestaña, sin depender de cookies de terceros. La geolocalización del navegador necesita HTTPS fuera de localhost. El mapa requiere Internet; las ubicaciones también pueden marcarse manualmente. No se modificó el firewall ni se abrieron puertos del router.

## Límites de esta primera versión

- Stock, recetas, cobertura, factura, pagos y suscripción utilizan carga y verificación manual. No hay conectores reales a emisores, obras sociales, ERP, bancos o ARCA.
- La reserva protege el stock registrado en E-MEDICAPP. Sin integración con el sistema de la farmacia, una venta simultánea de mostrador puede cambiar la existencia física; la farmacia debe confirmarla y conciliarla antes de dispensar.
- Las distancias se calculan geográficamente para búsqueda; no son rutas de tránsito ni tiempos garantizados de entrega.
- La disponibilidad combina un radio por sucursal y una franja diaria configurada. Todavía no hay un calendario de feriados, turnos rotativos ni varias franjas por día.
- La ubicación del cadete se actualiza cuando éste la informa durante una tarea activa; no se promete seguimiento continuo en segundo plano.
- La notificación operativa se muestra dentro de la aplicación y las pantallas actualizan su estado. Los correos de confirmación y recuperación se envían cuando se configura SMTP; avisos operativos por WhatsApp, correo o SMS requieren una integración posterior.
- El esquema MVP implementa el circuito operativo. Los módulos más amplios del relevamiento permanecen como evolución y no se presentan como implementados.
- La preparación de un piloto real incluye cargar y verificar la operación de cada farmacia y acordar sus procedimientos. Los datos DEMO no acreditan convenios ni condiciones de una farmacia real.

## Referencias de las versiones elegidas

- [Oracle: hoja de soporte Java y versiones LTS](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Spring Boot: requisitos del sistema](https://docs.spring.io/spring-boot/system-requirements.html)
- [React: versiones publicadas](https://react.dev/versions)
- [PostgreSQL: política y versiones mantenidas](https://www.postgresql.org/support/versioning/)
- [Node.js: versiones anteriores y LTS](https://nodejs.org/en/about/previous-releases)
