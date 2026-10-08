# E-MEDICAPP — MVP de operación manual

Aplicación Java + React con PostgreSQL, construida a partir del concepto V3 y las decisiones del dueño de la idea. La carpeta `relevamiento_v3` del directorio superior contiene el relevamiento completo; este repositorio implementa el núcleo operativo de la primera versión. No modifica los archivos fuente del relevamiento.

## Qué se puede demostrar

- Un comprador adulto crea una solicitud para sí mismo o para un familiar adulto con autorización firmada. Elige entrega o retiro y confirma el punto geográfico correspondiente.
- El comprador elige compra particular o solicita cobertura con obra social/prepaga, plan opcional y número de afiliado o credencial privada. Esos datos corresponden al beneficiario; la farmacia comprueba la cobertura externamente antes de cotizar.
- Se prioriza la red con suscripción pagada. Se intenta una sucursal con toda la cesta, se mantiene la preferencia por la cadena y, si hace falta, se divide en pedidos por farmacia. Las alternativas sin suscripción requieren acuerdo y habilitación, y quedan después de la red suscrita.
- La farmacia comprueba stock físico, identidad, receta externa, cobertura y capacidades. El farmacéutico deja una revisión con responsable y evidencia. Los pedidos de alto valor exigen dos profesionales distintos.
- El comprador acepta expresamente la propuesta completa. Los importes, productos, farmacias, cobertura y condiciones aceptadas se conservan. Un precio cambiado o una propuesta vencida requiere nueva revisión y aceptación.
- El stock, lote y saldo de receta se reservan transaccionalmente. La farmacia recibe el dinero directamente y registra un cobro comprobado con referencia y evidencia; E-MEDICAPP no ejecuta la transferencia.
- El comprador puede informar el pago y adjuntar su comprobante. La farmacia consulta ese documento y confirma los fondos en su medio de pago. En una compra dividida, ninguna sucursal comienza a preparar hasta que todos los pagos estén comprobados.
- La farmacia emite y adjunta su factura, prepara y registra la dispensación. El pedido se retira presencialmente o se asigna a un cadete propio.
- Se registra la custodia y la recepción mediante un código temporal. En categorías especiales se exigen capacidades y evidencia; para frío se verifica el rango del producto en los hitos del cumplimiento.
- Una cancelación anterior a la dispensación libera las reservas y solicita el reintegro completo de lo registrado, sin tope arbitrario. La farmacia confirma el reintegro externo. Los problemas posteriores se resuelven como incidencias farmacéuticas; no reingresan medicamentos automáticamente al stock.
- Si se cancela un pedido después de informar un comprobante y antes de verificar el cobro, la farmacia debe conciliarlo. Si comprueba fondos recibidos, registra el cobro y solicita el reintegro completo; si no recibió el pago, documenta el resultado. Esa conciliación no reactiva reservas ni preparación.
- El panel administra productos, cadenas, sucursales, stock, lotes, planes, suscripciones, miembros, capacidades y parámetros operativos. Cada corrección conserva su responsable y versión.

El cliente no paga comisión de plataforma ni envío. La farmacia absorbe el envío. La suscripción es una cuota mensual fija; las U.M. de los planes son referencias simbólicas, no una moneda de cobro.

Un período mensual se calcula por mes calendario en el horario de Mendoza, conservando la hora del contrato y ajustando el día cuando el mes siguiente es más corto. Las condiciones aceptadas de cada pedido y los períodos ya registrados no cambian cuando se modifica la configuración.

## Stack y estructura

| Parte | Elección |
|---|---|
| Backend | Java 25 LTS, Spring Boot 4.1.1, Spring MVC, Spring Security, JdbcClient |
| Datos | PostgreSQL 18.6, Flyway, SQL explícito, 35 tablas del núcleo MVP |
| Frontend | React 19.3.0, TypeScript 6.0.3, Vite 8.3.4, MUI 9.4.0 |
| Formularios y consultas | React Hook Form, Zod, TanStack Query |
| Ubicación | Leaflet 1.9.4 y mapa OpenStreetMap con atribución |
| Verificación | JUnit, pruebas HTTP con PostgreSQL, Vitest y Playwright |

Un único backend organizado por función: `auth`, `documents`, `catalog`, `purchase`, `payments`, `fulfillment`, `config`. Las transacciones quedan en los servicios; el servidor decide precios, reservas, estados y permisos. La SPA se incluye dentro del JAR para el uso diario. Vite se utiliza durante desarrollo.

