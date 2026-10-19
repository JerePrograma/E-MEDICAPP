package ar.emedicapp.replacements;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.*;
import ar.emedicapp.payments.FinancialRules;
import ar.emedicapp.purchase.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * A separately consented, pharmacy-funded fulfillment. Original dispenses and receipts stay intact.
 */
@Service
public class ReplacementService {
    private final PurchaseStore s;
    private final IdempotencyService idem;

    public ReplacementService(PurchaseStore s, IdempotencyService idem) {
        this.s = s;
        this.idem = idem;
    }

    private Map<String, Object> request(UUID id, boolean lock) {
        if (lock) {
            var initial = request(id, false);
            ReplacementLifecycle.lockOrder(s, id(initial.get("original_order_id")));
        }
        return s.row(
                "SELECT * FROM replacement_request WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private void reader(Map<String, Object> request) {
        var actor = s.access.actor();
        if (actor.hasRole("BUYER") && Objects.equals(request.get("buyer_user_id"), actor.id()))
            return;
        s.pharmacy(id(request.get("branch_id")), true);
    }

    private void required(String value, String message) {
        if (text(value).isBlank())
            throw AppException.badRequest("REPLACEMENT_EVIDENCE_REQUIRED", message);
    }

    private void operationalEvidence(UUID document, UUID branch) {
        s.evidence(document, branch, "OTHER", "DELIVERY_PROOF");
        if (!s.rows(
                        "SELECT 1 FROM review WHERE evidence_document_id=? AND kind IN"
                                + " ('COVERAGE','SUBSTITUTION')",
                        document)
                .isEmpty())
            throw AppException.forbidden(
                    "CLINICAL_EVIDENCE_NOT_OPERATIONAL",
                    "La reposición requiere evidencia operativa propia; una autorización clínica de"
                            + " cobertura o sustitución no puede publicarse como comprobante de"
                            + " resolución.");
    }

    private boolean sameProductRules(Map<String, Object> product, Map<String, Object> frozen) {
        if (!text(product.get("presentation")).equals(text(frozen.get("presentation")))
                || flag(product.get("requires_prescription"))
                        != flag(frozen.get("requiresPrescription"))
                || flag(product.get("cold_chain")) != flag(frozen.get("coldChain"))
                || flag(product.get("restricted")) != flag(frozen.get("restricted"))) return false;
        Map<String, Object> current = s.json.readMap(text(product.get("metadata")));
        Map<?, ?> old = frozen.get("productMetadata") instanceof Map<?, ?> value ? value : Map.of();
        for (String key : List.of("minTemperatureC", "maxTemperatureC"))
            if ((current.get(key) == null) != (old.get(key) == null)
                    || (current.get(key) != null
                            && amount(current.get(key)).compareTo(amount(old.get(key))) != 0))
                return false;
        return true;
    }

    private void disposition(Map<String, Object> fulfillment, String disposition) {
        if ("DRIVER".equals(fulfillment.get("custody_holder")))
            throw AppException.conflict(
                    "PHYSICAL_RETURN_REQUIRED",
                    "La farmacia debe registrar primero el retorno físico; la custodia del cadete"
                            + " sigue vigente.");
        if (!Set.of("INCIDENT", "RETURNED").contains(text(fulfillment.get("status"))))
            throw AppException.conflict(
                    "INCIDENT_REQUIRED",
                    "La reposición requiere una incidencia farmacéutica abierta.");
        if (!Set.of("RETURNED", "NO_RETURN_DOCUMENTED").contains(text(disposition)))
            throw AppException.badRequest(
                    "REPLACEMENT_DISPOSITION_INVALID",
                    "Documentá el paquete en farmacia o la imposibilidad real de retorno.");
        if ("RETURNED".equals(disposition) && !"PHARMACY".equals(fulfillment.get("custody_holder")))
            throw AppException.conflict(
                    "PHYSICAL_RETURN_REQUIRED",
                    "No se registró la recepción física del producto en la farmacia.");
    }

    @Transactional
    public Map<String, Object> propose(UUID order, ReplacementDtos.Propose r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), true);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "REPLACEMENT_PROPOSE:" + order,
                key,
                r,
                () -> {
                    var o = ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, true);
                    s.version(o, r.expectedVersion());
                    FinancialRules.requireNotCancelling(s, o);
                    if (!Set.of("DISPENSED", "DISPATCHED", "COMPLETED")
                            .contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "REPLACEMENT_STATE_INVALID",
                                "La reposición se propone sobre unidades ya dispensadas, con el"
                                        + " reclamo documentado.");
                    if (FinancialRules.refunds(s, order, true).signum() > 0)
                        throw AppException.conflict(
                                "REPLACEMENT_REFUND_CONFLICT",
                                "El pedido tiene devoluciones financieras registradas; la farmacia"
                                        + " debe resolverlas antes de ofrecer reposición de esas"
                                        + " unidades.");
                    var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
                    disposition(f, r.originalDisposition());
                    required(r.reason(), "Registrá el motivo de la reposición.");
                    required(
                            r.dispositionNote(),
                            "Registrá la custodia y disposición farmacéutica del producto"
                                    + " original.");
                    operationalEvidence(r.evidenceDocumentId(), branch);
                    if (o.get("invoice_document_id") == null)
                        throw AppException.conflict(
                                "INVOICE_REQUIRED",
                                "Registrá la factura original emitida externamente antes de"
                                        + " vincular la reposición.");
                    if (r.lines() == null || r.lines().isEmpty())
                        throw AppException.badRequest(
                                "REPLACEMENT_LINES_REQUIRED",
                                "Seleccioná las líneas y cantidades afectadas.");
                    if (!Set.of("DELIVERY", "PICKUP").contains(text(r.mode())))
                        throw AppException.badRequest(
                                "REPLACEMENT_MODE_INVALID",
                                "Elegí entrega o retiro para la reposición.");
                    if (r.deliveryMinutes() == null
                            || r.deliveryMinutes() < 1
                            || r.deliveryMinutes() > 10080)
                        throw AppException.badRequest(
                                "REPLACEMENT_TIME_REQUIRED",
                                "Registrá el plazo propuesto en minutos.");
                    var p = s.purchase(purchase, false);
                    var b = s.row("SELECT * FROM branch WHERE id=?", branch);
                    if (!flag(b.get("active"))
                            || !flag(b.get("verified"))
                            || !flag(
                                    b.get(
                                            "DELIVERY".equals(r.mode())
                                                    ? "delivery_enabled"
                                                    : "pickup_enabled")))
                        throw AppException.conflict(
                                "BRANCH_NOT_ELIGIBLE",
                                "La farmacia no está habilitada para esa modalidad de reposición.");
                    BigDecimal
                            lat = r.latitude() == null ? amount(p.get("latitude")) : r.latitude(),
                            lon =
                                    r.longitude() == null
                                            ? amount(p.get("longitude"))
                                            : r.longitude();
                    String address =
                            text(r.deliveryAddress()).isBlank()
                                    ? text(p.get("delivery_address"))
                                    : r.deliveryAddress().trim();
                    if ("DELIVERY".equals(r.mode()) && address.isBlank())
                        throw AppException.badRequest(
                                "REPLACEMENT_ADDRESS_REQUIRED",
                                "Indicá el domicilio propuesto para la reposición.");
                    if (lat.abs().compareTo(new BigDecimal("90")) > 0
                            || lon.abs().compareTo(new BigDecimal("180")) > 0)
                        throw AppException.badRequest(
                                "REPLACEMENT_LOCATION_INVALID", "La ubicación debe ser válida.");
                    UUID requestId = UUID.randomUUID();
                    Set<UUID> selected = new HashSet<>();
                    List<Map<String, Object>> lines = new ArrayList<>();
                    BigDecimal commercial = BigDecimal.ZERO;
                    boolean rx = false;
                    for (var line : r.lines()) {
                        if (line.lineId() == null
                                || !selected.add(line.lineId())
                                || line.quantity() < 1)
                            throw AppException.badRequest(
                                    "REPLACEMENT_LINE_INVALID",
                                    "Cada línea afectada debe aparecer una vez con una cantidad"
                                            + " positiva.");
                        var item =
                                s.items(order).stream()
                                        .filter(i -> Objects.equals(i.get("id"), line.lineId()))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        AppException.badRequest(
                                                                "REPLACEMENT_LINE_INVALID",
                                                                "La línea no pertenece al pedido"
                                                                        + " original."));
                        long replaced =
                                num(
                                        s.row(
                                                        "SELECT coalesce(sum(l.quantity),0)"
                                                            + " quantity FROM replacement_line l"
                                                            + " JOIN replacement_request r ON"
                                                            + " r.id=l.replacement_id WHERE"
                                                            + " l.original_order_item_id=? AND"
                                                            + " r.status IN"
                                                            + " ('ACCEPTED','READY','FULFILLED')",
                                                        line.lineId())
                                                .get("quantity"));
                        if (replaced + line.quantity() > num(item.get("dispensed_quantity")))
                            throw AppException.conflict(
                                    "REPLACEMENT_EXCEEDS_DISPENSED",
                                    "La cantidad acumulada de reposiciones no puede superar las"
                                            + " unidades originales afectadas ya dispensadas.");
                        var stock =
                                s.row(
                                        "SELECT * FROM branch_stock WHERE branch_id=? AND"
                                                + " product_id=?",
                                        branch,
                                        item.get("product_id"));
                        var product =
                                s.row("SELECT * FROM product WHERE id=?", item.get("product_id"));
                        var snapshot =
                                map(
                                        "name",
                                        item.get("name"),
                                        "presentation",
                                        item.get("presentation"),
                                        "requiresPrescription",
                                        item.get("requires_prescription"),
                                        "coldChain",
                                        item.get("cold_chain"),
                                        "restricted",
                                        item.get("restricted"),
                                        "productMetadata",
                                        s.json.readMap(text(item.get("metadata"))),
                                        "stockPrice",
                                        text(stock.get("price")));
                        if (!flag(product.get("active")) || !sameProductRules(product, snapshot))
                            throw AppException.conflict(
                                    "REPLACEMENT_PRODUCT_CHANGED",
                                    "La presentación o las condiciones de receta y conservación"
                                            + " cambiaron; hace falta una evaluación nueva, sin"
                                            + " reposición silenciosa.");
                        if (!flag(stock.get("active"))
                                || !text(stock.get("currency"))
                                        .trim()
                                        .equals(text(o.get("currency")).trim()))
                            throw AppException.conflict(
                                    "REPLACEMENT_STOCK_CHANGED",
                                    "El stock está deshabilitado o cambió su moneda; la farmacia"
                                            + " debe reevaluar las condiciones.");
                        BigDecimal price =
                                amount(s.snapshot(item).get("unitPrice"))
                                        .max(amount(stock.get("price")));
                        commercial =
                                commercial.add(price.multiply(BigDecimal.valueOf(line.quantity())));
                        rx |= flag(item.get("requires_prescription"));
                        lines.add(
                                map(
                                        "id",
                                        UUID.randomUUID(),
                                        "lineId",
                                        line.lineId(),
                                        "productId",
                                        item.get("product_id"),
                                        "quantity",
                                        line.quantity(),
                                        "commercialUnitPrice",
                                        price,
                                        "snapshot",
                                        snapshot));
                    }
                    s.exec(
                            "UPDATE replacement_request SET"
                                    + " status='EXPIRED',version=version+1,updated_at=now() WHERE"
                                    + " original_order_id=? AND status='PROPOSED' AND"
                                    + " expires_at<=now()",
                            order);
                    if (!s.rows(
                                    "SELECT 1 FROM replacement_request WHERE original_order_id=?"
                                            + " AND status IN ('PROPOSED','ACCEPTED','READY')",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "REPLACEMENT_ALREADY_OPEN",
                                "La farmacia debe resolver la reposición vigente antes de proponer"
                                        + " otra.");
                    FinancialRules.checkedAmount(
                            commercial,
                            text(o.get("currency")).trim(),
                            text(o.get("currency")).trim(),
                            true);
                    var snapshot =
                            map(
                                    "mode",
                                    r.mode(),
                                    "address",
                                    address,
                                    "latitude",
                                    lat,
                                    "longitude",
                                    lon,
                                    "deliveryMinutes",
                                    r.deliveryMinutes(),
                                    "requiresPrescription",
                                    rx,
                                    "requiresAuthorization",
                                    p.get("family_authorization_id") != null,
                                    "commercialValue",
                                    commercial,
                                    "currency",
                                    text(o.get("currency")).trim(),
                                    "highValueRequired",
                                    commercial.compareTo(s.config.current().highValueAmount()) >= 0,
                                    "config",
                                    s.config.snapshot(),
                                    "originalPurchaseId",
                                    purchase,
                                    "originalInvoiceDocumentId",
                                    o.get("invoice_document_id"),
                                    "originalRetryCount",
                                    f.get("retry_count"));
                    s.exec(
                            "INSERT INTO"
                                + " replacement_request(id,original_order_id,original_fulfillment_id,buyer_user_id,branch_id,status,original_disposition,reason,disposition_note,evidence_document_id,proposed_by,expires_at,snapshot)"
                                + " VALUES(?,?,?,?,?,'PROPOSED',?,?,?,?,?,now()+make_interval(mins"
                                + " => ?),cast(? as jsonb))",
                            requestId,
                            order,
                            f.get("id"),
                            p.get("buyer_user_id"),
                            branch,
                            r.originalDisposition(),
                            r.reason(),
                            r.dispositionNote(),
                            r.evidenceDocumentId(),
                            actor.id(),
                            s.config.current().quoteMinutes(),
                            s.json.encode(snapshot));
                    for (var line : lines)
                        s.exec(
                                "INSERT INTO"
                                    + " replacement_line(id,replacement_id,original_order_item_id,product_id,quantity,commercial_unit_price,snapshot)"
                                    + " VALUES(?,?,?,?,?,?,cast(? as jsonb))",
                                line.get("id"),
                                requestId,
                                line.get("lineId"),
                                line.get("productId"),
                                line.get("quantity"),
                                line.get("commercialUnitPrice"),
                                s.json.encode(line.get("snapshot")));
                    s.audit.record(
                            actor,
                            "REPLACEMENT_PROPOSED",
                            "child_order",
                            order,
                            map(
                                    "replacementId",
                                    requestId,
                                    "disposition",
                                    r.originalDisposition(),
                                    "noAutomaticRestock",
                                    true,
                                    "customerAmount",
                                    "0.00"));
                    return view(requestId);
                });
    }

    @Transactional
    public Map<String, Object> accept(UUID replacement, ReplacementDtos.Accept r, String key) {
        var initial = request(replacement, false);
        s.buyer(
                s.purchase(
                        id(s.order(id(initial.get("original_order_id")), false).get("purchase_id")),
                        false));
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "REPLACEMENT_ACCEPT:" + replacement,
                key,
                r,
                () -> {
                    var initialRequest = request(replacement, false);
                    var original =
                            ReplacementLifecycle.lockOrder(
                                    s, id(initialRequest.get("original_order_id")));
                    var req = request(replacement, true);
                    s.version(req, r.expectedVersion());
                    if (!"PROPOSED".equals(req.get("status"))
                            || !instant(req.get("expires_at")).isAfter(Instant.now()))
                        throw AppException.conflict(
                                "REPLACEMENT_EXPIRED_OR_CLOSED",
                                "La propuesta de reposición venció o ya fue resuelta.");
                    FinancialRules.requireNotCancelling(s, original);
                    var originalF =
                            s.row(
                                    "SELECT * FROM fulfillment WHERE id=? FOR UPDATE",
                                    req.get("original_fulfillment_id"));
                    disposition(originalF, text(req.get("original_disposition")));
                    if (FinancialRules.refunds(s, id(original.get("id")), true).signum() > 0)
                        throw AppException.conflict(
                                "REPLACEMENT_REFUND_CONFLICT",
                                "Se registró una devolución después de la propuesta; la farmacia"
                                        + " debe evaluar nuevamente la resolución.");
                    var originalP = s.purchase(id(original.get("purchase_id")), false);
                    var beneficiary =
                            s.row(
                                    "SELECT * FROM beneficiary WHERE id=?",
                                    originalP.get("beneficiary_id"));
                    var snapshot = s.snapshot(req);
                    required(r.receiverName(), "Declarás el nombre del receptor de la reposición.");
                    String identity = text(r.receiverIdentity()).replaceAll("[.\\s-]", "");
                    if (!identity.matches("[0-9]{7,12}"))
                        throw AppException.badRequest(
                                "REPLACEMENT_RECEIVER_REQUIRED",
                                "Declarás un DNI válido del receptor de la reposición.");
                    var originalPurchaseSnapshot = s.snapshot(originalP);
                    String beneficiaryIdentity =
                            text(originalPurchaseSnapshot.containsKey("beneficiaryIdentity")
                                            ? originalPurchaseSnapshot.get("beneficiaryIdentity")
                                            : beneficiary.get("identity_reference"))
                                    .replaceAll("[.\\s-]", "");
                    boolean authorization =
                            flag(snapshot.get("requiresAuthorization"))
                                    || !r.receiverName()
                                            .trim()
                                            .equalsIgnoreCase(text(beneficiary.get("name")))
                                    || !identity.equals(beneficiaryIdentity);
                    if (authorization && r.authorizationDocumentId() == null)
                        throw AppException.badRequest(
                                "REPLACEMENT_AUTHORIZATION_REQUIRED",
                                "Adjuntá una nota firmada nueva para comprar y recibir esta"
                                        + " reposición.");
                    if (flag(snapshot.get("requiresPrescription"))
                            && r.prescriptionDocumentId() == null)
                        throw AppException.badRequest(
                                "REPLACEMENT_PRESCRIPTION_REQUIRED",
                                "Adjuntá la receta nueva que la farmacia evaluará antes de"
                                        + " dispensar unidades adicionales.");
                    if (authorization) s.unusedAuthorizationDocument(r.authorizationDocumentId());
                    if (flag(snapshot.get("requiresPrescription"))) {
                        s.ownedDocument(r.prescriptionDocumentId(), "PRESCRIPTION");
                        freshPrescription(r.prescriptionDocumentId());
                    }
                    UUID branch = id(req.get("branch_id"));
                    var b = s.row("SELECT * FROM branch WHERE id=?", branch);
                    if (!flag(b.get("active"))
                            || !flag(b.get("verified"))
                            || !flag(
                                    b.get(
                                            "DELIVERY".equals(snapshot.get("mode"))
                                                    ? "delivery_enabled"
                                                    : "pickup_enabled")))
                        throw AppException.conflict(
                                "BRANCH_NOT_ELIGIBLE",
                                "La farmacia ya no conserva habilitación para esta modalidad de"
                                        + " reposición.");
                    UUID purchase = UUID.randomUUID(),
                            order = UUID.randomUUID(),
                            quote = UUID.randomUUID(),
                            f = UUID.randomUUID(),
                            auth = null,
                            rx = null;
                    if (authorization) {
                        auth = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO"
                                    + " family_authorization(id,beneficiary_id,buyer_user_id,document_id,scope,receiver_name,receiver_identity)"
                                    + " VALUES(?,?,?,?,'BUY_AND_RECEIVE',?,?)",
                                auth,
                                beneficiary.get("id"),
                                actor.id(),
                                r.authorizationDocumentId(),
                                r.receiverName().trim(),
                                identity);
                        s.rememberAuthorizationDocument(auth, r.authorizationDocumentId());
                    }
                    if (flag(snapshot.get("requiresPrescription"))) {
                        rx = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO"
                                    + " prescription(id,owner_user_id,beneficiary_id,document_id,status,metadata)"
                                    + " VALUES(?,?,?,?,'PENDING',cast(? as jsonb))",
                                rx,
                                actor.id(),
                                beneficiary.get("id"),
                                r.prescriptionDocumentId(),
                                s.json.encode(
                                        map(
                                                "replacementRequestId",
                                                replacement,
                                                "rightsNotYetAuthorized",
                                                true)));
                    }
                    var ps =
                            map(
                                    "originalPurchaseId",
                                    originalP.get("id"),
                                    "replacementOfOrderId",
                                    original.get("id"),
                                    "replacementRequestId",
                                    replacement,
                                    "thirdParty",
                                    authorization,
                                    "beneficiaryName",
                                    originalPurchaseSnapshot.getOrDefault(
                                            "beneficiaryName", beneficiary.get("name")),
                                    "beneficiaryIdentity",
                                    beneficiaryIdentity,
                                    "receiverName",
                                    r.receiverName().trim(),
                                    "receiverIdentity",
                                    identity,
                                    "authorizationDocumentId",
                                    authorization ? r.authorizationDocumentId() : null,
                                    "prescriptionDocumentId",
                                    rx == null ? null : r.prescriptionDocumentId(),
                                    "config",
                                    snapshot.get("config"),
                                    "noCustomerCharge",
                                    true);
                    s.exec(
                            "INSERT INTO"
                                + " purchase(id,buyer_user_id,beneficiary_id,family_authorization_id,mode,delivery_address,latitude,longitude,status,total,currency,snapshot)"
                                + " VALUES(?,?,?,?,?,?,?,?,'PAID',0,?,cast(? as jsonb))",
                            purchase,
                            actor.id(),
                            beneficiary.get("id"),
                            auth,
                            snapshot.get("mode"),
                            snapshot.get("address"),
                            amount(snapshot.get("latitude")),
                            amount(snapshot.get("longitude")),
                            snapshot.get("currency"),
                            s.json.encode(ps));
                    s.exec(
                            "INSERT INTO"
                                + " quote(id,purchase_id,version,status,subtotal,total,currency,expires_at,accepted_by,accepted_at,snapshot)"
                                + " VALUES(?,?,1,'ACCEPTED',0,0,?,now()+make_interval(mins =>"
                                + " ?),?,now(),cast(? as jsonb))",
                            quote,
                            purchase,
                            snapshot.get("currency"),
                            s.config.current().reservationMinutes(),
                            actor.id(),
                            s.json.encode(
                                    map(
                                            "replacementRequestId",
                                            replacement,
                                            "pharmacyFunded",
                                            true,
                                            "config",
                                            snapshot.get("config"))));
                    s.exec("UPDATE purchase SET accepted_quote_id=? WHERE id=?", quote, purchase);
                    var os =
                            map(
                                    "replacementRequestId",
                                    replacement,
                                    "replacementReviewPending",
                                    true,
                                    "reviewApproved",
                                    false,
                                    "highValueRequired",
                                    snapshot.get("highValueRequired"),
                                    "commercialValue",
                                    snapshot.get("commercialValue"),
                                    "deliveryMinutes",
                                    snapshot.get("deliveryMinutes"),
                                    "invoiceReference",
                                    s.snapshot(original).get("invoiceReference"),
                                    "originalInvoiceDocumentId",
                                    original.get("invoice_document_id"),
                                    "resolutionEvidenceDocumentId",
                                    req.get("evidence_document_id"),
                                    "noCustomerCharge",
                                    true);
                    s.exec(
                            "INSERT INTO"
                                + " child_order(id,purchase_id,branch_id,status,total,currency,invoice_document_id,accepted_quote_id,snapshot)"
                                + " VALUES(?,?,?,'PAID',0,?,?,?,cast(? as jsonb))",
                            order,
                            purchase,
                            branch,
                            snapshot.get("currency"),
                            original.get("invoice_document_id"),
                            quote,
                            s.json.encode(os));
                    for (var line :
                            s.rows(
                                    "SELECT * FROM replacement_line WHERE replacement_id=? ORDER BY"
                                            + " product_id",
                                    replacement))
                        reserveLine(line, purchase, order, quote, branch, actor);
                    s.exec(
                            "INSERT INTO"
                                + " fulfillment(id,order_id,branch_id,mode,address,receiver_name,receiver_identity,retry_count,snapshot)"
                                + " VALUES(?,?,?,?,?,?,?,?,cast(? as jsonb))",
                            f,
                            order,
                            branch,
                            snapshot.get("mode"),
                            snapshot.get("address"),
                            r.receiverName().trim(),
                            identity,
                            originalF.get("retry_count"),
                            s.json.encode(
                                    map(
                                            "config",
                                            snapshot.get("config"),
                                            "originalOrderId",
                                            original.get("id"),
                                            "replacementRequestId",
                                            replacement)));
                    snapshot.put("requiresAuthorization", authorization);
                    snapshot.put("prescriptionId", rx);
                    snapshot.put(
                            "acceptedReceiver",
                            map("name", r.receiverName().trim(), "identity", identity));
                    s.exec(
                            "UPDATE replacement_request SET"
                                + " status='ACCEPTED',replacement_purchase_id=?,replacement_order_id=?,accepted_at=now(),snapshot=cast(?"
                                + " as jsonb),version=version+1,updated_at=now() WHERE id=?",
                            purchase,
                            order,
                            s.json.encode(snapshot),
                            replacement);
                    var originalSnapshot = s.snapshot(original);
                    originalSnapshot.put("activeReplacementId", replacement);
                    s.exec(
                            "UPDATE child_order SET snapshot=cast(? as"
                                    + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                            s.json.encode(originalSnapshot),
                            original.get("id"));
                    s.audit.record(
                            actor,
                            "REPLACEMENT_ACCEPTED",
                            "replacement_request",
                            replacement,
                            map(
                                    "replacementPurchaseId",
                                    purchase,
                                    "replacementOrderId",
                                    order,
                                    "customerAmount",
                                    "0.00",
                                    "newStockReserved",
                                    true));
                    return view(replacement);
                });
    }

    private void freshPrescription(UUID document) {
        s.rows(
                "SELECT pg_advisory_xact_lock(hashtextextended(sha256,71002)) FROM document WHERE"
                        + " id=?",
                document);
        String query =
                "SELECT 1 FROM prescription p JOIN document old ON old.id=p.document_id JOIN"
                    + " document fresh ON fresh.id=? WHERE p.document_id=fresh.id OR (fresh.sha256"
                    + " IS NOT NULL AND old.sha256=fresh.sha256)";
        if (!s.rows(query, document).isEmpty())
            throw AppException.conflict(
                    "FRESH_REPLACEMENT_DOCUMENT_REQUIRED",
                    "La reposición requiere un documento nuevo para esta compra; no reutiliza la"
                            + " autorización o receta ya empleada.");
    }

    private void reserveLine(
            Map<String, Object> line,
            UUID purchase,
            UUID order,
            UUID quote,
            UUID branch,
            Actor actor) {
        UUID product = id(line.get("product_id"));
        int qty = (int) num(line.get("quantity"));
        var ls = s.snapshot(line);
        var stock =
                s.row(
                        "SELECT * FROM branch_stock WHERE branch_id=? AND product_id=? FOR UPDATE",
                        branch,
                        product);
        if (!flag(stock.get("active"))
                || !text(stock.get("currency"))
                        .trim()
                        .equals(text(s.order(order, false).get("currency")).trim())
                || amount(stock.get("price")).compareTo(amount(ls.get("stockPrice"))) != 0
                || num(stock.get("quantity")) - num(stock.get("reserved_quantity")) < qty)
            throw AppException.conflict(
                    "REPLACEMENT_STOCK_CHANGED",
                    "La disponibilidad o condiciones cambiaron; la farmacia debe emitir otra"
                            + " propuesta sin reservar unidades inexistentes.");
        var current = s.row("SELECT * FROM product WHERE id=?", product);
        if (!flag(current.get("active")) || !sameProductRules(current, ls))
            throw AppException.conflict(
                    "REPLACEMENT_PRODUCT_CHANGED",
                    "El medicamento, presentación o condiciones de receta y conservación cambiaron;"
                            + " no puede reponerse automáticamente.");
        UUID lot = null;
        boolean special = flag(ls.get("coldChain")) || flag(ls.get("restricted"));
        var lots =
                s.rows(
                        "SELECT * FROM stock_lot WHERE branch_id=? AND product_id=? AND"
                                + " status='AVAILABLE' AND quantity-reserved_quantity>=? AND"
                                + " (expires_on IS NULL OR expires_on>=current_date) ORDER BY"
                                + " expires_on NULLS LAST,id FOR UPDATE",
                        branch,
                        product,
                        qty);
        if (!lots.isEmpty()) {
            var selected = lots.getFirst();
            if (special && selected.get("expires_on") == null)
                throw AppException.conflict(
                        "LOT_EXPIRY_REQUIRED", "El lote especial necesita vencimiento verificado.");
            lot = id(selected.get("id"));
        }
        if (lot == null
                && (special
                        || !s.rows(
                                        "SELECT 1 FROM stock_lot WHERE branch_id=? AND"
                                            + " product_id=?",
                                        branch,
                                        product)
                                .isEmpty()))
            throw AppException.conflict(
                    "LOT_UNAVAILABLE",
                    "No hay un lote vigente con cantidad suficiente para la reposición; confirmá"
                            + " lotes y emití una propuesta nueva.");
        UUID purchaseItem = UUID.randomUUID(),
                orderItem = UUID.randomUUID(),
                quoteLine = UUID.randomUUID(),
                reservation = UUID.randomUUID();
        var itemSnapshot = new LinkedHashMap<>(ls);
        itemSnapshot.put("unitPrice", BigDecimal.ZERO);
        itemSnapshot.put("coverageAmount", BigDecimal.ZERO);
        itemSnapshot.put(
                "pharmacyAbsorbedAmount",
                amount(line.get("commercial_unit_price")).multiply(BigDecimal.valueOf(qty)));
        itemSnapshot.put("replacementLineId", line.get("id"));
        s.exec(
                "INSERT INTO purchase_item(id,purchase_id,product_id,quantity) VALUES(?,?,?,?)",
                purchaseItem,
                purchase,
                product,
                qty);
        s.exec(
                "INSERT INTO"
                    + " quote_line(id,quote_id,order_id,purchase_item_id,product_id,quantity,unit_price,total,snapshot)"
                    + " VALUES(?,?,?,?,?,?,0,0,cast(? as jsonb))",
                quoteLine,
                quote,
                order,
                purchaseItem,
                product,
                qty,
                s.json.encode(itemSnapshot));
        s.exec(
                "INSERT INTO"
                    + " order_item(id,order_id,purchase_item_id,product_id,quantity,quote_line_id,snapshot)"
                    + " VALUES(?,?,?,?,?,?,cast(? as jsonb))",
                orderItem,
                order,
                purchaseItem,
                product,
                qty,
                quoteLine,
                s.json.encode(itemSnapshot));
        s.exec(
                "UPDATE branch_stock SET"
                    + " reserved_quantity=reserved_quantity+?,version=version+1,updated_at=now()"
                    + " WHERE branch_id=? AND product_id=?",
                qty,
                branch,
                product);
        if (lot != null)
            s.exec(
                    "UPDATE stock_lot SET reserved_quantity=reserved_quantity+?,version=version+1"
                            + " WHERE id=?",
                    qty,
                    lot);
        s.exec(
                "INSERT INTO"
                    + " reservation(id,order_id,order_item_id,branch_id,product_id,lot_id,quantity,status,expires_at)"
                    + " VALUES(?,?,?,?,?,?,?,'HELD',now()+make_interval(mins => ?))",
                reservation,
                order,
                orderItem,
                branch,
                product,
                lot,
                qty,
                s.config.current().reservationMinutes());
        s.exec(
                "INSERT INTO"
                    + " stock_movement(id,branch_id,product_id,reservation_id,actor_user_id,kind,quantity_delta,reserved_delta,reason)"
                    + " VALUES(?,?,?,?,?,'RESERVE',0,?,'Unidades nuevas para reposición aceptada;"
                    + " sin reingreso del original')",
                UUID.randomUUID(),
                branch,
                product,
                reservation,
                actor.id(),
                qty);
    }

    @Transactional
    public Map<String, Object> review(UUID replacement, ReplacementDtos.Review r, String key) {
        var initial = request(replacement, false);
        s.pharmacy(id(initial.get("branch_id")), true);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "REPLACEMENT_REVIEW:" + replacement,
                key,
                r,
                () -> {
                    var req = request(replacement, true);
                    s.version(req, r.expectedVersion());
                    s.pharmacy(id(req.get("branch_id")), true);
                    if (!"ACCEPTED".equals(req.get("status")))
                        throw AppException.conflict(
                                "REPLACEMENT_REVIEW_STATE",
                                "La reposición no espera una revisión clínica.");
                    var o = ReplacementLifecycle.lockOrder(s, id(req.get("replacement_order_id")));
                    FinancialRules.requireNotCancelling(s, o);
                    if (!"PAID".equals(o.get("status")))
                        throw AppException.conflict(
                                "REPLACEMENT_REVIEW_STATE",
                                "El pedido de reposición ya avanzó o fue cancelado.");
                    if (!s.rows(
                                    "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD'"
                                            + " AND expires_at<=now()",
                                    o.get("id"))
                            .isEmpty())
                        throw AppException.conflict(
                                "REPLACEMENT_RESERVATION_EXPIRED",
                                "La reserva de reposición venció; el sistema libera esas unidades y"
                                        + " la farmacia debe proponer un nuevo acuerdo.");
                    required(
                            r.reason(),
                            "Registrá el resultado de la evaluación farmacéutica real.");
                    operationalEvidence(r.conditionEvidenceDocumentId(), id(req.get("branch_id")));
                    var snapshot = s.snapshot(req);
                    var p = s.purchase(id(o.get("purchase_id")), false);
                    if (flag(snapshot.get("requiresAuthorization"))) {
                        if (!Boolean.TRUE.equals(r.authorizationChecked()))
                            throw AppException.badRequest(
                                    "AUTHORIZATION_REVIEW_REQUIRED",
                                    "Comprobá manualmente la nueva nota de compra y recepción.");
                        if (s.exec(
                                        "UPDATE family_authorization SET"
                                            + " status='APPROVED',reviewed_by=?,reviewed_at=now(),reason=?"
                                            + " WHERE id=? AND status='PENDING' AND"
                                            + " scope='BUY_AND_RECEIVE' AND (expires_at IS NULL OR"
                                            + " expires_at>now())",
                                        actor.id(),
                                        r.reason(),
                                        p.get("family_authorization_id"))
                                != 1)
                            throw AppException.conflict(
                                    "AUTHORIZATION_INVALID",
                                    "La nueva nota fue revocada, venció o ya no espera una revisión"
                                            + " válida.");
                    }
                    if (flag(snapshot.get("requiresPrescription"))) {
                        if (!Boolean.TRUE.equals(r.originVerified())
                                || text(r.prescriptionReference()).isBlank()
                                || text(r.issuer()).isBlank()
                                || r.expiresAt() == null
                                || !r.expiresAt().isAfter(Instant.now()))
                            throw AppException.badRequest(
                                    "REPLACEMENT_PRESCRIPTION_REVIEW_REQUIRED",
                                    "Comprobá el origen, referencia, emisor y vigencia de la receta"
                                        + " nueva; no se recuperan derechos consumidos en la receta"
                                        + " anterior.");
                        UUID rx = id(snapshot.get("prescriptionId"));
                        Map<UUID, Integer> quantities = new HashMap<>();
                        if (r.lines() != null)
                            for (var line : r.lines()) {
                                if (line.lineId() == null
                                        || line.authorizedQuantity() == null
                                        || quantities.put(line.lineId(), line.authorizedQuantity())
                                                != null)
                                    throw AppException.badRequest(
                                            "REPLACEMENT_RX_QUANTITY_REQUIRED",
                                            "Registrá una cantidad autorizada válida por línea de"
                                                    + " la nueva receta.");
                            }
                        if (!"PENDING"
                                .equals(
                                        s.row(
                                                        "SELECT status FROM prescription WHERE id=?"
                                                                + " FOR UPDATE",
                                                        rx)
                                                .get("status")))
                            throw AppException.conflict(
                                    "REPLACEMENT_PRESCRIPTION_REVIEW_REQUIRED",
                                    "La receta nueva ya no conserva el estado pendiente de"
                                            + " evaluación.");
                        for (var line :
                                s.rows(
                                        "SELECT * FROM replacement_line WHERE replacement_id=?"
                                                + " ORDER BY product_id",
                                        replacement)) {
                            if (!flag(s.snapshot(line).get("requiresPrescription"))) continue;
                            Integer authorized =
                                    quantities.remove(id(line.get("original_order_item_id")));
                            int qty = (int) num(line.get("quantity"));
                            if (authorized == null || authorized < qty)
                                throw AppException.badRequest(
                                        "REPLACEMENT_RX_QUANTITY_REQUIRED",
                                        "La cantidad observada en la receta debe cubrir las"
                                                + " unidades nuevas a dispensar.");
                            UUID rxItem = UUID.randomUUID();
                            s.exec(
                                    "INSERT INTO"
                                        + " prescription_item(id,prescription_id,product_id,authorized_quantity,reserved_quantity)"
                                        + " VALUES(?,?,?,?,?)",
                                    rxItem,
                                    rx,
                                    line.get("product_id"),
                                    authorized,
                                    qty);
                            s.exec(
                                    "UPDATE purchase_item SET prescription_item_id=? WHERE"
                                            + " purchase_id=? AND product_id=?",
                                    rxItem,
                                    p.get("id"),
                                    line.get("product_id"));
                            s.exec(
                                    "UPDATE reservation SET prescription_item_id=? WHERE order_id=?"
                                            + " AND product_id=? AND status='HELD'",
                                    rxItem,
                                    o.get("id"),
                                    line.get("product_id"));
                        }
                        if (!quantities.isEmpty())
                            throw AppException.badRequest(
                                    "REPLACEMENT_RX_LINE_INVALID",
                                    "La receta sólo puede autorizar las líneas que requieren receta"
                                            + " en esta reposición.");
                        s.exec(
                                "UPDATE prescription SET"
                                    + " external_reference=?,issuer=?,expires_at=?,provider_verification='MANUALLY_VERIFIED',status='APPROVED',metadata=cast(?"
                                    + " as jsonb) WHERE id=?",
                                r.prescriptionReference().trim(),
                                r.issuer().trim(),
                                java.sql.Timestamp.from(r.expiresAt()),
                                s.json.encode(
                                        map(
                                                "replacementRequestId",
                                                replacement,
                                                "verifiedBy",
                                                actor.id(),
                                                "manualVerification",
                                                true)),
                                rx);
                        s.exec(
                                "INSERT INTO"
                                    + " review(id,order_id,branch_id,prescription_id,kind,status,reviewed_by,reason,evidence_document_id)"
                                    + " VALUES(?,?,?,?,'PRESCRIPTION','APPROVED',?,?,?)",
                                UUID.randomUUID(),
                                o.get("id"),
                                req.get("branch_id"),
                                rx,
                                actor.id(),
                                r.reason(),
                                r.conditionEvidenceDocumentId());
                    }
                    var os = s.snapshot(o);
                    os.put("replacementReviewPending", false);
                    os.put("reviewApproved", true);
                    os.put("reviewCount", 1);
                    os.put("replacementReviewedBy", actor.id());
                    s.exec(
                            "UPDATE child_order SET snapshot=cast(? as"
                                    + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                            s.json.encode(os),
                            o.get("id"));
                    s.exec(
                            "UPDATE replacement_request SET"
                                + " status='READY',version=version+1,updated_at=now() WHERE id=?",
                            replacement);
                    s.audit.record(
                            actor,
                            "REPLACEMENT_CLINICALLY_REVIEWED",
                            "replacement_request",
                            replacement,
                            map(
                                    "newPrescriptionRights",
                                    flag(snapshot.get("requiresPrescription")),
                                    "manualVerification",
                                    true,
                                    "evidenceDocumentId",
                                    r.conditionEvidenceDocumentId()));
                    return view(replacement);
                });
    }

    @Transactional
    public Map<String, Object> reject(UUID replacement, ReplacementDtos.Reject r) {
        var req = request(replacement, true);
        reader(req);
        s.version(req, r.expectedVersion());
        if (!"PROPOSED".equals(req.get("status")))
            throw AppException.conflict(
                    "REPLACEMENT_REJECTION_STATE",
                    "Sólo puede rechazarse una propuesta pendiente; un pedido aceptado usa el"
                            + " circuito de cancelación.");
        required(r.reason(), "Registrá el motivo de rechazo de la propuesta.");
        s.exec(
                "UPDATE replacement_request SET"
                        + " status='REJECTED',version=version+1,updated_at=now() WHERE id=?",
                replacement);
        s.audit.record(
                s.access.actor(),
                "REPLACEMENT_REJECTED",
                "replacement_request",
                replacement,
                map("reason", r.reason()));
        return view(replacement);
    }

    public Map<String, Object> view(UUID replacement) {
        var r = request(replacement, false);
        reader(r);
        var snapshot = s.snapshot(r);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var row :
                s.rows(
                        "SELECT * FROM replacement_line WHERE replacement_id=? ORDER BY id",
                        replacement)) {
            var item = s.snapshot(row);
            Map<?, ?> meta =
                    item.get("productMetadata") instanceof Map<?, ?> value ? value : Map.of();
            lines.add(
                    map(
                            "lineId",
                            row.get("original_order_item_id"),
                            "productId",
                            row.get("product_id"),
                            "name",
                            item.get("name"),
                            "presentation",
                            item.get("presentation"),
                            "quantity",
                            row.get("quantity"),
                            "commercialUnitPrice",
                            text(row.get("commercial_unit_price")),
                            "coldChain",
                            item.get("coldChain"),
                            "restricted",
                            item.get("restricted"),
                            "requiresPrescription",
                            item.get("requiresPrescription"),
                            "minTemperatureC",
                            meta.get("minTemperatureC"),
                            "maxTemperatureC",
                            meta.get("maxTemperatureC")));
        }
        String status = text(r.get("status"));
        if ("PROPOSED".equals(status) && !instant(r.get("expires_at")).isAfter(Instant.now()))
            status = "EXPIRED";
        boolean clinicalEvidence =
                !s.rows(
                                "SELECT 1 FROM review WHERE evidence_document_id=? AND kind IN"
                                        + " ('COVERAGE','SUBSTITUTION')",
                                r.get("evidence_document_id"))
                        .isEmpty();
        Object evidence =
                clinicalEvidence && !s.branchRole(id(r.get("branch_id")), "PHARMACIST")
                        ? null
                        : r.get("evidence_document_id");
        return map(
                "id",
                r.get("id"),
                "version",
                r.get("version"),
                "status",
                status,
                "originalOrderId",
                r.get("original_order_id"),
                "replacementPurchaseId",
                r.get("replacement_purchase_id"),
                "replacementOrderId",
                r.get("replacement_order_id"),
                "requiresPrescription",
                snapshot.get("requiresPrescription"),
                "requiresAuthorization",
                snapshot.get("requiresAuthorization"),
                "lines",
                lines,
                "mode",
                snapshot.get("mode"),
                "address",
                snapshot.get("address"),
                "latitude",
                snapshot.get("latitude"),
                "longitude",
                snapshot.get("longitude"),
                "deliveryMinutes",
                snapshot.get("deliveryMinutes"),
                "commercialValue",
                text(snapshot.get("commercialValue")),
                "currency",
                snapshot.get("currency"),
                "customerAmount",
                "0.00",
                "reason",
                r.get("reason"),
                "originalDisposition",
                r.get("original_disposition"),
                "dispositionNote",
                r.get("disposition_note"),
                "evidenceDocumentId",
                evidence,
                "originalInvoiceDocumentId",
                snapshot.get("originalInvoiceDocumentId"),
                "expiresAt",
                r.get("expires_at"));
    }

    public List<Map<String, Object>> list(UUID order) {
        var o = s.order(order, false);
        var p = s.purchase(id(o.get("purchase_id")), false);
        Actor actor = s.access.actor();
        if (actor.hasRole("BUYER") && Objects.equals(p.get("buyer_user_id"), actor.id())) {
        } else s.pharmacy(id(o.get("branch_id")), true);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row :
                s.rows(
                        "SELECT id FROM replacement_request WHERE original_order_id=? ORDER BY"
                                + " created_at DESC",
                        order)) result.add(view(id(row.get("id"))));
        return result;
    }

    @Transactional
    public boolean expire(UUID replacement) {
        var initial = request(replacement, false);
        if ("PROPOSED".equals(initial.get("status"))) {
            ReplacementLifecycle.lockOrder(s, id(initial.get("original_order_id")));
            var req = request(replacement, true);
            if (!"PROPOSED".equals(req.get("status"))
                    || instant(req.get("expires_at")).isAfter(Instant.now())) return false;
            s.exec(
                    "UPDATE replacement_request SET"
                            + " status='EXPIRED',version=version+1,updated_at=now() WHERE id=?",
                    replacement);
            s.audit.record(
                    null,
                    "REPLACEMENT_PROPOSAL_EXPIRED",
                    "replacement_request",
                    replacement,
                    map("system", true, "noReservationCreated", true));
            return true;
        }
        if (initial.get("replacement_order_id") == null) return false;
        var req = request(replacement, true);
        var o = ReplacementLifecycle.lockOrder(s, id(req.get("replacement_order_id")));
        if (!Set.of("ACCEPTED", "READY").contains(text(req.get("status")))
                || !Set.of("PAID", "PREPARING").contains(text(o.get("status")))
                || amount(o.get("total")).signum() != 0) return false;
        var f =
                s.row(
                        "SELECT status,custody_holder FROM fulfillment WHERE order_id=? FOR UPDATE",
                        o.get("id"));
        if (!Set.of("PENDING", "PREPARING").contains(text(f.get("status")))
                || !"PHARMACY".equals(f.get("custody_holder"))) return false;
        if (!s.rows(
                                "SELECT 1 FROM order_item WHERE order_id=? AND"
                                        + " dispensed_quantity>0",
                                o.get("id"))
                        .isEmpty()
                || !s.rows("SELECT 1 FROM payment WHERE order_id=?", o.get("id")).isEmpty())
            return false;
        if (s.rows(
                        "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD' AND"
                                + " expires_at<=now()",
                        o.get("id"))
                .isEmpty()) return false;
        s.release(
                id(o.get("id")),
                null,
                "Vencimiento de reserva de reposición sin cargo antes de dispensar");
        s.exec(
                "UPDATE child_order SET"
                    + " status='CANCELLED',snapshot=jsonb_set(snapshot,'{cancellationPending}','false'),version=version+1,updated_at=now()"
                    + " WHERE id=?",
                o.get("id"));
        s.exec(
                "UPDATE fulfillment SET"
                    + " status='CANCELLED',driver_user_id=NULL,proof_code_hash=NULL,version=version+1,updated_at=now()"
                    + " WHERE order_id=?",
                o.get("id"));
        s.exec(
                "UPDATE purchase SET status='CANCELLED',version=version+1,updated_at=now() WHERE"
                        + " id=?",
                o.get("purchase_id"));
        ReplacementLifecycle.close(s, o, false);
        s.audit.record(
                null,
                "REPLACEMENT_RESERVATION_EXPIRED",
                "replacement_request",
                replacement,
                map(
                        "system",
                        true,
                        "stockReleased",
                        true,
                        "customerAmount",
                        "0.00",
                        "noDispensation",
                        true));
        return true;
    }
}
