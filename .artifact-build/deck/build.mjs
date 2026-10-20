import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const runtimeModules = 'C:/Users/Jerem/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const { Presentation, PresentationFile, FileBlob } = await import(pathToFileURL(path.join(runtimeModules,'@oai/artifact-tool/dist/artifact_tool.mjs')).href);
const skillDir = 'C:/Users/Jerem/.codex/plugins/cache/openai-primary-runtime/presentations/26.1017.11052/skills/presentations';
const { finalizePresentation, resolvePresentationFont } = await import(pathToFileURL(path.join(skillDir,'container_tools/artifact_tool_utils.mjs')).href);
const root='C:/laburo/E-MEDICAPP';
const build=path.join(root,'.artifact-build/deck');
const final=path.join(root,'outputs/presentacion_20261019/03_Pitch_Deck_EMEDICAPP.pptx');
const font=resolvePresentationFont({fontFamily:'Arial'});
const C={paper:'#F5F5EF', ink:'#152F2B', green:'#176B5D', muted:'#48625B', light:'#D8E4DD', white:'#FFFFFF'};
const deck=Presentation.create({slideSize:{width:1280,height:720}});
const txt=(s,name,text,x,y,w,h,size=26,color=C.ink,bold=false)=>{
  const t=s.shapes.add({geometry:'textbox',name,position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  t.text=text;t.text.style={fontSize:size,typeface:font,color,bold,wrap:'square',autoFit:'none',insets:0,verticalAlignment:'top'};return t;
};
function slide(title, n, dark=false){
 const s=deck.slides.add();s.background.fill=dark?C.ink:C.paper;
 txt(s,'slide-title',title,64,55,1150,100,46,dark?C.white:C.ink,true);
 txt(s,'page',String(n).padStart(2,'0'),1160,662,56,24,16,dark?C.light:C.muted);
 return s;
}
function note(s,files){s.speakerNotes.text='Fuentes internas. Corte documental: 19 de octubre de 2026.\n'+files.join('\n');}
function row(s,y,label,body){txt(s,`label-${y}`,label,64,y,310,70,29,C.green,true);txt(s,`body-${y}`,body,405,y,795,80,26);}
// 1. Portada
{
 const s=deck.slides.add();s.background.fill=C.ink;
 txt(s,'brand','E-MEDICAPP',64,75,1100,96,76,C.white,true);
 txt(s,'cover-title','Coordinación de compras\ny dispensación farmacéutica',64,230,1120,200,53,C.white,true);
 txt(s,'cover-status','MVP de operación manual',66,505,1030,50,30,C.light);
 txt(s,'cover-date','Presentación del proyecto · 19 de octubre de 2026',66,632,1120,32,21,C.light);
 note(s,['README.md','docs/verificacion-pages-demo-20261019.txt']);
}
// 2. Necesidad operativa
{
 const s=slide('La operación que organiza',2);
 txt(s,'intro','La propuesta reúne el circuito de una compra en un registro compartido por sus responsables.',64,156,1140,75,31);
 row(s,273,'Comprador','Solicitar para sí o para un familiar adulto, revisar el presupuesto y conocer el estado.');
 row(s,383,'Farmacia','Comprobar condiciones, cotizar, registrar el cobro y documentar la dispensación.');
 row(s,493,'Entrega o retiro','Vincular el paquete, la custodia y la recepción con la compra original.');
 txt(s,'hypothesis','La necesidad comercial y la disposición de pago requieren validación en un piloto real.',64,612,1110,40,23,C.muted);
 note(s,['README.md, sección «Qué se puede demostrar»','docs/flujo-producto-QA.txt','docs/manuales/01-comprador.md','docs/manuales/04-farmaceutico.md','docs/manuales/06-cadete.md']);
}
// 3. Producto con captura real de demo sintética
{
 const s=slide('Producto disponible',3);
 txt(s,'product-lead','Una aplicación web con tareas según el perfil',64,169,725,105,37,C.green,true);
 txt(s,'product-copy','El comprador crea solicitudes y acepta condiciones.\n\nLa farmacia revisa y procesa pedidos.\n\nEl cadete registra la custodia y la entrega.\n\nLa administración gestiona sucursales, catálogo y accesos.',64,305,710,320,28);
 const bytes=await fs.readFile(path.join(root,'docs/capturas/pages-demo-20261019.png'));
 s.images.add({blob:bytes,contentType:'image/png',alt:'Entorno DEMO de E-MEDICAPP. Pantalla móvil de Mis compras, con cero compras registradas.',fit:'contain',position:{left:880,top:153,width:224,height:516}});
 txt(s,'demo-caption','Entorno DEMO',857,120,280,27,20,C.green,true);
 note(s,['README.md','docs/verificacion-pages-demo-20261019.txt','Captura: docs/capturas/pages-demo-20261019.png. Datos de ejemplo.']);
}
// 4. Circuito operativo, tipografía editable
{
 const s=slide('Circuito de una compra',4);
 const steps=[
 ['01','Solicitud','Beneficiario adulto, productos, entrega o retiro y documentación.'],
 ['02','Revisión y presupuesto','Farmacia, receta y cobertura. Reserva de stock, lote y saldo de receta.'],
 ['03','Aceptación','El comprador confirma la propuesta completa y sus condiciones.'],
 ['04','Cobro y factura','La farmacia comprueba los fondos y adjunta su factura externa.'],
 ['05','Preparación y dispensación','El equipo registra controles y la actuación profesional.'],
 ['06','Recepción','Retiro o entrega con custodia, identidad observada y código temporal.'],
 ];
 steps.forEach(([n,t,b],i)=>{
  const x=i<3?64:665, j=i%3, y=180+j*153;
  txt(s,`step-${n}`,n,x,y,75,55,43,C.green,true);
  txt(s,`step-title-${n}`,t,x+95,y,460,50,28,C.ink,true);
  txt(s,`step-copy-${n}`,b,x+95,y+52,460,91,24);
 });
 note(s,['README.md, circuito operativo actual','docs/flujo-producto-QA.txt','docs/manuales/05-operador-farmacia.md','docs/manuales/04-farmaceutico.md','docs/manuales/06-cadete.md']);
}
// 5. Controles implementados
{
 const s=slide('Controles implementados',5);
 row(s,184,'Reservas','Transacciones de stock, lote y receta. El vencimiento libera reservas.');
 row(s,296,'Consentimiento','El comprador acepta cambios de productos y un nuevo presupuesto ante cambios materiales.');
 row(s,408,'Responsabilidad','Permisos por rol y sucursal, versiones y evidencia. Alto valor con dos profesionales distintos.');
 row(s,520,'Incidencias','Cancelación, conciliación, reintegro y reposición vinculados a la operación original.');
 txt(s,'manual-control','Las comprobaciones clínicas, comerciales y físicas corresponden a los responsables de la operación.',64,635,1110,38,21,C.muted);
 note(s,['README.md','docs/verificacion-producto-QA.txt','backend/src/main/java/ar/emedicapp/purchase/PurchaseService.java','backend/src/main/java/ar/emedicapp/catalog/CatalogService.java']);
}
// 6. Evidencia técnica con cifras documentadas, sin uso comercial
{
 const s=slide('Implementación y evidencia técnica',6);
 const metrics=[['184','pruebas de servidor'],['18','pruebas de interfaz'],['17','recorridos de navegador']];
 metrics.forEach(([v,t],i)=>{const x=64+i*403;txt(s,`metric-${i}`,v,x,180,350,115,89,C.green,true);txt(s,`metric-label-${i}`,t,x,306,350,68,27);});
 txt(s,'qa-status','Verificación documentada del 19/10/2026: aprobadas, sin fallos de servidor ni reintentos de navegador.',64,393,1135,80,26);
 txt(s,'architecture','React y TypeScript en la web. Java y Spring Boot en el backend. PostgreSQL con migraciones.',64,502,1120,78,27);
 txt(s,'deployment','La demo compartida usa GitHub Pages y un túnel HTTPS hacia el backend de esta PC.',64,607,1100,48,24,C.muted);
 note(s,['docs/verificacion-pages-demo-20261019.txt. 184 servidor, cero fallos/errores/omitidas. 18 unitarias de interfaz. 17 recorridos completos de navegador, sin reintentos.','backend/pom.xml','frontend/package.json','README.md. Estos resultados acreditan pruebas técnicas, no tracción comercial.']);
}
// 7. Negocio
{
 const s=slide('Modelo de ingresos definido',7,true);
 txt(s,'model','Suscripción mensual fija\na la farmacia o red',64,183,1060,155,54,C.white,true);
 txt(s,'payer','La farmacia cobra y factura los medicamentos directamente. También absorbe el costo del envío.',64,383,1095,105,32,C.light);
 txt(s,'buyer','El comprador no paga comisión de plataforma ni envío.',64,521,1100,55,30,C.white,true);
 txt(s,'pricing','El precio contractual real y la moneda se acuerdan por contrato. Las U.M. de los planes son simbólicas.',64,612,1110,54,23,C.light);
 note(s,['README.md, modelo de suscripción y límites','docs/manuales/07-alta-real-de-cuentas-y-farmacias.md, contrato y pago mensual','backend/src/main/java/ar/emedicapp/catalog/CatalogService.java, saveSubscription. Exige cuota real positiva y moneda para activar.']);
}
// 8. Piloto futuro
{
 const s=slide('Piloto propuesto',8);
 txt(s,'future','Próxima etapa: validar la operación con una farmacia participante.',64,162,1140,82,34,C.green,true);
 row(s,278,'Preparación','Acordar alcance, responsables, contrato y procedimientos. Configurar correo y datos reales verificados.');
 row(s,400,'Ejecución','Realizar compras controladas de extremo a extremo y documentar desvíos del circuito.');
 row(s,522,'Evaluación','Medir tiempos por etapa, finalización de pedidos, incidencias y costo de atención y entrega.');
 txt(s,'pilot-evidence','La documentación revisada no acredita convenios, clientes activos, ventas ni cobros comerciales.',64,640,1110,32,21,C.muted);
 note(s,['Propuesta de siguiente etapa, sin ejecución ni participantes acreditados.','README.md, Operación manual con datos propios y límites','docs/manuales/07-alta-real-de-cuentas-y-farmacias.md. Preparación y compra de prueba.','Las métricas del piloto son una propuesta de evaluación, no resultados existentes.']);
}
// 9. Estado y evolución
{
 const s=slide('Alcance actual y evolución',9);
 txt(s,'present-label','Disponible hoy',64,172,500,55,33,C.green,true);
 txt(s,'present','MVP funcional y verificaciones técnicas documentadas.\n\nDemo sintética separada de la operación manual.\n\nManuales por rol y configuración administrativa.',64,258,500,300,28);
 txt(s,'next-label','Preparación para operación real',666,172,530,90,33,C.green,true);
 txt(s,'next','Correo transaccional configurado.\n\nInfraestructura persistente y procedimientos de respaldo.\n\nFarmacias, equipos y condiciones acreditados.\n\nIntegraciones externas como evolución posterior.',666,285,525,320,27);
 txt(s,'limits','Stock, receta, cobertura, pagos y facturas conservan comprobación manual. El MVP no dispone de conectores reales a esos sistemas.',64,622,1140,62,23,C.muted);
 note(s,['README.md, Operación manual, Respaldo y Límites de esta primera versión','docs/verificacion-pages-demo-20261019.txt, SMTP pendiente e infraestructura de demo','docs/manuales/07-alta-real-de-cuentas-y-farmacias.md']);
}
await fs.mkdir(build,{recursive:true});
if (process.env.EMEDICAPP_PDF_EXPORT_PAGES === '1') {
 const pdfPages = path.join(build, 'pdf-pages');
 await fs.mkdir(pdfPages, {recursive:true});
 for (let i=0;i<deck.slides.items.length;i++) {
  const png = await deck.export({slide:deck.slides.items[i],format:'png',scale:2});
  await fs.writeFile(path.join(pdfPages,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await png.arrayBuffer()));
 }
 console.log(JSON.stringify({pdfPages, slides:deck.slides.items.length}));
 process.exit(0);
}
const draft=path.join(build,'draft.pptx');
await (await PresentationFile.exportPptx(deck)).save(draft);
await fs.writeFile(path.join(build,'deck.proto.json'),JSON.stringify(deck.toProto()));
for(let i=0;i<deck.slides.items.length;i++){
 const png=await deck.export({slide:deck.slides.items[i],format:'png',scale:1});
 await fs.writeFile(path.join(build,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await png.arrayBuffer()));
}
const result=await finalizePresentation({workspaceDir:root,candidatePath:draft,finalPath:final,pythonExecutable:'C:/Users/Jerem/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',integrityValidatorPath:path.join(skillDir,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skillDir,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit'],explicitTotalSlideCount:9,requiredNativeTableOwnerSlides:[],requiredNativeChartOwnerSlides:[],fontPolicy:{basis:'design',families:[font]},verifyArtifactToolImport:true,receiptPath:path.join(build,'validation.json')});
console.log(JSON.stringify(result,null,2));
console.log('Final bytes:',(await fs.stat(final)).size);
