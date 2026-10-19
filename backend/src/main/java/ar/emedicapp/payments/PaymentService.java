package ar.emedicapp.payments;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.*;
import ar.emedicapp.fulfillment.FulfillmentService;
import ar.emedicapp.purchase.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

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

    private String paymentMethod(String value) {
        String method = value == null ? "EXTERNAL_MANUAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("TRANSFER", "CASH", "CARD", "OTHER", "EXTERNAL_MANUAL").contains(method))
            throw AppException.badRequest(
                    "PAYMENT_METHOD_INVALID",
                    "Indicá transferencia, efectivo, tarjeta u otro medio efectivamente"
                        + " comprobado.");
        return method;
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
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID purchase = id(o.get("purchase_id"));
                    s.buyer(s.purchase(purchase, false));
                    s.version(o, r.expectedVersion());
                    String originalStatus = text(o.get("status")),
                            parentStatus = text(s.purchase(purchase, false).get("status"));
                    FinancialRules.requireNotCancelling(s, o);
                    if (!"ACCEPTED".equals(originalStatus)
                            || !Set.of("ACCEPTED", "PARTIALLY_PAID").contains(parentStatus))
                        throw AppException.conflict(
                                "PAYMENT_REPORT_STATE_INVALID",
                                "Este pedido no espera un comprobante de pago.");
                    if (FinancialRules.due(s, o)
                                    .subtract(FinancialRules.grossPaid(s, order))
                                    .signum()
                            <= 0)
                        throw AppException.conflict(
                                "NO_CUSTOMER_PAYMENT_REQUIRED",
                                "La farmacia debe comprobar la cobertura; no tenés un saldo para"
                                        + " pagar.");
                    if (text(r.reference()).isBlank()
                            || text(r.reference()).length() > 160
                            || r.evidenceDocumentId() == null)
                        throw AppException.badRequest(
                                "PAYMENT_REPORT_REQUIRED",
                                "Ingresá la referencia del pago y adjuntá su comprobante.");
                    s.ownedDocument(r.evidenceDocumentId(), "PAYMENT_RECEIPT");
                    if (!s.rows(
                                    "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD'"
                                            + " AND expires_at<=now()",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "RESERVATION_EXPIRED",
                                "La reserva venció. Consultá a la farmacia antes de realizar o"
                                        + " repetir el pago.");
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
                            "UPDATE child_order SET snapshot=cast(? as"
                                    + " jsonb),version=version+1,updated_at=now() WHERE id=?",
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
        var o = s.order(order, false);
        return payment(
                order,
                new PaymentDtos.Confirm(
                        r.expectedVersion(),
                        r.reference(),
                        r.evidenceDocumentId(),
                        r.verifiedExternally(),
                        amount(o.get("total")),
                        text(o.get("currency"))),
                key);
    }

    @Transactional
    public Map<String, Object> payment(UUID order, PaymentDtos.Confirm r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "PAYMENT:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    String originalStatus = text(o.get("status")),
                            parentStatus = text(s.purchase(purchase, false).get("status"));
                    FinancialRules.requireNotCancelling(s, o);
                    if (!"ACCEPTED".equals(originalStatus)
                            || !Set.of("ACCEPTED", "PARTIALLY_PAID").contains(parentStatus))
                        throw AppException.conflict(
                                "PAYMENT_STATE_INVALID",
                                "Este pedido no espera un cobro confirmado.");
                    if (!Boolean.TRUE.equals(r.verifiedExternally())
                            || text(r.reference()).isBlank())
                        throw AppException.badRequest(
                                "EXTERNAL_VERIFICATION_REQUIRED",
                                "Comprobá el cobro directo a la farmacia y registrá su referencia"
                                        + " externa.");
                    s.paymentEvidence(r.evidenceDocumentId(), branch, order);
                    if (!s.rows(
                                    "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD'"
                                            + " AND expires_at<=now()",
                                    order)
                            .isEmpty())
                        throw AppException.conflict(
                                "RESERVATION_EXPIRED",
                                "La reserva venció. No se puede avanzar con esta compra; cancelá y"
                                        + " conciliá cualquier cobro externo.");
                    BigDecimal total = FinancialRules.due(s, o);
                    BigDecimal received =
                            FinancialRules.checkedAmount(
                                    r.amount(),
                                    r.currency(),
                                    o.get("currency"),
                                    total.signum() == 0);
                    String method = paymentMethod(r.method());
                    BigDecimal previouslyReceived = FinancialRules.grossPaid(s, order);
                    if (!"ACCEPTED".equals(originalStatus)
                            && previouslyReceived.compareTo(total) >= 0)
                        throw AppException.conflict(
                                "PAYMENT_ALREADY_VERIFIED",
                                "Este pedido ya tiene cubierto su saldo; no registres dos veces el"
                                        + " mismo ingreso.");
                    if (received.compareTo(total.subtract(previouslyReceived)) > 0)
                        throw AppException.conflict(
                                "PAYMENT_EXCEEDS_BALANCE",
                                "El importe recibido supera el saldo del pedido; conciliá el"
                                        + " excedente sin inventar un cobro de la compra.");
                    if (received.signum() > 0) {
                        UUID payment = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO"
                                    + " payment(id,order_id,branch_id,amount,currency,method,status,evidence_document_id,external_reference,verified_by,verified_at)"
                                    + " VALUES(?,?,?,?,?,?,'VERIFIED',?,?,?,now())",
                                payment,
                                order,
                                branch,
                                received,
                                o.get("currency"),
                                method,
                                r.evidenceDocumentId(),
                                r.reference().trim(),
                                a.id());
                        s.exec(
                                "INSERT INTO"
                                    + " payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason)"
                                    + " VALUES(?,?,?,'VERIFIED',?,?,?,?,?)",
                                UUID.randomUUID(),
                                payment,
                                a.id(),
                                received,
                                o.get("currency"),
                                r.reference().trim(),
                                r.evidenceDocumentId(),
                                "Cobro directo de la farmacia comprobado externamente");
                    }
                    boolean settled = previouslyReceived.add(received).compareTo(total) >= 0;
                    s.exec(
                            "UPDATE child_order SET status=?,version=version+1,updated_at=now()"
                                    + " WHERE id=?",
                            "ACCEPTED".equals(originalStatus)
                                    ? (settled ? "PAID" : "ACCEPTED")
                                    : originalStatus,
                            order);
                    submissionStatus(o, "VERIFIED");
                    boolean all =
                            s.rows(
                                            "SELECT 1 FROM child_order WHERE purchase_id=? AND"
                                                + " status NOT IN"
                                                + " ('PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED','RESOLVED')",
                                            purchase)
                                    .isEmpty();
                    s.exec(
                            "UPDATE purchase SET status=?,version=version+1,updated_at=now() WHERE"
                                    + " id=?",
                            Set.of("FULFILLING", "COMPENSATING").contains(parentStatus)
                                    ? parentStatus
                                    : all ? "PAID" : "PARTIALLY_PAID",
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
                                    received.toPlainString(),
                                    "method",
                                    method,
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
        var o = s.order(order, false);
        return reconcilePayment(
                order,
                new PaymentDtos.Reconcile(
                        r.expectedVersion(),
                        r.decision(),
                        r.reference(),
                        r.evidenceDocumentId(),
                        r.verifiedExternally(),
                        r.reason(),
                        amount(o.get("total")),
                        text(o.get("currency"))),
                key);
    }

    @Transactional
    public Map<String, Object> reconcilePayment(UUID order, PaymentDtos.Reconcile r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "RECONCILE_PAYMENT:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    var snapshot = s.snapshot(o);
                    if (!Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status")))
                            || !(snapshot.get("paymentSubmission") instanceof Map<?, ?> report)
                            || !"PENDING".equals(text(report.get("status"))))
                        throw AppException.conflict(
                                "PAYMENT_RECONCILIATION_STATE_INVALID",
                                "Este pedido no tiene un pago informado pendiente de conciliación"
                                        + " tras su cancelación.");
                    requireReason(r.reason());
                    if (!Boolean.TRUE.equals(r.verifiedExternally())
                            || !Set.of("RECEIVED", "NOT_RECEIVED").contains(text(r.decision())))
                        throw AppException.badRequest(
                                "RECONCILIATION_VERIFICATION_REQUIRED",
                                "Comprobá externamente si ingresaron fondos y registrá el"
                                        + " resultado.");
                    s.evidence(r.evidenceDocumentId(), branch, "PAYMENT_RECEIPT", "OTHER");
                    String method =
                            "RECEIVED".equals(r.decision()) ? paymentMethod(r.method()) : null;
                    if ("RECEIVED".equals(r.decision())) {
                        if (text(r.reference()).isBlank())
                            throw AppException.badRequest(
                                    "PAYMENT_REFERENCE_REQUIRED",
                                    "Registrá la referencia del ingreso comprobado.");
                        var total =
                                FinancialRules.checkedAmount(
                                        r.amount(), r.currency(), o.get("currency"), false);
                        UUID payment = UUID.randomUUID();
                        s.exec(
                                "INSERT INTO"
                                    + " payment(id,order_id,branch_id,amount,currency,method,status,evidence_document_id,external_reference,verified_by,verified_at)"
                                    + " VALUES(?,?,?,?,?,?,'VERIFIED',?,?,?,now())",
                                payment,
                                order,
                                branch,
                                total,
                                o.get("currency"),
                                method,
                                r.evidenceDocumentId(),
                                r.reference().trim(),
                                actor.id());
                        s.exec(
                                "INSERT INTO"
                                    + " payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason)"
                                    + " VALUES(?,?,?,'VERIFIED',?,?,?,?,?)",
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
                                "UPDATE child_order SET version=version+1,updated_at=now() WHERE"
                                        + " id=?",
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
                                    "method",
                                    method,
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
                    var initialPurchase = s.purchase(purchase, false);
                    if (s.snapshot(initialPurchase).get("replacementRequestId") != null)
                        ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(
                                s,
                                id(
                                        s.row(
                                                        "SELECT id FROM child_order WHERE"
                                                                + " purchase_id=?",
                                                        purchase)
                                                .get("id")));
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
                                    "SELECT * FROM child_order WHERE purchase_id=? AND status NOT"
                                            + " IN ('CANCELLED','RESOLVED','COMPLETED') ORDER BY"
                                            + " branch_id FOR UPDATE",
                                    purchase);
                    for (var o : orders) {
                        FinancialRules.requireNotReplacing(s, o);
                        if (!cancellationAuthorized(o, r.reason(), a)) continue;
                        if (Set.of("DISPENSED", "DISPATCHED").contains(text(o.get("status")))
                                && !FinancialRules.highValue(s, o)) {
                            var f =
                                    s.row(
                                            "SELECT * FROM fulfillment WHERE order_id=?",
                                            o.get("id"));
                            if (!Set.of("INCIDENT", "RETURN_REQUIRED", "RETURNED")
                                    .contains(text(f.get("status"))))
                                incident(id(o.get("id")), r.reason(), null, a);
                        } else cancelOrderInternal(o, cancellationReason(o, r.reason()), a);
                    }
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
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    requireReason(r.reason());
                    FinancialRules.requireNotReplacing(s, o);
                    if (Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "ORDER_ALREADY_CANCELLED", "Este pedido ya tiene un cierre.");
                    if (!cancellationAuthorized(o, r.reason(), a)) return s.view(purchase);
                    if (Set.of("DISPENSED", "DISPATCHED", "COMPLETED")
                                    .contains(text(o.get("status")))
                            && !FinancialRules.highValue(s, o)) {
                        var f = s.row("SELECT * FROM fulfillment WHERE order_id=?", order);
                        if (!Set.of("INCIDENT", "RETURN_REQUIRED", "RETURNED")
                                .contains(text(f.get("status"))))
                            incident(order, r.reason(), null, a);
                        return s.view(purchase);
                    }
                    if (Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "ORDER_ALREADY_CANCELLED", "Este pedido ya tiene un cierre.");
                    cancelOrderInternal(o, cancellationReason(o, r.reason()), a);
                    finishCancellation(purchase);
                    s.audit.record(
                            a,
                            "CHILD_CANCELLED_GROUP_BLOCKED",
                            "child_order",
                            order,
                            map("reason", r.reason()));
                    return s.view(purchase);
                });
    }

    private String cancellationReason(Map<String, Object> order, String fallback) {
        return text(s.snapshot(order).get("cancellationReason")).isBlank()
                ? fallback
                : text(s.snapshot(order).get("cancellationReason"));
    }

    private boolean cancellationAuthorized(Map<String, Object> order, String reason, Actor actor) {
        if (!FinancialRules.highValue(s, order)) return true;
        var snapshot = s.snapshot(order);
        if (flag(snapshot.get("cancellationPending"))) {
            try {
                FinancialRules.requireControl(s, order, "CANCEL");
                return true;
            } catch (AppException e) {
                return false;
            }
        }
        UUID orderId = id(order.get("id"));
        s.release(orderId, actor);
        snapshot.put("cancellationPending", true);
        snapshot.put("cancellationReason", reason);
        snapshot.put("cancellationRequestedBy", actor.id());
        snapshot.put("cancellationRequestedAt", java.time.Instant.now().toString());
        s.exec(
                "UPDATE child_order SET snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                s.json.encode(snapshot),
                orderId);
        var fs = s.rows("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", orderId);
        if (!fs.isEmpty()) {
            var f = fs.getFirst();
            var details = s.snapshot(f);
            if (!Set.of("INCIDENT", "RETURN_REQUIRED", "RETURNED").contains(text(f.get("status"))))
                details.put("incidentPreviousStatus", f.get("status"));
            details.put("incidentKind", "CANCELLATION_REQUESTED");
            details.put("incidentReason", reason);
            s.exec(
                    "UPDATE fulfillment SET status=?,proof_code_hash=NULL,snapshot=cast(? as"
                            + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    "DRIVER".equals(f.get("custody_holder"))
                            ? "RETURN_REQUIRED"
                            : "RETURNED".equals(f.get("status")) ? "RETURNED" : "INCIDENT",
                    s.json.encode(details),
                    f.get("id"));
        }
        s.exec(
                "UPDATE purchase SET status='COMPENSATING',version=version+1,updated_at=now() WHERE"
                        + " id=?",
                order.get("purchase_id"));
        s.audit.record(
                actor,
                "HIGH_VALUE_CANCELLATION_REQUESTED",
                "child_order",
                orderId,
                map(
                        "reason",
                        reason,
                        "preparationStopped",
                        true,
                        "reservationReleased",
                        true,
                        "confirmed",
                        false));
        return false;
    }

    private void cancelOrderInternal(Map<String, Object> o, String reason, Actor actor) {
        UUID order = id(o.get("id"));
        s.release(order, actor);
        var snapshot = s.snapshot(o);
        snapshot.put("cancellationPending", false);
        s.exec(
                "UPDATE child_order SET snapshot=cast(? as jsonb) WHERE id=?",
                s.json.encode(snapshot),
                order);
        for (var payment :
                s.rows(
                        "SELECT * FROM payment WHERE order_id=? AND status IN"
                                + " ('VERIFIED','PARTIALLY_REFUNDED') FOR UPDATE",
                        order)) {
            BigDecimal refunded =
                    amount(
                            s.row(
                                            "SELECT coalesce(sum(amount),0) amount FROM refund"
                                                    + " WHERE payment_id=? AND status IN"
                                                    + " ('REQUESTED','PENDING','VERIFIED')",
                                            payment.get("id"))
                                    .get("amount"));
            BigDecimal remaining = amount(payment.get("amount")).subtract(refunded);
            if (remaining.signum() > 0) {
                s.exec(
                        "INSERT INTO"
                            + " refund(id,payment_id,requested_by,amount,currency,reason,branch_id)"
                            + " VALUES(?,?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        payment.get("id"),
                        actor.id(),
                        remaining,
                        payment.get("currency"),
                        reason,
                        payment.get("branch_id"));
                s.exec(
                        "INSERT INTO"
                            + " payment_operation(id,payment_id,actor_user_id,kind,amount,currency,reason)"
                            + " VALUES(?,?,?,'REFUND_REQUESTED',?,?,?)",
                        UUID.randomUUID(),
                        payment.get("id"),
                        actor.id(),
                        remaining,
                        payment.get("currency"),
                        reason);
            }
        }
        boolean completed = "COMPLETED".equals(o.get("status"));
        s.exec(
                "UPDATE child_order SET status=?,version=version+1,updated_at=now() WHERE id=?",
                completed ? "COMPLETED" : "CANCELLED",
                order);
        s.exec(
                "UPDATE fulfillment SET status=CASE WHEN custody_holder='DRIVER' THEN"
                        + " 'RETURN_REQUIRED' WHEN custody_holder='RECIPIENT' THEN 'RESOLVED' ELSE"
                        + " 'CANCELLED' END,driver_user_id=CASE WHEN custody_holder='DRIVER' THEN"
                        + " driver_user_id ELSE NULL"
                        + " END,proof_code_hash=NULL,version=version+1,updated_at=now() WHERE"
                        + " order_id=?",
                order);
        s.exec(
                "UPDATE routing_attempt SET status='REJECTED',reason=? WHERE purchase_id=? AND"
                        + " branch_id=? AND status IN ('PENDING','ACCEPTED')",
                reason,
                o.get("purchase_id"),
                o.get("branch_id"));
        ar.emedicapp.replacements.ReplacementLifecycle.close(s, o, false);
    }

    public void finishCancellation(UUID purchase) {
        boolean outstanding =
                !s.rows(
                                "SELECT 1 FROM refund r JOIN payment p ON p.id=r.payment_id JOIN"
                                    + " child_order o ON o.id=p.order_id WHERE o.purchase_id=? AND"
                                    + " r.status IN ('REQUESTED','PENDING')",
                                purchase)
                        .isEmpty();
        boolean active =
                !s.rows(
                                "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN"
                                        + " ('CANCELLED','COMPLETED','RESOLVED')",
                                purchase)
                        .isEmpty();
        boolean fulfilled =
                !s.rows(
                                "SELECT 1 FROM child_order WHERE purchase_id=? AND status IN"
                                        + " ('COMPLETED','RESOLVED')",
                                purchase)
                        .isEmpty();
        s.exec(
                "UPDATE purchase SET status=?,version=version+1,updated_at=now() WHERE id=?",
                outstanding || active ? "COMPENSATING" : fulfilled ? "RESOLVED" : "CANCELLED",
                purchase);
        s.exec(
                "UPDATE quote SET status='REJECTED' WHERE purchase_id=? AND status IN"
                        + " ('DRAFT','ISSUED')",
                purchase);
    }

    @Transactional
    public Map<String, Object> refund(UUID order, PurchaseDtos.Payment r, String key) {
        var pending =
                s.rows(
                        "SELECT r.* FROM refund r JOIN payment p ON p.id=r.payment_id WHERE"
                                + " p.order_id=? AND r.status IN ('REQUESTED','PENDING') ORDER BY"
                                + " r.created_at",
                        order);
        if (pending.size() != 1)
            throw AppException.conflict(
                    "REFUND_SELECTION_REQUIRED",
                    "Seleccioná el reintegro individual que se comprobó externamente.");
        var row = pending.getFirst();
        return refund(
                order,
                new PaymentDtos.RefundConfirm(
                        r.expectedVersion(),
                        id(row.get("id")),
                        amount(row.get("amount")),
                        text(row.get("currency")),
                        r.reference(),
                        r.evidenceDocumentId(),
                        r.verifiedExternally()),
                key);
    }

    @Transactional
    public Map<String, Object> refund(UUID order, PaymentDtos.RefundConfirm r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "REFUND:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    if (!Set.of("CANCELLED", "RESOLVED", "COMPLETED")
                            .contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "REFUND_REVIEW_REQUIRED",
                                "La devolución necesita la resolución farmacéutica del pedido"
                                        + " afectado.");
                    if (!Boolean.TRUE.equals(r.verifiedExternally())
                            || text(r.reference()).isBlank())
                        throw AppException.badRequest(
                                "EXTERNAL_VERIFICATION_REQUIRED",
                                "Comprobá el reintegro externo y registrá su referencia.");
                    s.evidence(r.evidenceDocumentId(), branch, "PAYMENT_RECEIPT", "OTHER");
                    var pending =
                            s.rows(
                                    "SELECT r.*,p.amount payment_amount FROM refund r JOIN payment"
                                        + " p ON p.id=r.payment_id WHERE p.order_id=? AND r.id=?"
                                        + " AND r.status IN ('REQUESTED','PENDING') FOR UPDATE OF"
                                        + " r,p",
                                    order,
                                    r.refundId());
                    if (pending.isEmpty())
                        throw AppException.conflict(
                                "NO_REFUND_PENDING",
                                "No hay un reintegro pendiente para este pedido.");
                    var requested = pending.getFirst();
                    BigDecimal verifiedAmount =
                            FinancialRules.checkedAmount(
                                    r.amount(), r.currency(), requested.get("currency"), false);
                    if (verifiedAmount.compareTo(amount(requested.get("amount"))) != 0)
                        throw AppException.badRequest(
                                "REFUND_AMOUNT_MISMATCH",
                                "El importe externo debe coincidir con la devolución"
                                        + " seleccionada.");
                    FinancialRules.requireControl(s, o, "REFUND");
                    requireCreditNote(
                            o, FinancialRules.refunds(s, order, false).add(verifiedAmount));
                    for (var rfd : pending) {
                        BigDecimal verified =
                                amount(
                                        s.row(
                                                        "SELECT coalesce(sum(amount),0) amount FROM"
                                                                + " refund WHERE payment_id=? AND"
                                                                + " status='VERIFIED'",
                                                        rfd.get("payment_id"))
                                                .get("amount"));
                        if (verified.add(amount(rfd.get("amount")))
                                        .compareTo(amount(rfd.get("payment_amount")))
                                > 0)
                            throw AppException.conflict(
                                    "REFUND_EXCEEDS_PAYMENT",
                                    "La devolución acumulada no puede superar el cobro original.");
                        s.exec(
                                "UPDATE refund SET"
                                    + " status='VERIFIED',external_reference=?,evidence_document_id=?,verified_by=?,verified_at=now(),branch_id=?"
                                    + " WHERE id=?",
                                r.reference().trim(),
                                r.evidenceDocumentId(),
                                a.id(),
                                branch,
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
                                "INSERT INTO"
                                    + " payment_operation(id,payment_id,actor_user_id,kind,amount,currency,external_reference,evidence_document_id,reason)"
                                    + " VALUES(?,?,?,'REFUND_VERIFIED',?,?,?,?,?)",
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
                    if (Set.of("CANCELLED", "RESOLVED").contains(text(o.get("status"))))
                        finishCancellation(purchase);
                    else s.finishIfComplete(purchase);
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
    public Map<String, Object> requestRefund(UUID order, PaymentDtos.RefundRequest r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), true);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "REFUND_REQUEST:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id"));
                    s.pharmacy(branch, true);
                    s.version(o, r.expectedVersion());
                    requireReason(r.reason());
                    FinancialRules.requireNotReplacing(s, o);
                    if (!Set.of("CANCELLED", "RESOLVED", "COMPLETED")
                            .contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "REFUND_REVIEW_REQUIRED",
                                "Resolvé primero la incidencia o la cancelación; la devolución no"
                                        + " modifica una entrega ya cumplida.");
                    s.evidence(
                            r.evidenceDocumentId(),
                            branch,
                            "OTHER",
                            "PAYMENT_RECEIPT",
                            "DELIVERY_PROOF");
                    BigDecimal requested =
                            FinancialRules.checkedAmount(
                                    r.amount(), r.currency(), o.get("currency"), false);
                    BigDecimal available =
                            FinancialRules.grossPaid(s, order)
                                    .subtract(FinancialRules.refunds(s, order, true));
                    if (requested.compareTo(available) > 0)
                        throw AppException.conflict(
                                "REFUND_EXCEEDS_PAYMENT",
                                "El total de devoluciones solicitadas y realizadas no puede superar"
                                        + " el dinero cobrado.");
                    List<UUID> lines = r.lineIds().stream().distinct().toList();
                    if (!lines.isEmpty()) {
                        BigDecimal lineTotal = BigDecimal.ZERO;
                        for (UUID line : lines) {
                            var item =
                                    s.rows(
                                            "SELECT q.total FROM order_item i JOIN quote_line q ON"
                                                    + " q.id=i.quote_line_id WHERE i.order_id=? AND"
                                                    + " i.id=?",
                                            order,
                                            line);
                            if (item.isEmpty())
                                throw AppException.badRequest(
                                        "REFUND_LINE_INVALID",
                                        "La línea seleccionada no pertenece a este pedido.");
                            lineTotal = lineTotal.add(amount(item.getFirst().get("total")));
                        }
                        BigDecimal previouslyAllocated = BigDecimal.ZERO;
                        for (var previous :
                                s.rows(
                                        "SELECT r.amount,r.line_ids FROM refund r JOIN payment p ON"
                                            + " p.id=r.payment_id WHERE p.order_id=? AND r.status"
                                            + " IN ('REQUESTED','PENDING','VERIFIED') AND"
                                            + " cardinality(r.line_ids)>0",
                                        order)) {
                            Object raw = previous.get("line_ids");
                            try {
                                Object[] ids =
                                        raw instanceof java.sql.Array array
                                                ? (Object[]) array.getArray()
                                                : (Object[]) raw;
                                if (Arrays.stream(ids)
                                        .map(PurchaseStore::id)
                                        .anyMatch(lines::contains))
                                    previouslyAllocated =
                                            previouslyAllocated.add(amount(previous.get("amount")));
                            } catch (java.sql.SQLException ex) {
                                throw new IllegalStateException(ex);
                            }
                        }
                        if (requested.compareTo(lineTotal.subtract(previouslyAllocated)) > 0)
                            throw AppException.conflict(
                                    "REFUND_EXCEEDS_LINES",
                                    "El importe supera el saldo devolvible de las líneas"
                                            + " seleccionadas.");
                    }
                    BigDecimal remaining = requested;
                    for (var payment :
                            s.rows(
                                    "SELECT * FROM payment WHERE order_id=? AND status IN"
                                            + " ('VERIFIED','PARTIALLY_REFUNDED') ORDER BY"
                                            + " created_at,id FOR UPDATE",
                                    order)) {
                        BigDecimal allocated =
                                amount(
                                        s.row(
                                                        "SELECT coalesce(sum(amount),0) amount FROM"
                                                            + " refund WHERE payment_id=? AND"
                                                            + " status IN"
                                                            + " ('REQUESTED','PENDING','VERIFIED')",
                                                        payment.get("id"))
                                                .get("amount"));
                        BigDecimal part =
                                remaining.min(amount(payment.get("amount")).subtract(allocated));
                        if (part.signum() <= 0) continue;
                        s.exec(
                                "INSERT INTO"
                                    + " refund(id,payment_id,requested_by,amount,currency,reason,evidence_document_id,line_ids,branch_id)"
                                    + " VALUES(?,?,?,?,?,?,?,cast(? as uuid[]),?)",
                                UUID.randomUUID(),
                                payment.get("id"),
                                actor.id(),
                                part,
                                o.get("currency"),
                                r.reason(),
                                r.evidenceDocumentId(),
                                "{"
                                        + String.join(
                                                ",", lines.stream().map(UUID::toString).toList())
                                        + "}",
                                branch);
                        s.exec(
                                "INSERT INTO"
                                    + " payment_operation(id,payment_id,actor_user_id,kind,amount,currency,reason)"
                                    + " VALUES(?,?,?,'REFUND_REQUESTED',?,?,?)",
                                UUID.randomUUID(),
                                payment.get("id"),
                                actor.id(),
                                part,
                                o.get("currency"),
                                r.reason());
                        remaining = remaining.subtract(part);
                        if (remaining.signum() == 0) break;
                    }
                    if (remaining.signum() != 0)
                        throw AppException.conflict(
                                "REFUND_ALLOCATION_INVALID",
                                "Los cobros necesitan conciliación antes de solicitar esta"
                                        + " devolución.");
                    s.exec(
                            "UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?",
                            order);
                    s.exec(
                            "UPDATE purchase SET"
                                + " status='COMPENSATING',version=version+1,updated_at=now() WHERE"
                                + " id=?",
                            o.get("purchase_id"));
                    s.audit.record(
                            actor,
                            "REFUND_REQUESTED_BY_PHARMACIST",
                            "child_order",
                            order,
                            map(
                                    "amount",
                                    requested,
                                    "lineIds",
                                    lines,
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId()));
                    return s.view(id(o.get("purchase_id")));
                });
    }

    private void requireCreditNote(Map<String, Object> order, BigDecimal refunded) {
        if (order.get("invoice_document_id") == null) return;
        BigDecimal credited =
                amount(
                        s.row(
                                        "SELECT coalesce(sum(amount),0) amount FROM"
                                                + " fiscal_adjustment WHERE order_id=? AND"
                                                + " kind='CREDIT'",
                                        order.get("id"))
                                .get("amount"));
        if (credited.compareTo(refunded) < 0)
            throw AppException.conflict(
                    "CREDIT_NOTE_REQUIRED",
                    "Registrá la nota de crédito emitida externamente y vinculada a la factura"
                            + " antes de confirmar este reintegro.");
    }

    @Transactional
    public Map<String, Object> control(UUID order, PaymentDtos.Control r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        UUID branch = id(o.get("branch_id"));
        s.pharmacy(branch, true);
        s.version(o, r.expectedVersion());
        requireReason(r.reason());
        if (!Set.of("CANCEL", "REFUND", "DISPATCH").contains(text(r.operation())))
            throw AppException.badRequest(
                    "CONTROL_OPERATION_INVALID",
                    "Elegí control de cancelación, compensación o despacho.");
        if (!FinancialRules.highValue(s, o))
            throw AppException.conflict(
                    "HIGH_VALUE_CONTROL_NOT_REQUIRED",
                    "El pedido no requiere doble control de alto valor.");
        if ("DISPATCH".equals(r.operation())
                && !Set.of("PAID", "PREPARING", "DISPENSED").contains(text(o.get("status"))))
            throw AppException.conflict(
                    "CONTROL_STATE_INVALID",
                    "Solicitá el control de despacho antes de la salida física de un pedido"
                            + " pagado.");
        if ("REFUND".equals(r.operation())
                && s.rows(
                                "SELECT 1 FROM fulfillment WHERE order_id=? AND status IN"
                                    + " ('INCIDENT','RETURN_REQUIRED','RETURNED','RESOLVED','CANCELLED')",
                                order)
                        .isEmpty()
                && !Set.of("CANCELLED", "RESOLVED", "COMPLETED").contains(text(o.get("status"))))
            throw AppException.conflict(
                    "CONTROL_STATE_INVALID",
                    "El control de compensación requiere una incidencia o una devolución"
                            + " pendiente.");
        if ("CANCEL".equals(r.operation()) && !flag(s.snapshot(o).get("cancellationPending")))
            throw AppException.conflict(
                    "CONTROL_STATE_INVALID",
                    "Primero debe existir una solicitud de cancelación detenida para revisión.");
        s.evidence(r.evidenceDocumentId(), branch, "OTHER", "DELIVERY_PROOF", "PAYMENT_RECEIPT");
        Actor actor = s.access.actor();
        String fingerprint = FinancialRules.fingerprint(s, o, r.operation());
        BigDecimal value =
                "REFUND".equals(r.operation())
                        ? FinancialRules.refunds(s, order, true)
                                .subtract(FinancialRules.refunds(s, order, false))
                        : amount(o.get("total")).max(amount(s.snapshot(o).get("commercialValue")));
        var existing =
                s.rows(
                        "SELECT * FROM financial_control WHERE order_id=? AND operation=? FOR"
                                + " UPDATE",
                        order,
                        r.operation());
        if (existing.isEmpty()
                || !fingerprint.equals(text(existing.getFirst().get("fingerprint")))) {
            s.exec(
                    "INSERT INTO"
                        + " financial_control(id,order_id,operation,fingerprint,amount,currency,requested_by,reason,evidence_document_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(order_id,operation) DO UPDATE SET"
                        + " fingerprint=excluded.fingerprint,amount=excluded.amount,currency=excluded.currency,requested_by=excluded.requested_by,reason=excluded.reason,evidence_document_id=excluded.evidence_document_id,created_at=now(),approved_by=NULL,approved_at=NULL",
                    UUID.randomUUID(),
                    order,
                    r.operation(),
                    fingerprint,
                    value,
                    o.get("currency"),
                    actor.id(),
                    r.reason(),
                    r.evidenceDocumentId());
        } else {
            var control = existing.getFirst();
            if (Objects.equals(control.get("requested_by"), actor.id()))
                throw AppException.conflict(
                        "SECOND_PHARMACIST_REQUIRED",
                        "La segunda aprobación debe registrarla otro farmacéutico de la sucursal.");
            if (control.get("approved_by") != null)
                throw AppException.conflict(
                        "CONTROL_ALREADY_APPROVED",
                        "Este control ya está aprobado para las condiciones actuales.");
            s.exec(
                    "UPDATE financial_control SET approved_by=?,approved_at=now() WHERE id=?",
                    actor.id(),
                    control.get("id"));
        }
        s.exec("UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?", order);
        s.exec(
                "UPDATE purchase SET version=version+1,updated_at=now() WHERE id=?",
                o.get("purchase_id"));
        s.audit.record(
                actor,
                "HIGH_VALUE_OPERATION_CONTROL",
                "child_order",
                order,
                map(
                        "operation",
                        r.operation(),
                        "fingerprint",
                        fingerprint,
                        "amount",
                        value,
                        "evidenceDocumentId",
                        r.evidenceDocumentId()));
        return s.view(id(o.get("purchase_id")));
    }

    @Transactional
    public Map<String, Object> fiscalAdjustment(
            UUID order, PaymentDtos.FiscalAdjustment r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "FISCAL_ADJUSTMENT:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    requireReason(r.reason());
                    requireReason(r.reference());
                    if (o.get("invoice_document_id") == null)
                        throw AppException.conflict(
                                "INVOICE_REQUIRED",
                                "La nota fiscal necesita una factura original registrada.");
                    if (!Set.of("CREDIT", "DEBIT").contains(text(r.kind())))
                        throw AppException.badRequest(
                                "FISCAL_ADJUSTMENT_INVALID", "Elegí nota de crédito o débito.");
                    BigDecimal value =
                            FinancialRules.checkedAmount(
                                    r.amount(), r.currency(), o.get("currency"), false);
                    s.evidence(r.evidenceDocumentId(), branch, "INVOICE");
                    BigDecimal documentaryBalance =
                            amount(o.get("total"))
                                    .add(
                                            amount(
                                                    s.row(
                                                                    "SELECT coalesce(sum(CASE WHEN"
                                                                        + " kind='DEBIT' THEN"
                                                                        + " amount ELSE -amount"
                                                                        + " END),0) amount FROM"
                                                                        + " fiscal_adjustment WHERE"
                                                                        + " order_id=?",
                                                                    order)
                                                            .get("amount")));
                    if ("CREDIT".equals(r.kind()) && value.compareTo(documentaryBalance) > 0)
                        throw AppException.conflict(
                                "CREDIT_EXCEEDS_INVOICE",
                                "Las notas de crédito acumuladas no pueden superar la factura y sus"
                                        + " débitos registrados.");
                    s.exec(
                            "INSERT INTO"
                                + " fiscal_adjustment(id,order_id,invoice_document_id,kind,reference,amount,currency,evidence_document_id,reason,created_by)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            order,
                            o.get("invoice_document_id"),
                            r.kind(),
                            r.reference().trim(),
                            value,
                            o.get("currency"),
                            r.evidenceDocumentId(),
                            r.reason(),
                            actor.id());
                    s.exec(
                            "UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?",
                            order);
                    s.exec(
                            "UPDATE purchase SET version=version+1,updated_at=now() WHERE id=?",
                            o.get("purchase_id"));
                    s.audit.record(
                            actor,
                            "EXTERNAL_FISCAL_NOTE_RECORDED",
                            "child_order",
                            order,
                            map(
                                    "kind",
                                    r.kind(),
                                    "amount",
                                    value,
                                    "reference",
                                    r.reference(),
                                    "invoiceDocumentId",
                                    o.get("invoice_document_id"),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId()));
                    return s.view(id(o.get("purchase_id")));
                });
    }

    @Transactional
    public Map<String, Object> invoiceIssue(UUID order, PaymentDtos.InvoiceIssue r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), false);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "INVOICE_ISSUE:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id"));
                    s.pharmacy(branch, false);
                    s.version(o, r.expectedVersion());
                    FinancialRules.requireNotCancelling(s, o);
                    requireReason(r.reason());
                    var snapshot = s.snapshot(o);
                    if (snapshot.get("replacementRequestId") != null)
                        throw AppException.conflict(
                                "REPLACEMENT_ORIGINAL_INVOICE",
                                "La reposición conserva la factura original y la evidencia de"
                                    + " resolución; no inicia otra emisión fiscal ni invalida ese"
                                    + " comprobante.");
                    if (!Set.of("PAID", "PREPARING", "DISPENSED").contains(text(o.get("status"))))
                        throw AppException.conflict(
                                "INVOICE_ISSUE_STATE_INVALID",
                                "Registrá la falla de emisión externa sobre un pedido pagado y"
                                    + " antes de la entrega física.");
                    if (o.get("invoice_document_id") != null)
                        throw AppException.conflict(
                                "INVOICE_ALREADY_ATTACHED",
                                "El pedido ya tiene una factura registrada; conservá su comprobante"
                                    + " y documentá cualquier ajuste por el circuito de notas"
                                    + " fiscales.");
                    s.evidence(r.evidenceDocumentId(), branch, "OTHER");
                    if (!s.rows(
                                    "SELECT 1 FROM review WHERE evidence_document_id=? AND kind IN"
                                        + " ('COVERAGE','SUBSTITUTION')",
                                    r.evidenceDocumentId())
                            .isEmpty())
                        throw AppException.forbidden(
                                "CLINICAL_EVIDENCE_NOT_OPERATIONAL",
                                "La falla fiscal requiere evidencia operativa propia; no puede usar"
                                    + " documentos clínicos de cobertura o sustitución.");
                    var issue = new LinkedHashMap<String, Object>();
                    if (snapshot.get("invoiceIssue") instanceof Map<?, ?> saved)
                        saved.forEach((k, v) -> issue.put(String.valueOf(k), v));
                    String reportedAt = java.time.Instant.now().toString();
                    issue.putAll(
                            map(
                                    "active",
                                    true,
                                    "priority",
                                    "HIGH",
                                    "status",
                                    "OPEN",
                                    "reason",
                                    r.reason().trim(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId(),
                                    "reportedBy",
                                    actor.id(),
                                    "reportedAt",
                                    reportedAt,
                                    "firstReportedAt",
                                    issue.getOrDefault("firstReportedAt", reportedAt),
                                    "attemptCount",
                                    num(issue.getOrDefault("attemptCount", 0)) + 1,
                                    "manualRetryRequired",
                                    true,
                                    "source",
                                    "MANUAL_EXTERNAL_FAILURE"));
                    snapshot.put("invoiceIssue", issue);
                    s.exec(
                            "UPDATE child_order SET snapshot=cast(? as"
                                + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                            s.json.encode(snapshot),
                            order);
                    s.audit.record(
                            actor,
                            "EXTERNAL_INVOICE_ISSUE_REPORTED",
                            "child_order",
                            order,
                            map(
                                    "priority",
                                    "HIGH",
                                    "reason",
                                    r.reason().trim(),
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId(),
                                    "attemptCount",
                                    issue.get("attemptCount"),
                                    "manualRetryRequired",
                                    true));
                    return s.view(id(o.get("purchase_id")));
                });
    }

    @Transactional
    public Map<String, Object> invoice(UUID order, Invoice r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        UUID branch = id(o.get("branch_id"));
        s.pharmacy(branch, false);
        s.version(o, r.expectedVersion());
        if (s.snapshot(o).get("replacementRequestId") != null)
            throw AppException.conflict(
                    "REPLACEMENT_ORIGINAL_INVOICE",
                    "La reposición conserva la factura original y la evidencia de resolución"
                            + " vinculadas; no reemplaza silenciosamente ese comprobante.");
        if (!Set.of("PAID", "PREPARING", "DISPENSED").contains(text(o.get("status"))))
            throw AppException.conflict(
                    "INVOICE_STATE_INVALID",
                    "Registrá la factura sobre un pedido pagado y antes del cumplimiento.");
        requireReason(r.reference());
        s.evidence(r.evidenceDocumentId(), branch, "INVOICE");
        if (!s.rows("SELECT 1 FROM fiscal_adjustment WHERE order_id=?", order).isEmpty())
            throw AppException.conflict(
                    "INVOICE_HAS_ADJUSTMENTS",
                    "La factura ya tiene notas vinculadas; conservá su documento original.");
        var snapshot = s.snapshot(o);
        snapshot.put("invoiceReference", r.reference().trim());
        boolean issueResolved = false;
        if (snapshot.get("invoiceIssue") instanceof Map<?, ?> saved
                && Boolean.TRUE.equals(saved.get("active"))) {
            var issue = new LinkedHashMap<String, Object>();
            saved.forEach((k, v) -> issue.put(String.valueOf(k), v));
            issue.putAll(
                    map(
                            "active",
                            false,
                            "status",
                            "RESOLVED",
                            "manualRetryRequired",
                            false,
                            "resolvedBy",
                            s.access.actor().id(),
                            "resolvedAt",
                            java.time.Instant.now().toString(),
                            "invoiceDocumentId",
                            r.evidenceDocumentId(),
                            "invoiceReference",
                            r.reference().trim()));
            snapshot.put("invoiceIssue", issue);
            issueResolved = true;
        }
        s.exec(
                "UPDATE child_order SET"
                    + " invoice_document_id=?,version=version+1,updated_at=now(),snapshot=cast(? as"
                    + " jsonb) WHERE id=?",
                r.evidenceDocumentId(),
                s.json.encode(snapshot),
                order);
        s.audit.record(
                s.access.actor(),
                "PHARMACY_INVOICE_ATTACHED",
                "child_order",
                order,
                map(
                        "reference",
                        r.reference(),
                        "documentId",
                        r.evidenceDocumentId(),
                        "invoiceIssueResolved",
                        issueResolved));
        fulfillment.autoAssign(order, null, s.access.actor());
        return s.view(id(o.get("purchase_id")));
    }

    public record Invoice(long expectedVersion, UUID evidenceDocumentId, String reference) {}

    @Transactional
    public Map<String, Object> reportIncident(UUID order, PurchaseDtos.Incident r) {
        return reportIncident(
                order,
                new PaymentDtos.Incident(
                        r.expectedVersion(), "OTHER", r.reason(), r.evidenceDocumentId()));
    }

    @Transactional
    public Map<String, Object> reportIncident(UUID order, PaymentDtos.Incident r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        var p = s.purchase(id(o.get("purchase_id")), false);
        Actor a = s.access.actor();
        UUID branch = id(o.get("branch_id"));
        boolean owner = a.hasRole("BUYER") && Objects.equals(p.get("buyer_user_id"), a.id());
        boolean driver =
                !s.rows(
                                "SELECT 1 FROM fulfillment WHERE order_id=? AND driver_user_id=?"
                                        + " AND status NOT IN"
                                        + " ('DELIVERED','COLLECTED','CANCELLED','RESOLVED')",
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
        if (!Set.of(
                        "RECIPIENT_ABSENT",
                        "RECIPIENT_REFUSED",
                        "PRODUCT_DISCREPANCY",
                        "TEMPERATURE",
                        "OTHER")
                .contains(text(r.kind())))
            throw AppException.badRequest(
                    "INCIDENT_KIND_INVALID", "Seleccioná el motivo operativo de la incidencia.");
        if (!Set.of("PAID", "PREPARING", "DISPENSED", "DISPATCHED", "COMPLETED")
                .contains(text(o.get("status"))))
            throw AppException.conflict(
                    "INCIDENT_STATE_INVALID",
                    "La incidencia requiere un pedido pagado o en cumplimiento.");
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (Set.of("RECIPIENT_ABSENT", "RECIPIENT_REFUSED").contains(r.kind())
                && (!"DELIVERY".equals(f.get("mode")) || !"PICKED_UP".equals(f.get("status"))))
            throw AppException.conflict(
                    "INCIDENT_STATE_INVALID",
                    "Este motivo requiere una entrega en camino con custodia del cadete.");
        incident(order, r.kind(), r.reason(), r.evidenceDocumentId(), a);
        return s.view(id(p.get("id")));
    }

    private void incident(UUID order, String reason, UUID evidence, Actor a) {
        incident(order, "OTHER", reason, evidence, a);
    }

    private void incident(UUID order, String kind, String reason, UUID evidence, Actor a) {
        var o = s.order(order, false);
        if (!Set.of("PAID", "PREPARING", "DISPENSED", "DISPATCHED", "COMPLETED")
                .contains(text(o.get("status"))))
            throw AppException.conflict(
                    "INCIDENT_STATE_INVALID",
                    "La incidencia requiere un pedido pagado o en cumplimiento; no modifica"
                            + " solicitudes ni presupuestos pendientes.");
        var fs = s.rows("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!fs.isEmpty()) {
            var f = fs.getFirst();
            var snapshot = s.snapshot(f);
            if (Set.of("CANCELLED", "RESOLVED", "INCIDENT", "RETURN_REQUIRED", "RETURNED")
                    .contains(text(f.get("status"))))
                throw AppException.conflict(
                        "INCIDENT_ALREADY_OPEN_OR_CLOSED",
                        "La incidencia ya está abierta o el cumplimiento tiene un cierre.");
            if (!"INCIDENT".equals(f.get("status")))
                snapshot.put("incidentPreviousStatus", f.get("status"));
            snapshot.put("incidentReason", reason);
            snapshot.put("incidentKind", kind);
            String status =
                    "DRIVER".equals(f.get("custody_holder")) ? "RETURN_REQUIRED" : "INCIDENT";
            s.exec(
                    "UPDATE fulfillment SET status=?,proof_code_hash=NULL,snapshot=cast(? as"
                            + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    status,
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec(
                    "INSERT INTO"
                        + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,reason)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    f.get("id"),
                    o.get("branch_id"),
                    a.id(),
                    "RETURN_REQUIRED".equals(status) ? "RETURN_REQUIRED" : "INCIDENT",
                    evidence,
                    reason);
        } else
            throw AppException.conflict(
                    "FULFILLMENT_REQUIRED",
                    "No existe un cumplimiento aceptado para registrar la incidencia.");
        s.exec(
                "UPDATE purchase SET status='COMPENSATING',version=version+1,updated_at=now() WHERE"
                        + " id=?",
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
        return resolveIncident(
                order,
                new PaymentDtos.Resolution(
                        r.expectedVersion(),
                        r.decision(),
                        r.reason(),
                        r.evidenceDocumentId(),
                        r.temperatureC(),
                        null,
                        null));
    }

    @Transactional
    public Map<String, Object> resolveIncident(UUID order, PaymentDtos.Resolution r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
        s.pharmacy(branch, true);
        s.version(o, r.expectedVersion());
        requireReason(r.reason());
        FinancialRules.requireNotReplacing(s, o);
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!Set.of("INCIDENT", "RETURN_REQUIRED", "RETURNED").contains(text(f.get("status"))))
            throw AppException.conflict(
                    "INCIDENT_REQUIRED", "Este pedido no tiene una incidencia abierta.");
        if (!Set.of("REFUND", "RETRY", "CLOSE_WITHOUT_REFUND").contains(text(r.decision())))
            throw AppException.badRequest(
                    "RESOLUTION_INVALID",
                    "Elegí reintegro, un reintento coordinado o cierre farmacéutico documentado.");
        s.evidence(r.evidenceDocumentId(), branch, "OTHER", "DELIVERY_PROOF", "PAYMENT_RECEIPT");
        if ("DRIVER".equals(f.get("custody_holder")))
            throw AppException.conflict(
                    "PHYSICAL_RETURN_REQUIRED",
                    "Registrá primero la recepción física del paquete en la farmacia; la custodia"
                            + " del cadete continúa vigente.");
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
            if (!cancellationAuthorized(o, r.reason(), actor)) return s.view(purchase);
            cancelOrderInternal(o, r.reason(), actor);
            s.exec(
                    "UPDATE fulfillment SET status='RESOLVED',snapshot=cast(? as"
                            + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
            finishCancellation(purchase);
        } else if ("CLOSE_WITHOUT_REFUND".equals(r.decision())) {
            if (!cancellationAuthorized(o, r.reason(), actor)) return s.view(purchase);
            FinancialRules.requireControl(s, o, "REFUND");
            s.release(order, actor);
            s.exec(
                    "UPDATE fulfillment SET"
                        + " status='RESOLVED',driver_user_id=NULL,proof_code_hash=NULL,snapshot=cast(?"
                        + " as jsonb),version=version+1,updated_at=now() WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec(
                    "UPDATE child_order SET status=?,version=version+1,updated_at=now() WHERE id=?",
                    "COMPLETED".equals(o.get("status")) ? "COMPLETED" : "RESOLVED",
                    order);
            var finalOrderSnapshot = s.snapshot(o);
            finalOrderSnapshot.put("cancellationPending", false);
            s.exec(
                    "UPDATE child_order SET snapshot=cast(? as jsonb) WHERE id=?",
                    s.json.encode(finalOrderSnapshot),
                    order);
            finishCancellation(purchase);
        } else {
            FinancialRules.requireNotCancelling(s, o);
            if (num(f.get("retry_count")) >= 1)
                throw AppException.conflict(
                        "RETRY_LIMIT_REACHED",
                        "Esta entrega ya utilizó su único reintento; la farmacia debe resolver el"
                                + " pedido.");
            if ("RECIPIENT_REFUSED".equals(snapshot.get("incidentKind")))
                throw AppException.conflict(
                        "RETRY_NOT_POSSIBLE",
                        "El rechazo de recepción requiere resolución farmacéutica, sin un nuevo"
                                + " despacho automático.");
            if (r.retryScheduledAt() == null
                    || !r.retryScheduledAt().isAfter(java.time.Instant.now())
                    || text(r.coordinationNote()).isBlank())
                throw AppException.badRequest(
                        "RETRY_COORDINATION_REQUIRED",
                        "Registrá la fecha futura acordada con el receptor y una nota de"
                                + " coordinación del único reintento.");
            String previous = text(snapshot.get("incidentPreviousStatus"));
            if (!Set.of("PENDING", "PREPARING", "READY", "ASSIGNED", "ACCEPTED", "PICKED_UP")
                            .contains(previous)
                    || "RECIPIENT".equals(f.get("custody_holder")))
                throw AppException.conflict(
                        "RETRY_NOT_POSSIBLE",
                        "Esta incidencia no conserva un paquete en custodia de la farmacia apto"
                                + " para reintentar.");
            if ("PICKED_UP".equals(previous) && f.get("return_received_at") == null)
                throw AppException.conflict(
                        "PHYSICAL_RETURN_REQUIRED",
                        "El reintento exige el retorno físico documentado del paquete.");
            fulfillment.checkConditions(
                    order, r.evidenceDocumentId(), r.temperatureC(), r.reason(), false);
            snapshot.put("coordinationNote", r.coordinationNote());
            snapshot.put("retryScheduledAt", r.retryScheduledAt().toString());
            String next = Set.of("PENDING", "PREPARING").contains(previous) ? previous : "READY";
            s.exec(
                    "UPDATE fulfillment SET"
                        + " status=?,driver_user_id=NULL,task_expires_at=NULL,retry_count=retry_count+1,retry_scheduled_at=?,pickup_expires_at=cast(?"
                        + " as timestamptz)+make_interval(hours =>"
                        + " ?),proof_code_hash=NULL,snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    next,
                    java.sql.Timestamp.from(r.retryScheduledAt()),
                    java.sql.Timestamp.from(r.retryScheduledAt()),
                    fulfillment.setting(
                            f, "pickupDeadlineHours", s.config.current().pickupDeadlineHours()),
                    s.json.encode(snapshot),
                    f.get("id"));
            String orderStatus =
                    "PENDING".equals(next)
                            ? "PAID"
                            : "PREPARING".equals(next) ? "PREPARING" : "DISPENSED";
            s.exec(
                    "UPDATE child_order SET status=?,version=version+1,updated_at=now() WHERE id=?",
                    orderStatus,
                    order);
            s.exec(
                    "UPDATE purchase SET status='FULFILLING',version=version+1,updated_at=now()"
                            + " WHERE id=?",
                    purchase);
            if ("READY".equals(next)) fulfillment.autoAssign(order, null, actor);
        }
        s.exec(
                "INSERT INTO"
                    + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,temperature_c,reason,snapshot)"
                    + " VALUES(?,?,?,?,'INCIDENT',?,?,?,cast(? as jsonb))",
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
                map(
                        "decision",
                        r.decision(),
                        "evidenceDocumentId",
                        r.evidenceDocumentId(),
                        "retryScheduledAt",
                        r.retryScheduledAt(),
                        "coordinationNote",
                        r.coordinationNote()));
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
                        "SELECT 1 FROM reservation r JOIN child_order o ON o.id=r.order_id WHERE"
                                + " o.purchase_id=? AND r.status='HELD' AND r.expires_at<=now()",
                        purchase)
                .isEmpty()) return false;
        var orders =
                s.rows(
                        "SELECT * FROM child_order WHERE purchase_id=? AND status<>'CANCELLED'"
                                + " ORDER BY branch_id FOR UPDATE",
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