Se aplican transacciones, estados explícitos, control de versión, idempotencia y separación por rol y sucursal donde resuelven problemas reales. No se introduce un motor editable de flujos. Los parámetros cambian desde el panel; una etapa nueva se implementa en código y se verifica.

## Arranque en esta PC

El proyecto está en `C:\laburo\E-MEDICAPP\E-MEDICAPP`.

Requisitos: JDK 25, Node.js 24 LTS, PostgreSQL 18. Maven se obtiene mediante el wrapper. Las herramientas ya verificadas en esta PC son Java 25.0.4.1, Node 24.21.0 y PostgreSQL 18.6. La primera compilación necesita Internet para dependencias.

```powershell
cd C:\laburo\E-MEDICAPP\E-MEDICAPP
.\scripts\Build.ps1
.\scripts\Start.ps1 -Environment Demo -OpenBrowser
```

Después de compilar, también se puede abrir `AbrirDemo.cmd` con doble clic. El acceso comprueba disponibilidad antes de abrir el navegador. Dirección: `http://127.0.0.1:8082`.

Los scripts utilizan el JDK 25 del proyecto sin cambiar el Java predeterminado de otros proyectos. Para otra instalación, configurar `EMEDICAPP_JAVA_HOME` o pasar `-JavaHome`. Para PostgreSQL, configurar `EMEDICAPP_PG_BIN`. No se adopta ni se detiene automáticamente un proceso ajeno que ocupe los puertos.

La base local propia escucha en `127.0.0.1:55432`. Se inicializa en `.runtime/postgres`, con contraseña aleatoria protegida mediante DPAPI para el usuario de Windows. No se imprime la contraseña ni se guarda en el repositorio. `.runtime` contiene los datos persistidos, archivos privados y registros: conservarla; no es una caché descartable.

### Cuentas exclusivamente de demostración

Contraseña pública sólo DEMO: `DemoMedicapp2026!`.

| Perfil | Persona del escenario | Correo |
|---|---|---|
| Comprador | Lucía Fernández | comprador@demo.invalid |
| Operador de farmacia | Paula Benítez | farmacia@demo.invalid |
| Farmacéutica | Valentina Ríos | farmaceutico@demo.invalid |
| Segundo farmacéutico | Gabriel Ponce | farmaceutico2@demo.invalid |
| Cadete | Santiago Molina | cadete@demo.invalid |
| Administración | Marina Suárez | admin@demo.invalid |

El selector de ingreso muestra el perfil y la persona. El segundo farmacéutico permite completar la revisión de alto valor con dos cuentas distintas. Lucía tiene fecha de nacimiento `1990-01-01`; para comprar para sí misma, su identidad se toma de la cuenta.

El escenario actualizado contiene 6 personas, 18 presentaciones, 4 sucursales, 72 registros de stock y 72 lotes. Las cantidades varían por sucursal e incluyen faltantes para demostrar alternativas y división. Los precios incluyen centavos, diferencias entre sucursales y un medicamento de alto valor. Los 4 documentos iniciales respaldan capacidades simuladas; la base de presentación se inicia sin compras ni pagos de pruebas.

Las sucursales del escenario son **Nivalis · Ciudad**, **Nivalis · Dorrego**, **Valnera · Godoy Cruz** y **Luma Cuyo · Las Heras**, con direcciones de Mendoza y condiciones operativas diferenciadas. Los medicamentos tienen nombre, concentración y presentación completos; las marcas comerciales, comercios y personas son ficticios. Los importes y requisitos del escenario no son una lista de precios ni una guía de dispensación real.

Un único aviso visible identifica el modo DEMO. Los documentos de muestra conservan su identificación como simulaciones. Los registros que realices en la demostración persisten; las pruebas automatizadas de navegador utilizan una base aparte para mantener limpio el escenario de presentación.

El [guion de demostración](docs/guion-demostracion.txt) guía una presentación de 10 a 15 minutos con perfiles separados, importes y PDF coherentes: retiro completo de una caja, alternativa de entrega y caso opcional de familiar con receta, cobertura, doble revisión y reintegro.

Para renovar el escenario de demostración:

```powershell
.\scripts\ResetDemo.ps1 -OpenBrowser
```

