package ar.emedicapp.payments;

import ar.emedicapp.common.*;
import ar.emedicapp.purchase.*;
import static ar.emedicapp.purchase.PurchaseStore.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ar.emedicapp.fulfillment.FulfillmentService;

@Service
public class PaymentService {
    private final PurchaseStore s;
    private final IdempotencyService idem;
    private final FulfillmentService fulfillment;

    public PaymentService(
            PurchaseStore s, IdempotencyService idem, FulfillmentService fulfillment) {
        this.s = s;
        this.idem = idem;
        this.fulfillment = fulfillment;
    }

    @Transactional
    public Map<String, Object> reportPayment(UUID order, PurchaseDtos.PaymentReport r, String key) {
        s.buyer(s.purchase(id(s.order(order, false).get("purchase_id")), false));
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "PAYMENT_REPORT:" + order,
                key,
                r,
                () -> {
                    var o = s.order(order, true);
                    UUID purchase = id(o.get("purchase_id"));
                    s.buyer(s.purchase(purchase, false));
                    s.version(o, r.expectedVersion());
                    if (!"ACCEPTED".equals(o.get("status"))
                            || !Set.of("ACCEPTED", "PARTIALLY_PAID")
                                    .contains(text(s.purchase(purchase, false).get("status"))))
                        throw AppException.conflict(
                                "PAYMENT_REPORT_STATE_INVALID",
                                "Este pedido no espera un comprobante de pago.");
                    if (amount(o.get("total")).signum() <= 0)
                        throw AppException.conflict(
                                "NO_CUSTOMER_PAYMENT_REQUIRED",
                                "La farmacia debe comprobar la cobertura; no tenés un saldo para pagar.");
                    if (text(r.reference()).isBlank()
                            || text(r.reference()).length() > 160
                            || r.evidenceDocumentId() == null)
                        throw AppException.badRequest(
                                "PAYMENT_REPORT_REQUIRED",
                                "Ingresá la referencia del pago y adjuntá su comprobante.");
                    s.ownedDocument(r.evidenceDocumentId(), "PAYMENT_RECEIPT");
                    if (!s.rows(
                                    "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD' AND expires_at<=now()",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "RESERVATION_EXPIRED",
                                "La reserva venció. Consultá a la farmacia antes de realizar o repetir el pago.");
                    Map<String, Object> snapshot = s.snapshot(o);
                    snapshot.put(
                            "paymentSubmission",
                            map(
                                    "reference",
                                    r.reference().trim(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId(),
                                    "submittedAt",
                                    java.time.Instant.now().toString(),
                                    "status",
                                    "PENDING"));
                    s.exec(
                            "UPDATE child_order SET snapshot=cast(? as jsonb),version=version+1,updated_at=now() WHERE id=?",
                            s.json.encode(snapshot),
                            order);
                    s.exec(
                            "UPDATE purchase SET version=version+1,updated_at=now() WHERE id=?",
                            purchase);
                    s.audit.record(
                            actor,
                            "CUSTOMER_PAYMENT_REPORTED",
                            "child_order",
                            order,
                            map(
                                    "reference",
                                    r.reference().trim(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId(),
                                    "verification",
                                    "PENDING_PHARMACY"));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> payment(UUID order, PurchaseDtos.Payment r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "PAYMENT:" + order,
                key,
                r,
                () -> {
                    var o = s.order(order, true);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    if (!"ACCEPTED".equals(o.get("status"))
                            || !Set.of("ACCEPTED", "PARTIALLY_PAID")
                                    .contains(text(s.purchase(purchase, false).get("status"))))
                        throw AppException.conflict(
                                "PAYMENT_STATE_INVALID",
                                "Este pedido no espera un cobro confirmado.");
                    if (!r.verifiedExternally() || text(r.reference()).isBlank())
                        throw AppException.badRequest(
                                "EXTERNAL_VERIFICATION_REQUIRED",
                                "Comprobá el cobro directo a la farmacia y registrá su referencia externa.");
                    s.paymentEvidence(r.evidenceDocumentId(), branch, order);
                    if (!s.rows(
                                    "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD' AND expires_at<=now()",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "RESERVATION_EXPIRED",
                                "La reserva venció. No se puede avanzar con esta compra; cancelá y conciliá cualquier cobro externo.");
                    BigDecimal total = amount(o.get("total"));
                    if (total.signum() > 0) {
                        if (!s.rows(
                                        "SELECT 1 FROM payment WHERE order_id=? AND status IN ('VERIFIED','PARTIALLY_REFUNDED','REFUNDED')",
                                        order)
                                .isEmpty())
                            throw AppException.conflict(
                                    "PAYMENT_ALREADY_VERIFIED",
                                    "Este pedido ya tiene un cobro confirmado.");
                        UUID payment = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO payment(id,order_id,branch_id,amount,currency,status,evidence_document_id,external_reference,verified_by,verified_at) VALUES(?,?,?,?,?,'VERIFIED',?,?,?,now())",
                                payment,
                                order,
                                branch,
                                total,
                                o.get("currency"),
                                r.evidenceDocumentId(),
                                r.reference().trim(),
                                a.id());
                        s.exec(
                                "INSERT INTO payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason) VALUES(?,?,?,'VERIFIED',?,?,?,?,?)",
                                UUID.randomUUID(),
                                payment,
                                a.id(),
                                total,
                                o.get("currency"),
                                r.reference().trim(),
                                r.evidenceDocumentId(),
                                "Cobro directo de la farmacia comprobado externamente");
                    }
                    s.exec(
                            "UPDATE child_order SET status='PAID',version=version+1,updated_at=now() WHERE id=?",
                            order);
                    submissionStatus(o, "VERIFIED");
                    boolean all =
                            s.rows(
                                            "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN ('PAID','CANCELLED')",
                                            purchase)
                                    .isEmpty();
                    s.exec(
                            "UPDATE purchase SET status=?,version=version+1,updated_at=now() WHERE id=?",
                            all ? "PAID" : "PARTIALLY_PAID",
                            purchase);
                    s.audit.record(
                            a,
                            total.signum() == 0
                                    ? "ZERO_CUSTOMER_BALANCE_VERIFIED"
                                    : "DIRECT_PAYMENT_VERIFIED",
                            "child_order",
                            order,
                            map(
                                    "reference",
                                    r.reference(),
                                    "amount",
                                    total.toPlainString(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId()));
                    return s.view(purchase);
                });
    }

    private void submissionStatus(Map<String, Object> order, String status) {
        var snapshot = s.snapshot(order);
        if (snapshot.get("paymentSubmission") instanceof Map<?, ?> original) {
            var report = new LinkedHashMap<String, Object>((Map<String, Object>) original);
            report.put("status", status);
            report.put("resolvedAt", java.time.Instant.now().toString());
            snapshot.put("paymentSubmission", report);
            s.exec(
                    "UPDATE child_order SET snapshot=cast(? as jsonb) WHERE id=?",
                    s.json.encode(snapshot),
                    order.get("id"));
        }
    }

    @Transactional
    public Map<String, Object> reconcilePayment(
            UUID order, PurchaseDtos.PaymentReconciliation r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "RECONCILE_PAYMENT:" + order,
                key,
                r,
                () -> {
                    var o = s.order(order, true);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    var snapshot = s.snapshot(o);
                    if (!Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status")))
                            || !(snapshot.get("paymentSubmission") instanceof Map<?, ?> report)
                            || !"PENDING".equals(text(report.get("status"))))
                        throw AppException.conflict(
                                "PAYMENT_RECONCILIATION_STATE_INVALID",
                                "Este pedido no tiene un pago informado pendiente de conciliación tras su cancelación.");
                    requireReason(r.reason());
                    if (!Boolean.TRUE.equals(r.verifiedExternally())
                            || !Set.of("RECEIVED", "NOT_RECEIVED").contains(text(r.decision())))
                        throw AppException.badRequest(
                                "RECONCILIATION_VERIFICATION_REQUIRED",
                                "Comprobá externamente si ingresaron fondos y registrá el resultado.");
                    s.evidence(r.evidenceDocumentId(), branch, "PAYMENT_RECEIPT", "OTHER");
                    if (!s.rows(
                                    "SELECT 1 FROM payment WHERE order_id=? AND status<>'REJECTED'",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "PAYMENT_ALREADY_VERIFIED",
                                "El cobro ya fue registrado. Utilizá el circuito de reintegro correspondiente.");
                    if ("RECEIVED".equals(r.decision())) {
                        if (text(r.reference()).isBlank())
                            throw AppException.badRequest(
                                    "PAYMENT_REFERENCE_REQUIRED",
                                    "Registrá la referencia del ingreso comprobado.");
                        var total = amount(o.get("total"));
                        UUID payment = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO payment(id,order_id,branch_id,amount,currency,status,evidence_document_id,external_reference,verified_by,verified_at) VALUES(?,?,?,?,?,'VERIFIED',?,?,?,now())",
                                payment,
                                order,
                                branch,
                                total,
                                o.get("currency"),
                                r.evidenceDocumentId(),
                                r.reference().trim(),
                                actor.id());
                        s.exec(
                                "INSERT INTO payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason) VALUES(?,?,?,'VERIFIED',?,?,?,?,?)",
                                UUID.randomUUID(),
                                payment,
                                actor.id(),
                                total,
                                o.get("currency"),
                                r.reference().trim(),
                                r.evidenceDocumentId(),
                                r.reason());
                        cancelOrderInternal(
                                o,
                                "Cobro conciliado después de la cancelación: " + r.reason(),
                                actor);
                        submissionStatus(o, "REFUND_REQUESTED");
                        finishCancellation(purchase);
                    } else {
                        submissionStatus(o, "NOT_RECEIVED");
                        s.exec(
                                "UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?",
                                order);
                        finishCancellation(purchase);
                    }
                    s.audit.record(
                            actor,
                            "CANCELLED_PAYMENT_RECONCILED",
                            "child_order",
                            order,
                            map(
                                    "decision",
                                    r.decision(),
                                    "reference",
                                    r.reference(),
                                    "reason",
                                    r.reason(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId()));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> cancelPurchase(UUID purchase, PurchaseDtos.Cancel r, String key) {
        s.buyer(s.purchase(purchase, false));
        Actor a = s.access.actor();
        return idem.run(
                a,
                "CANCEL_PURCHASE:" + purchase,
                key,
                r,
                () -> {
                    var p = s.purchase(purchase, true);
                    s.buyer(p);
                    s.version(p, r.expectedVersion());
                    requireReason(r.reason());
                    if (Set.of("CANCELLED", "COMPLETED", "RESOLVED")
                            .contains(text(p.get("status"))))
                        throw AppException.conflict(
                                "CANCELLATION_STATE_INVALID", "Esta compra ya tiene un cierre.");
                    var orders =
                            s.rows(
                                    "SELECT * FROM child_order WHERE purchase_id=? AND status NOT IN ('CANCELLED','RESOLVED') ORDER BY branch_id FOR UPDATE",
                                    purchase);
                    if (orders.stream()
                            .anyMatch(
                                    o ->
                                            Set.of("DISPENSED", "DISPATCHED", "COMPLETED")
                                                    .contains(text(o.get("status"))))) {
                        for (var o : orders) incident(id(o.get("id")), r.reason(), null, a);
                        s.audit.record(
                                a,
                                "POST_DISPENSE_CANCELLATION_INCIDENT",
                                "purchase",
                                purchase,
                                map("reason", r.reason()));
                        return s.view(purchase);
                    }
                    for (var o : orders) cancelOrderInternal(o, r.reason(), a);
                    finishCancellation(purchase);
                    s.audit.record(
                            a,
                            "PURCHASE_CANCELLED",
                            "purchase",
                            purchase,
                            map("reason", r.reason()));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> cancelOrder(UUID order, PurchaseDtos.Cancel r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "CANCEL_ORDER:" + order,
                key,
                r,
                () -> {
                    var o = s.order(order, true);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    requireReason(r.reason());
                    if (Set.of("DISPENSED", "DISPATCHED", "COMPLETED")
                            .contains(text(o.get("status")))) {
                        incident(order, r.reason(), null, a);
                        return s.view(purchase);
                    }
                    if (Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "ORDER_ALREADY_CANCELLED", "Este pedido ya tiene un cierre.");
                    cancelOrderInternal(o, r.reason(), a);
                    // A branch can cancel its child only. Other merchants are frozen for explicit
                    // buyer resolution.
                    s.exec(
                            "UPDATE purchase SET status='COMPENSATING',version=version+1,updated_at=now() WHERE id=?",
                            purchase);
                    s.audit.record(
                            a,
                            "CHILD_CANCELLED_GROUP_BLOCKED",
                            "child_order",
                            order,
                            map("reason", r.reason()));
                    return s.view(purchase);
                });
    }

    private void cancelOrderInternal(Map<String, Object> o, String reason, Actor actor) {
        UUID order = id(o.get("id"));
        s.release(order, actor);
        for (var payment :
                s.rows(
                        "SELECT * FROM payment WHERE order_id=? AND status IN ('VERIFIED','PARTIALLY_REFUNDED') FOR UPDATE",
                        order)) {
            BigDecimal refunded =
                    amount(
                            s.row(
                                            "SELECT coalesce(sum(amount),0) amount FROM refund WHERE payment_id=? AND status IN ('REQUESTED','PENDING','VERIFIED')",
                                            payment.get("id"))
                                    .get("amount"));
            BigDecimal remaining = amount(payment.get("amount")).subtract(refunded);
            if (remaining.signum() > 0) {
                s.exec(
                        "INSERT INTO refund(id,payment_id,requested_by,amount,currency,reason) VALUES(?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        payment.get("id"),
                        actor.id(),
                        remaining,
                        payment.get("currency"),
                        reason);
                s.exec(
                        "INSERT INTO payment_operation(id,payment_id,actor_user_id,kind,amount,currency,reason) VALUES(?,?,?,'REFUND_REQUESTED',?,?,?)",
                        UUID.randomUUID(),
                        payment.get("id"),
                        actor.id(),
                        remaining,
                        payment.get("currency"),
                        reason);
            }
        }
        s.exec(
                "UPDATE child_order SET status='CANCELLED',version=version+1,updated_at=now() WHERE id=?",
                order);
        s.exec(
                "UPDATE fulfillment SET status='CANCELLED',driver_user_id=NULL,proof_code_hash=NULL,version=version+1,updated_at=now() WHERE order_id=?",
                order);
        s.exec(
                "UPDATE routing_attempt SET status='REJECTED',reason=? WHERE purchase_id=? AND branch_id=? AND status IN ('PENDING','ACCEPTED')",
                reason,
                o.get("purchase_id"),
                o.get("branch_id"));
    }

    public void finishCancellation(UUID purchase) {
        boolean outstanding =
                !s.rows(
                                "SELECT 1 FROM refund r JOIN payment p ON p.id=r.payment_id JOIN child_order o ON o.id=p.order_id WHERE o.purchase_id=? AND r.status IN ('REQUESTED','PENDING')",
                                purchase)
                        .isEmpty();
        boolean active =
                !s.rows(
                                "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN ('CANCELLED','COMPLETED','RESOLVED')",
                                purchase)
                        .isEmpty();
        boolean fulfilled =
                !s.rows(
                                "SELECT 1 FROM child_order WHERE purchase_id=? AND status IN ('COMPLETED','RESOLVED')",
                                purchase)
                        .isEmpty();
        s.exec(
                "UPDATE purchase SET status=?,version=version+1,updated_at=now() WHERE id=?",
                outstanding || active ? "COMPENSATING" : fulfilled ? "RESOLVED" : "CANCELLED",
                purchase);
        s.exec(
                "UPDATE quote SET status='REJECTED' WHERE purchase_id=? AND status IN ('DRAFT','ISSUED')",
                purchase);
    }

    @Transactional
    public Map<String, Object> refund(UUID order, PurchaseDtos.Payment r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "REFUND:" + order,
                key,
                r,
                () -> {
                    var o = s.order(order, true);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    if (!"CANCELLED".equals(o.get("status")))
                        throw AppException.conflict(
                                "REFUND_REVIEW_REQUIRED",
                                "Este circuito confirma devoluciones de pedidos cancelados antes de dispensar. Una incidencia posterior requiere resolución farmacéutica.");
                    if (!r.verifiedExternally() || text(r.reference()).isBlank())
                        throw AppException.badRequest(
                                "EXTERNAL_VERIFICATION_REQUIRED",
                                "Comprobá el reintegro externo y registrá su referencia.");
                    s.evidence(r.evidenceDocumentId(), branch, "PAYMENT_RECEIPT", "OTHER");
                    var pending =
                            s.rows(
                                    "SELECT r.*,p.amount payment_amount FROM refund r JOIN payment p ON p.id=r.payment_id WHERE p.order_id=? AND r.status IN ('REQUESTED','PENDING') ORDER BY r.created_at FOR UPDATE OF r,p",
                                    order);
                    if (pending.isEmpty())
                        throw AppException.conflict(
                                "NO_REFUND_PENDING",
                                "No hay un reintegro pendiente para este pedido.");
                    for (var rfd : pending) {
                        BigDecimal verified =
                                amount(
                                        s.row(
                                                        "SELECT coalesce(sum(amount),0) amount FROM refund WHERE payment_id=? AND status='VERIFIED'",
                                                        rfd.get("payment_id"))
                                                .get("amount"));
                        if (verified.add(amount(rfd.get("amount")))
                                        .compareTo(amount(rfd.get("payment_amount")))
                                > 0)
                            throw AppException.conflict(
                                    "REFUND_EXCEEDS_PAYMENT",
                                    "La devolución acumulada no puede superar el cobro original.");
                        s.exec(
                                "UPDATE refund SET status='VERIFIED',external_reference=?,evidence_document_id=?,verified_by=?,verified_at=now() WHERE id=?",
                                r.reference(),
                                r.evidenceDocumentId(),
                                a.id(),
                                rfd.get("id"));
                        s.exec(
                                "UPDATE payment SET status=? WHERE id=?",
                                verified.add(amount(rfd.get("amount")))
                                                        .compareTo(
                                                                amount(rfd.get("payment_amount")))
                                                == 0
                                        ? "REFUNDED"
                                        : "PARTIALLY_REFUNDED",
                                rfd.get("payment_id"));
                        s.exec(
                                "INSERT INTO payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason) VALUES(?,?,?,'REFUND_VERIFIED',?,?,?,?,?)",
                                UUID.randomUUID(),
                                rfd.get("payment_id"),
                                a.id(),
                                rfd.get("amount"),
                                rfd.get("currency"),
                                r.reference(),
                                r.evidenceDocumentId(),
                                "Reintegro externo confirmado, sin tope arbitrario");
                    }
                    s.exec(
                            "UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?",
                            order);
                    finishCancellation(purchase);
                    s.audit.record(
                            a,
                            "DIRECT_REFUND_VERIFIED",
                            "child_order",
                            order,
                            map(
                                    "reference",
                                    r.reference(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId()));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> invoice(UUID order, Invoice r) {
        var o = s.order(order, true);
        UUID branch = id(o.get("branch_id"));
        s.pharmacy(branch, false);
        s.version(o, r.expectedVersion());
        if (!Set.of("PAID", "PREPARING", "DISPENSED").contains(text(o.get("status"))))
            throw AppException.conflict(
                    "INVOICE_STATE_INVALID",
                    "Registrá la factura sobre un pedido pagado y antes del cumplimiento.");
        requireReason(r.reference());
        s.evidence(r.evidenceDocumentId(), branch, "INVOICE");
        s.exec(
                "UPDATE child_order SET invoice_document_id=?,version=version+1,updated_at=now(),snapshot=jsonb_set(snapshot,'{invoiceReference}',to_jsonb(cast(? as text))) WHERE id=?",
                r.evidenceDocumentId(),
                r.reference(),
                order);
        s.audit.record(
                s.access.actor(),
                "PHARMACY_INVOICE_ATTACHED",
                "child_order",
                order,
                map("reference", r.reference(), "documentId", r.evidenceDocumentId()));
        return s.view(id(o.get("purchase_id")));
    }

    public record Invoice(long expectedVersion, UUID evidenceDocumentId, String reference) {}

    @Transactional
    public Map<String, Object> reportIncident(UUID order, PurchaseDtos.Incident r) {
        var o = s.order(order, true);
        var p = s.purchase(id(o.get("purchase_id")), false);
        Actor a = s.access.actor();
        UUID branch = id(o.get("branch_id"));
        boolean owner = a.hasRole("BUYER") && Objects.equals(p.get("buyer_user_id"), a.id());
        boolean driver =
                !s.rows(
                                "SELECT 1 FROM fulfillment WHERE order_id=? AND driver_user_id=? AND status NOT IN ('DELIVERED','COLLECTED','CANCELLED','RESOLVED')",
                                order,
                                a.id())
                        .isEmpty();
        if (driver && !owner) s.access.requireBranchRole(branch, "DRIVER");
        if (!owner && !driver) s.pharmacy(branch, false);
        s.version(o, r.expectedVersion());
        requireReason(r.reason());
        if (r.evidenceDocumentId() != null) {
            if (owner) s.ownedDocument(r.evidenceDocumentId(), "OTHER");
            else s.evidence(r.evidenceDocumentId(), branch, "OTHER", "DELIVERY_PROOF");
        }
        incident(order, r.reason(), r.evidenceDocumentId(), a);
        return s.view(id(p.get("id")));
    }

    private void incident(UUID order, String reason, UUID evidence, Actor a) {
        var o = s.order(order, false);
        var fs = s.rows("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!fs.isEmpty()) {
            var f = fs.getFirst();
            var snapshot = s.snapshot(f);
            if (!"INCIDENT".equals(f.get("status")))
                snapshot.put("incidentPreviousStatus", f.get("status"));
            snapshot.put("incidentReason", reason);
            s.exec(
                    "UPDATE fulfillment SET status='INCIDENT',proof_code_hash=NULL,snapshot=cast(? as jsonb),version=version+1,updated_at=now() WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec(
                    "INSERT INTO custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,reason) VALUES(?,?,?,?,'INCIDENT',?,?)",
                    UUID.randomUUID(),
                    f.get("id"),
                    o.get("branch_id"),
                    a.id(),
                    evidence,
                    reason);
        }
        s.exec(
                "UPDATE purchase SET status='COMPENSATING',version=version+1,updated_at=now() WHERE id=?",
                o.get("purchase_id"));
        s.exec("UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?", order);
        s.audit.record(
                a,
                "ORDER_INCIDENT_REPORTED",
                "child_order",
                order,
                map("reason", reason, "evidenceDocumentId", evidence));
    }

    @Transactional
    public Map<String, Object> resolveIncident(UUID order, PurchaseDtos.Resolve r) {
        var o = s.order(order, true);
        UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
        s.pharmacy(branch, true);
        s.version(o, r.expectedVersion());
        requireReason(r.reason());
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!"INCIDENT".equals(f.get("status")))
            throw AppException.conflict(
                    "INCIDENT_REQUIRED", "Este pedido no tiene una incidencia abierta.");
        if (!Set.of("REFUND", "RETRY", "CLOSE_WITHOUT_REFUND").contains(text(r.decision())))
            throw AppException.badRequest(
                    "RESOLUTION_INVALID",
                    "Elegí reintegro, reintento o cierre farmacéutico documentado.");
        s.evidence(r.evidenceDocumentId(), branch, "OTHER", "DELIVERY_PROOF", "PAYMENT_RECEIPT");
        Actor actor = s.access.actor();
        var snapshot = s.snapshot(f);
        snapshot.put(
                "resolution",
                map(
                        "decision",
                        r.decision(),
                        "reason",
                        r.reason(),
                        "evidenceDocumentId",
                        r.evidenceDocumentId(),
                        "reviewedBy",
                        actor.id(),
                        "reviewedAt",
                        java.time.Instant.now().toString()));
        if ("REFUND".equals(r.decision())) {
            cancelOrderInternal(o, r.reason(), actor);
            s.exec(
                    "UPDATE fulfillment SET status='RESOLVED',snapshot=cast(? as jsonb),version=version+1 WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
            finishCancellation(purchase);
        } else if ("CLOSE_WITHOUT_REFUND".equals(r.decision())) {
            // Professional incident disposition, never a fabricated delivery or an automatic
            // inventory return.
            s.release(order, actor);
            s.exec(
                    "UPDATE fulfillment SET status='RESOLVED',driver_user_id=NULL,snapshot=cast(? as jsonb),version=version+1 WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec(
                    "UPDATE child_order SET status='RESOLVED',version=version+1,updated_at=now() WHERE id=?",
                    order);
            finishCancellation(purchase);
        } else {
            fulfillment.checkConditions(
                    order, r.evidenceDocumentId(), r.temperatureC(), r.reason(), false);
            if (!s.rows(
                            "SELECT 1 FROM child_order WHERE purchase_id=? AND accepted_quote_id IS NOT NULL AND status IN ('CANCELLED','RESOLVED')",
                            purchase)
                    .isEmpty())
                throw AppException.conflict(
                        "GROUP_RECONSENT_REQUIRED",
                        "La composición aceptada cambió; resolvé la compra completa, sin entregar un resto parcial automáticamente.");
            String previous = text(snapshot.get("incidentPreviousStatus"));
            if (!Set.of("PREPARING", "READY", "ASSIGNED", "ACCEPTED", "PICKED_UP")
                    .contains(previous))
                throw AppException.conflict(
                        "RETRY_NOT_POSSIBLE",
                        "Esta incidencia no conserva un estado seguro para reintentar el cumplimiento.");
            if (Set.of("ASSIGNED", "ACCEPTED", "PICKED_UP").contains(previous)) {
                if (s.rows(
                                "SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=? AND m.branch_id=? AND m.role='DRIVER' AND m.active AND u.active",
                                f.get("driver_user_id"),
                                branch)
                        .isEmpty())
                    throw AppException.conflict(
                            "OWN_DRIVER_REQUIRED",
                            "El reintento necesita conservar un cadete propio vigente o una reasignación segura.");
                fulfillment.checkDriverCapabilities(order, id(f.get("driver_user_id")));
            }
            s.exec(
                    "UPDATE fulfillment SET status=?,snapshot=cast(? as jsonb),proof_code_hash=NULL,version=version+1 WHERE id=?",
                    previous,
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec("UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?", order);
            if (s.rows(
                            "SELECT 1 FROM fulfillment f JOIN child_order o ON o.id=f.order_id WHERE o.purchase_id=? AND f.status='INCIDENT'",
                            purchase)
                    .isEmpty()) {
                s.allPaid(purchase);
                s.exec(
                        "UPDATE purchase SET status='FULFILLING',version=version+1,updated_at=now() WHERE id=?",
                        purchase);
            }
        }
        s.exec(
                "INSERT INTO custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,temperature_c,reason,snapshot) VALUES(?,?,?,?,'INCIDENT',?,?,?,cast(? as jsonb))",
                UUID.randomUUID(),
                f.get("id"),
                branch,
                actor.id(),
                r.evidenceDocumentId(),
                r.temperatureC(),
                r.reason(),
                s.json.encode(map("resolution", r.decision(), "automaticRestock", false)));
        s.audit.record(
                actor,
                "INCIDENT_RESOLVED_BY_PHARMACIST",
                "child_order",
                order,
                map("decision", r.decision(), "evidenceDocumentId", r.evidenceDocumentId()));
        return s.view(purchase);
    }

    private void requireReason(String reason) {
        if (text(reason).isBlank())
            throw AppException.badRequest(
                    "REFERENCE_REQUIRED", "Registrá una referencia o motivo para esta operación.");
    }

    @Transactional
    public boolean expirePurchase(UUID purchase) {
        var p = s.purchase(purchase, true);
        if (!Set.of("ACCEPTED", "PARTIALLY_PAID").contains(text(p.get("status")))) return false;
        if (s.rows(
                        "SELECT 1 FROM reservation r JOIN child_order o ON o.id=r.order_id WHERE o.purchase_id=? AND r.status='HELD' AND r.expires_at<=now()",
                        purchase)
                .isEmpty()) return false;
        var orders =
                s.rows(
                        "SELECT * FROM child_order WHERE purchase_id=? AND status<>'CANCELLED' ORDER BY branch_id FOR UPDATE",
                        purchase);
        if (orders.stream()
                .anyMatch(
                        o ->
                                Set.of("DISPENSED", "DISPATCHED", "COMPLETED", "RESOLVED")
                                        .contains(text(o.get("status"))))) return false;
        Actor system = new Actor(null, "", "SYSTEM", Set.of(), Set.of());
        for (var order : orders) cancelOrderInternal(order, "UNPAID_RESERVATION_EXPIRED", system);
        finishCancellation(purchase);
        s.audit.record(
                null,
                "SYSTEM_RESERVATION_EXPIRY",
                "purchase",
                purchase,
                map("orderCount", orders.size(), "reason", "UNPAID_RESERVATION_EXPIRED"));
        return true;
    }
}
