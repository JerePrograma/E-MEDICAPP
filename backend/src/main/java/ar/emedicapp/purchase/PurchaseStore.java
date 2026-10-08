package ar.emedicapp.purchase;

import ar.emedicapp.common.*;
import ar.emedicapp.config.OperationalConfigService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PurchaseStore {
    public final JdbcClient db;
    public final Json json;
    public final Access access;
    public final AuditLog audit;
    public final OperationalConfigService config;

    public PurchaseStore(
            JdbcClient db,
            Json json,
            Access access,
            AuditLog audit,
            OperationalConfigService config) {
        this.db = db;
        this.json = json;
        this.access = access;
        this.audit = audit;
        this.config = config;
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return db.sql(sql).params(args).query().listOfRows();
    }

    public Map<String, Object> row(String sql, Object... args) {
        return rows(sql, args).stream()
                .findFirst()
                .orElseThrow(
                        () -> AppException.notFound("NOT_FOUND", "No se encontró la operación."));
    }

    public int exec(String sql, Object... args) {
        return db.sql(sql).params(args).update();
    }

    public static UUID id(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
    }

    public static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static BigDecimal amount(Object o) {
        return o == null
                ? BigDecimal.ZERO
                : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    public static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(o.toString());
    }

    public static boolean flag(Object o) {
        return Boolean.TRUE.equals(o);
    }

    public static Instant instant(Object o) {
        if (o instanceof OffsetDateTime d) return d.toInstant();
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof Instant i) return i;
        return OffsetDateTime.parse(String.valueOf(o)).toInstant();
    }

    public static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]);
        return result;
    }

    public Map<String, Object> purchase(UUID id, boolean lock) {
        return row("SELECT * FROM purchase WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    public Map<String, Object> order(UUID id, boolean lock) {
        Map<String, Object> existing = row("SELECT * FROM child_order WHERE id=?", id);
        if (lock) purchase(id(existing.get("purchase_id")), true);
        return lock ? row("SELECT * FROM child_order WHERE id=? FOR UPDATE", id) : existing;
    }

    public void version(Map<String, Object> row, long expected) {
        if (expected < 1 || num(row.get("version")) != expected)
            throw AppException.conflict(
                    "VERSION_MISMATCH",
                    "La operación cambió. Actualizá la pantalla antes de continuar.");
    }

    public boolean branchRole(UUID branch, String... roles) {
        Actor a = access.actor();
        if (!a.hasRole(roles)) return false;
        String marks = String.join(",", Collections.nCopies(roles.length, "?"));
        List<Object> args = new ArrayList<>();
        args.add(a.id());
        args.add(branch);
        args.addAll(Arrays.asList(roles));
        return !rows(
                        "SELECT 1 FROM membership m JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=? AND m.branch_id=? AND m.active AND m.role IN ("
                                + marks
                                + ")",
                        args.toArray())
                .isEmpty();
    }

    public void pharmacy(UUID branch, boolean clinical) {
        access.requireBranchRole(
                branch,
                clinical ? new String[] {"PHARMACIST"} : new String[] {"PHARMACY", "PHARMACIST"});
    }

    public void buyer(Map<String, Object> purchase) {
        Actor a = access.actor();
        a.requireRole("BUYER");
        if (!a.id().equals(id(purchase.get("buyer_user_id"))))
            throw AppException.forbidden(
                    "PURCHASE_ACCESS_DENIED", "No tenés acceso a esta compra.");
    }

    public void evidence(UUID document, UUID branch, String... kinds) {
        if (document == null)
            throw AppException.badRequest(
                    "EVIDENCE_REQUIRED",
                    "Adjuntá la evidencia que respalda esta confirmación manual.");
        Map<String, Object> d = row("SELECT * FROM document WHERE id=?", document);
        if (!Objects.equals(d.get("branch_id"), branch)
                || !Arrays.asList(kinds).contains(text(d.get("kind"))))
            throw AppException.forbidden(
                    "DOCUMENT_SCOPE_INVALID",
                    "La evidencia debe corresponder a esta sucursal y operación.");
    }

    public void coverageEvidence(UUID document, UUID branch, UUID order) {
        if (document == null)
            throw AppException.badRequest(
                    "EVIDENCE_REQUIRED",
                    "Adjuntá la autorización que respalda la cobertura comprobada.");
        var evidence = row("SELECT * FROM document WHERE id=?", document);
        boolean existingLegacy =
                "OTHER".equals(evidence.get("kind"))
                        && !rows(
                                        "SELECT 1 FROM review WHERE order_id=? AND kind='COVERAGE' AND evidence_document_id=?",
                                        order,
                                        document)
                                .isEmpty();
        if (!Objects.equals(evidence.get("branch_id"), branch)
                || !("HEALTH_COVERAGE".equals(evidence.get("kind")) || existingLegacy))
            throw AppException.forbidden(
                    "DOCUMENT_SCOPE_INVALID",
                    "La autorización debe ser un documento clínico de cobertura de esta sucursal y pedido.");
    }

    public void ownedDocument(UUID document, String kind) {
        Map<String, Object> d = row("SELECT * FROM document WHERE id=?", document);
        if (!access.actor().id().equals(id(d.get("owner_user_id"))) || !kind.equals(d.get("kind")))
            throw AppException.forbidden(
                    "DOCUMENT_SCOPE_INVALID",
                    "El documento debe pertenecer al comprador y tener el tipo correspondiente.");
    }

    public void paymentEvidence(UUID document, UUID branch, UUID order) {
        if (document == null)
            throw AppException.badRequest("EVIDENCE_REQUIRED", "Adjuntá el comprobante del cobro.");
        Map<String, Object> d = row("SELECT * FROM document WHERE id=?", document);
        if (Objects.equals(d.get("branch_id"), branch)
                && Set.of("PAYMENT_RECEIPT", "OTHER").contains(text(d.get("kind")))) return;
        Map<String, Object> o = order(order, false), p = purchase(id(o.get("purchase_id")), false);
        Object submission = snapshot(o).get("paymentSubmission");
        if ("PAYMENT_RECEIPT".equals(d.get("kind"))
                && Objects.equals(d.get("owner_user_id"), p.get("buyer_user_id"))
                && submission instanceof Map<?, ?> report
                && document.toString().equals(text(report.get("evidenceDocumentId")))) return;
        throw AppException.forbidden(
                "DOCUMENT_SCOPE_INVALID",
                "El comprobante debe ser de esta farmacia o el presentado por el comprador para este pedido.");
    }

    public List<Map<String, Object>> items(UUID order) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row :
                rows(
                        "SELECT oi.*,p.name,p.presentation,p.requires_prescription,p.cold_chain,p.restricted,p.metadata FROM order_item oi JOIN product p ON p.id=oi.product_id WHERE oi.order_id=? ORDER BY oi.product_id",
                        order)) {
            Map<String, Object> i = new LinkedHashMap<>(row), snap = snapshot(row);
            i.put("name", snap.getOrDefault("name", row.get("name")));
            i.put("presentation", snap.getOrDefault("presentation", row.get("presentation")));
            i.put(
                    "requires_prescription",
                    snap.getOrDefault("requiresPrescription", row.get("requires_prescription")));
            i.put("cold_chain", snap.getOrDefault("coldChain", row.get("cold_chain")));
            i.put("restricted", snap.getOrDefault("restricted", row.get("restricted")));
            i.put(
                    "metadata",
                    snap.containsKey("productMetadata")
                            ? json.encode(snap.get("productMetadata"))
                            : row.get("metadata"));
            result.add(i);
        }
        return result;
    }

    public Map<String, Object> snapshot(Map<String, Object> row) {
        return json.readMap(text(row.get("snapshot")));
    }

    public Map<String, Object> view(UUID purchaseId) {
        Map<String, Object> p = purchase(purchaseId, false);
        Actor a = access.actor();
        boolean owner = a.hasRole("BUYER") && a.id().equals(p.get("buyer_user_id"));
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> o :
                rows(
                        "SELECT o.*,b.name branch_name,b.address branch_address,b.payment_instructions,b.merchant_name FROM child_order o JOIN branch b ON b.id=o.branch_id WHERE o.purchase_id=? ORDER BY o.created_at,o.id",
                        purchaseId)) {
            UUID orderId = id(o.get("id")), branch = id(o.get("branch_id"));
            boolean pharmacy = branchRole(branch, "PHARMACY", "PHARMACIST");
            boolean driver =
                    branchRole(branch, "DRIVER")
                            && !rows(
                                            "SELECT 1 FROM fulfillment WHERE order_id=? AND driver_user_id=?",
                                            orderId,
                                            a.id())
                                    .isEmpty();
            if (!owner && !pharmacy && !driver) continue;
            if (driver && !owner && !pharmacy) {
                visible.add(
                        map(
                                "id",
                                orderId,
                                "branchId",
                                branch,
                                "branchName",
                                o.get("branch_name"),
                                "version",
                                o.get("version"),
                                "status",
                                o.get("status")));
                continue;
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            var orderItems = items(orderId);
            for (Map<String, Object> item : orderItems) {
                Map<String, Object> s = snapshot(item);
                BigDecimal price = amount(s.get("unitPrice")),
                        coverage = amount(s.get("coverageAmount"));
                lines.add(
                        map(
                                "productId",
                                item.get("product_id"),
                                "name",
                                item.get("name"),
                                "presentation",
                                item.get("presentation"),
                                "quantity",
                                item.get("quantity"),
                                "unitPrice",
                                price.toPlainString(),
                                "coverageAmount",
                                coverage.toPlainString(),
                                "total",
                                price.multiply(amount(item.get("quantity")))
                                        .subtract(coverage)
                                        .toPlainString(),
                                "requiresPrescription",
                                item.get("requires_prescription"),
                                "coldChain",
                                item.get("cold_chain"),
                                "minTemperatureC",
                                json.readMap(text(item.get("metadata"))).get("minTemperatureC"),
                                "maxTemperatureC",
                                json.readMap(text(item.get("metadata"))).get("maxTemperatureC"),
                                "restricted",
                                item.get("restricted")));
            }
            if (pharmacy
                    && branchRole(branch, "PHARMACIST")
                    && !"CANCELLED".equals(o.get("status"))) {
                for (int i = 0; i < lines.size(); i++) {
                    var lineSnapshot = snapshot(orderItems.get(i));
                    lines.get(i).put("coverageReference", lineSnapshot.get("coverageReference"));
                    lines.get(i)
                            .put(
                                    "coverageEvidenceDocumentId",
                                    lineSnapshot.get("coverageEvidenceDocumentId"));
                    var prescriptionItem =
                            rows(
                                    "SELECT r.authorized_quantity FROM purchase_item pi JOIN prescription_item r ON r.id=pi.prescription_item_id WHERE pi.id=?",
                                    orderItems.get(i).get("purchase_item_id"));
                    if (!prescriptionItem.isEmpty())
                        lines.get(i)
                                .put(
                                        "authorizedQuantity",
                                        prescriptionItem.getFirst().get("authorized_quantity"));
                }
            }
            Map<String, Object> f =
                    rows("SELECT * FROM fulfillment WHERE order_id=?", orderId).stream()
                            .findFirst()
                            .orElse(Map.of());
            Map<String, Object> os = snapshot(o),
                    branchSnapshot =
                            os.get("branchConditions") instanceof Map<?, ?> saved
                                    ? new LinkedHashMap<>((Map<String, Object>) saved)
                                    : Map.of();
            Map<String, Object> v =
                    map(
                            "id",
                            orderId,
                            "purchaseId",
                            purchaseId,
                            "branchId",
                            branch,
                            "branchName",
                            branchSnapshot.getOrDefault("name", o.get("branch_name")),
                            "branchAddress",
                            branchSnapshot.getOrDefault("address", o.get("branch_address")),
                            "version",
                            o.get("version"),
                            "status",
                            o.get("status"),
                            "accepted",
                            o.get("accepted_quote_id") != null,
                            "total",
                            amount(o.get("total")).toPlainString(),
                            "currency",
                            o.get("currency"),
                            "deliveryFee",
                            "0.00",
                            "lines",
                            lines,
                            "reviewApproved",
                            flag(os.get("reviewApproved")),
                            "reviewCount",
                            os.getOrDefault("reviewCount", 0),
                            "highValueRequired",
                            flag(os.get("highValueRequired")),
                            "paymentInstructions",
                            branchSnapshot.getOrDefault(
                                    "paymentInstructions", o.get("payment_instructions")),
                            "merchantName",
                            branchSnapshot.getOrDefault("merchantName", o.get("merchant_name")),
                            "invoiceDocumentId",
                            o.get("invoice_document_id"),
                            "paymentSubmission",
                            os.get("paymentSubmission"),
                            "fulfillment",
                            f.isEmpty()
                                    ? null
                                    : map(
                                            "id",
                                            f.get("id"),
                                            "version",
                                            f.get("version"),
                                            "status",
                                            f.get("status"),
                                            "driverId",
                                            f.get("driver_user_id"),
                                            "location",
                                            snapshot(f).get("location")),
                            "payments",
                            rows(
                                    "SELECT id,status,amount::text AS amount,currency,external_reference FROM payment WHERE order_id=?",
                                    orderId),
                            "refunds",
                            rows(
                                    "SELECT r.id,r.status,r.amount::text AS amount,r.currency FROM refund r JOIN payment pay ON pay.id=r.payment_id WHERE pay.order_id=?",
                                    orderId));
            if (pharmacy
                    && branchRole(branch, "PHARMACIST")
                    && !"CANCELLED".equals(o.get("status"))) {
                Map<String, Object> b =
                        row("SELECT * FROM beneficiary WHERE id=?", p.get("beneficiary_id"));
                v.put("beneficiary", map("name", b.get("name"), "birthDate", b.get("birth_date")));
                v.put("prescriptionDocumentId", snapshot(p).get("prescriptionDocumentId"));
                v.put("authorizationDocumentId", snapshot(p).get("authorizationDocumentId"));
                v.put("thirdParty", snapshot(p).get("thirdParty"));
                v.put("coverageRequest", snapshot(p).get("coverageRequest"));
                var prescription =
                        rows(
                                """
                    SELECT r.external_reference,r.issuer,r.expires_at FROM order_item oi
                    JOIN purchase_item pi ON pi.id=oi.purchase_item_id
                    JOIN prescription_item ri ON ri.id=pi.prescription_item_id
                    JOIN prescription r ON r.id=ri.prescription_id WHERE oi.order_id=? LIMIT 1
                    """,
                                orderId);
                if (!prescription.isEmpty()) {
                    var r = prescription.getFirst();
                    v.put(
                            "prescriptionReview",
                            map(
                                    "reference",
                                    r.get("external_reference"),
                                    "issuer",
                                    r.get("issuer"),
                                    "expiresAt",
                                    r.get("expires_at")));
                }
            }
            visible.add(v);
        }
        if (!owner && visible.isEmpty())
            throw AppException.forbidden(
                    "PURCHASE_ACCESS_DENIED", "No tenés acceso a esta compra.");
        List<Map<String, Object>> quotes =
                rows(
                        "SELECT id,version,status,expires_at FROM quote WHERE purchase_id=? ORDER BY version DESC LIMIT 1",
                        purchaseId);
        Object expiration = quotes.isEmpty() ? null : quotes.getFirst().get("expires_at");
        Object messages =
                snapshot(p).get("config") instanceof Map<?, ?> cfg ? cfg.get("messages") : Map.of();
        Map<String, Object> result =
                map(
                        "id",
                        purchaseId,
                        "buyerUserId",
                        p.get("buyer_user_id"),
                        "createdAt",
                        p.get("created_at"),
                        "version",
                        p.get("version"),
                        "status",
                        p.get("status"),
                        "mode",
                        p.get("mode"),
                        "destinationAddress",
                        p.get("delivery_address"),
                        "total",
                        owner
                                ? amount(p.get("total")).toPlainString()
                                : visible.stream()
                                        .filter(
                                                o ->
                                                        !"CANCELLED".equals(o.get("status"))
                                                                || Boolean.TRUE.equals(
                                                                        o.get("accepted")))
                                        .map(o -> amount(o.get("total")))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                        .toPlainString(),
                        "currency",
                        p.get("currency"),
                        "quoteExpiresAt",
                        expiration,
                        "reserveExpiresAt",
                        row(
                                        "SELECT min(r.expires_at) expires_at FROM reservation r JOIN child_order o ON o.id=r.order_id WHERE o.purchase_id=? AND r.status='HELD'",
                                        purchaseId)
                                .get("expires_at"),
                        "allOrdersPaid",
                        rows(
                                        "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN ('PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED')",
                                        purchaseId)
                                .isEmpty(),
                        "message",
                        snapshot(p).getOrDefault("message", ""),
                        "messages",
                        messages,
                        "orders",
                        visible);
        if (owner) {
            var b =
                    row(
                            "SELECT name,birth_date FROM beneficiary WHERE id=?",
                            p.get("beneficiary_id"));
            result.put("beneficiary", map("name", b.get("name"), "birthDate", b.get("birth_date")));
            result.put("thirdParty", snapshot(p).get("thirdParty"));
            result.put("coverageRequest", snapshot(p).get("coverageRequest"));
            result.put("prescriptionDocumentId", snapshot(p).get("prescriptionDocumentId"));
            result.put("authorizationDocumentId", snapshot(p).get("authorizationDocumentId"));
        }
        return result;
    }

    public void release(UUID order, Actor actor) {
        for (Map<String, Object> r :
                rows(
                        "SELECT * FROM reservation WHERE order_id=? AND status='HELD' ORDER BY branch_id,product_id FOR UPDATE",
                        order)) {
            exec(
                    "UPDATE branch_stock SET reserved_quantity=reserved_quantity-?,version=version+1,updated_at=now() WHERE branch_id=? AND product_id=?",
                    r.get("quantity"),
                    r.get("branch_id"),
                    r.get("product_id"));
            if (r.get("prescription_item_id") != null)
                exec(
                        "UPDATE prescription_item SET reserved_quantity=reserved_quantity-? WHERE id=?",
                        r.get("quantity"),
                        r.get("prescription_item_id"));
            if (r.get("lot_id") != null)
                exec(
                        "UPDATE stock_lot SET reserved_quantity=reserved_quantity-?,version=version+1 WHERE id=?",
                        r.get("quantity"),
                        r.get("lot_id"));
            exec("UPDATE reservation SET status='RELEASED' WHERE id=?", r.get("id"));
            exec(
                    "INSERT INTO stock_movement(id,branch_id,product_id,reservation_id,actor_user_id,kind,quantity_delta,reserved_delta,reason) VALUES(?,?,?,?,?,'RELEASE',0,?,'Cancelación antes de dispensación')",
                    UUID.randomUUID(),
                    r.get("branch_id"),
                    r.get("product_id"),
                    r.get("id"),
                    actor.id(),
                    -((Number) r.get("quantity")).intValue());
        }
    }

    public void allPaid(UUID purchase) {
        if (!rows(
                        "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN ('PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED')",
                        purchase)
                .isEmpty())
            throw AppException.conflict(
                    "COMPLETE_PAYMENT_REQUIRED",
                    "Todos los pedidos de esta compra deben tener cobro confirmado antes de prepararlos o dispensarlos.");
    }

    public void finishIfComplete(UUID purchase) {
        if (rows(
                                "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN ('COMPLETED','CANCELLED','RESOLVED')",
                                purchase)
                        .isEmpty()
                && rows(
                                "SELECT 1 FROM refund r JOIN payment p ON p.id=r.payment_id JOIN child_order o ON o.id=p.order_id WHERE o.purchase_id=? AND r.status IN ('REQUESTED','PENDING')",
                                purchase)
                        .isEmpty())
            exec(
                    "UPDATE purchase SET status=?,version=version+1,updated_at=now() WHERE id=?",
                    rows(
                                            "SELECT 1 FROM child_order WHERE purchase_id=? AND (status='RESOLVED' OR (status='CANCELLED' AND accepted_quote_id IS NOT NULL))",
                                            purchase)
                                    .isEmpty()
                            ? "COMPLETED"
                            : "RESOLVED",
                    purchase);
    }
}