El script primero genera un respaldo, detiene únicamente la aplicación DEMO propia y archiva su base anterior con un nombre fechado. También conserva los documentos en su directorio de archivo y crea una nueva base DEMO. No ejecuta `DROP` ni modifica MANUAL. La ubicación del archivo queda en `.runtime/demo-reset.json`; los respaldos se guardan en `.runtime/backups`. `-NoStart` prepara la nueva base y permite iniciarla después con `AbrirDemo.cmd`.

Para detener la aplicación propia:

```powershell
.\scripts\Stop.ps1 -Environment Demo
```

## Operación manual con datos propios

Detener DEMO antes de usar el mismo puerto. Abrir `AbrirOperacionManual.cmd` o ejecutar:

```powershell
.\scripts\Start.ps1 -Environment Manual -OpenBrowser
```

El primer inicio solicita correo y contraseña propios del administrador. También acepta `EMEDICAPP_ADMIN_EMAIL` y `EMEDICAPP_ADMIN_PASSWORD` en el entorno. La contraseña se captura de forma oculta y no se guarda en los scripts. MANUAL utiliza `emedicapp_manual` y archivos separados, sin insertar farmacias, medicamentos ni credenciales DEMO.

Configuración inicial:

1. Ingresar como administrador y cargar cadenas y sucursales reales, responsables, horarios, modalidades, zonas operativas y medios de pago de cada farmacia.
2. Las personas se registran como compradores adultos; el administrador les asigna los permisos de sucursal que corresponden. No se concede acceso clínico al administrador por su rol administrativo.
3. Registrar las capacidades verificadas de farmacia y cadete cuando la categoría lo requiera, con documentación que las respalde.
4. Cargar presentaciones comerciales y sus condiciones documentadas; cargar precios y existencias por sucursal, y lotes para categorías que requieren trazabilidad.
5. Configurar los planes y contratos con importes reales positivos y moneda. Registrar períodos mensuales efectivamente cobrados con referencia y evidencia. Las U.M. no activan cobros reales.
6. Revisar los parámetros operativos y hacer una primera compra controlada entre usuarios habilitados.

Este MVP coordina operaciones y conserva comprobaciones manuales. No confirma por sí mismo la autenticidad de una receta, autorización de obra social, habilitación sanitaria, factura fiscal o transferencia bancaria. Esas verificaciones las realiza el responsable en su fuente externa y registra la evidencia.

## Pruebas

Los resultados de la revisión actual, el escenario y los recorridos están en
[la revisión de DEMO y flujos](docs/revision-demo-flujos.txt).
El [reporte de verificación inicial](docs/verificacion-mvp.txt) se conserva como
evidencia histórica de la primera fase; sus cantidades y capturas pertenecen al
escenario anterior.

```powershell
.\scripts\Test.ps1
npm.cmd --prefix frontend test
# Con la base local propia iniciada y el proyecto compilado:
.\scripts\Test-Workflow.ps1
```

`Test.ps1` prepara `emedicapp_test_demo`, independiente de las bases de operación. Las pruebas de integración requieren PostgreSQL real; una ejecución de Maven sin las variables de prueba informa esos casos como omitidos. La compilación sola no prueba la operación de la base.

`Test-Workflow.ps1` crea una base QA nueva y un directorio de documentos propios en cada invocación. Inicia la aplicación en `http://127.0.0.1:8083`, espera disponibilidad, ejecuta los recorridos Playwright y detiene sólo el proceso que inició. La base y las evidencias QA se conservan para revisión; el estado queda en `.runtime/workflow-*.json` y la salida del navegador en `.runtime/verification-browser.log`.

El puerto 8083 debe estar libre. El script no adopta ni detiene otra aplicación. La ejecución directa de `npm.cmd --prefix frontend run test:e2e` se niega a operar contra la base de presentación de 8082: el recorrido exige el entorno aislado preparado por el script.

La primera instalación del navegador de prueba se hace con `npx.cmd playwright install chromium` dentro de `frontend`.

## Respaldo

```powershell
.\scripts\Backup.ps1 -Environment Manual
```

Se genera un directorio con dump PostgreSQL, documentos privados, índice del dump y manifiesto SHA-256. Para otra ubicación usar `-Destination`. El respaldo contiene datos de la operación y debe guardarse en un destino privado con acceso restringido. Las credenciales DPAPI locales no forman parte del respaldo.

La restauración se realiza sobre una base nueva mediante `pg_restore`, con PostgreSQL de la misma versión principal y los documentos en su directorio privado. No se incluyen acciones automáticas de borrado o reemplazo de bases existentes. Para una prueba de recuperación, comparar el número de compras y documentos, comprobar los hashes y abrir los documentos referenciados desde la aplicación antes de utilizar el entorno restaurado.

## Desarrollo y despliegue posterior

Para publicar la operación real desde esta PC, usar `ConfigurarPublicacion.cmd`
y después `PublicarEMedicapp.cmd`. El acceso HTTPS de ngrok se verifica antes de
informar disponibilidad y apunta a MANUAL en 8084, con base y documentos separados
del DEMO. `DetenerPublicacion.cmd` cierra solamente esa publicación. La guía de
cuenta, secretos, carga inicial, respaldos y límites está en
[publicación desde esta PC](docs/publicacion-pc.txt).

La operación MANUAL se publicó y verificó el 08/10/2026 en
[tamper-raving-aversion.ngrok-free.dev](https://tamper-raving-aversion.ngrok-free.dev/).
La [verificación de publicación](docs/verificacion-publicacion-pc.txt) registra
las comprobaciones HTTPS, el estado inicial de la base real y el primer respaldo.

Durante desarrollo: iniciar el backend y, en otra terminal, `npm.cmd --prefix frontend run dev`. Vite escucha en `127.0.0.1:5174` y envía `/api` al backend local. La ejecución diaria utiliza el JAR compilado con el frontend incluido.

El mismo JAR puede desplegarse en un servidor Java 25 con PostgreSQL 18 y almacenamiento persistente para documentos. Los parámetros se suministran desde el entorno:

| Variable | Uso |
|---|---|
| EMEDICAPP_MODE | DEMO o MANUAL; la base debe tener el sufijo correspondiente |
| EMEDICAPP_DB_URL / EMEDICAPP_DB_USER / EMEDICAPP_DB_PASSWORD | Conexión a PostgreSQL |
| EMEDICAPP_DOCUMENTS_DIR | Directorio privado persistente, fuera del frontend |
| EMEDICAPP_ADDRESS / EMEDICAPP_PORT | Dirección y puerto de escucha |
| EMEDICAPP_SECURE_COOKIES | `true` para acceso HTTPS |
| EMEDICAPP_BUSINESS_ZONE | Zona de fechas del negocio; por defecto `America/Argentina/Mendoza` |
| EMEDICAPP_ADMIN_EMAIL / EMEDICAPP_ADMIN_PASSWORD | Primer administrador de un entorno MANUAL nuevo |

El acceso público desde esta PC utiliza el túnel HTTPS descrito arriba, la base MANUAL y documentos privados. La geolocalización del navegador necesita HTTPS fuera de localhost. El mapa requiere Internet; las ubicaciones también pueden marcarse manualmente. No se modificó el firewall ni se abrieron puertos del router.

## Límites de esta primera versión

- Stock, recetas, cobertura, factura, pagos y suscripción utilizan carga y verificación manual. No hay conectores reales a emisores, obras sociales, ERP, bancos o ARCA.
- La reserva protege el stock registrado en E-MEDICAPP. Sin integración con el sistema de la farmacia, una venta simultánea de mostrador puede cambiar la existencia física; la farmacia debe confirmarla y conciliarla antes de dispensar.
- Las distancias se calculan geográficamente para búsqueda; no son rutas de tránsito ni tiempos garantizados de entrega.
- La disponibilidad combina un radio por sucursal y una franja diaria configurada. Todavía no hay un calendario de feriados, turnos rotativos ni varias franjas por día.
- La ubicación del cadete se actualiza cuando éste la informa durante una tarea activa; no se promete seguimiento continuo en segundo plano.
- La notificación inmediata se muestra dentro de la aplicación y las pantallas actualizan su estado. No se envían WhatsApp, correo ni SMS.
- El esquema MVP implementa el circuito operativo. Los módulos más amplios del relevamiento permanecen como evolución y no se presentan como implementados.
- La preparación de un piloto real incluye cargar y verificar la operación de cada farmacia y acordar sus procedimientos. Los datos DEMO no acreditan convenios ni condiciones de una farmacia real.

## Referencias de las versiones elegidas

- [Oracle: hoja de soporte Java y versiones LTS](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Spring Boot: requisitos del sistema](https://docs.spring.io/spring-boot/system-requirements.html)
- [React: versiones publicadas](https://react.dev/versions)
- [PostgreSQL: política y versiones mantenidas](https://www.postgresql.org/support/versioning/)
- [Node.js: versiones anteriores y LTS](https://nodejs.org/en/about/previous-releases)
