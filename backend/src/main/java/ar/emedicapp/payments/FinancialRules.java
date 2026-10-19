package ar.emedicapp.payments;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.AppException;
import ar.emedicapp.purchase.IdempotencyService;
import ar.emedicapp.purchase.PurchaseStore;

import java.math.BigDecimal;
import java.util.*;

/** Small shared transaction rules; every fact is still written by the owning service. */
public final class FinancialRules {
    private FinancialRules() {}

    public static BigDecimal checkedAmount(
            BigDecimal value, String currency, Object expectedCurrency, boolean zero) {
        if (value == null
                || value.signum() < (zero ? 0 : 1)
                || value.scale() > 2
                || value.precision() - value.scale() > 14)
            throw AppException.badRequest(
                    "AMOUNT_INVALID", "Registrá un importe real válido, con hasta dos decimales.");
        if (currency == null
                || !currency.matches("[A-Z]{3}")
                || !currency.equals(text(expectedCurrency).trim()))
            throw AppException.badRequest(
                    "CURRENCY_MISMATCH", "La moneda verificada debe coincidir con la del pedido.");
        return value;
    }

    public static BigDecimal grossPaid(PurchaseStore s, UUID order) {
        return amount(
                s.row(
                                "SELECT coalesce(sum(amount),0) amount FROM payment WHERE"
                                    + " order_id=? AND status IN"
                                    + " ('VERIFIED','PARTIALLY_REFUNDED','REFUNDED')",
                                order)
                        .get("amount"));
    }

    public static BigDecimal refunds(PurchaseStore s, UUID order, boolean pending) {
        return amount(
                s.row(
                                "SELECT coalesce(sum(r.amount),0) amount FROM refund r JOIN payment"
                                    + " p ON p.id=r.payment_id WHERE p.order_id=? AND r.status IN ("
                                        + (pending
                                                ? "'REQUESTED','PENDING','VERIFIED'"
                                                : "'VERIFIED'")
                                        + ")",
                                order)
                        .get("amount"));
    }

    public static BigDecimal netPaid(PurchaseStore s, UUID order) {
        return grossPaid(s, order).subtract(refunds(s, order, false));
    }

    public static BigDecimal due(PurchaseStore s, Map<String, Object> order) {
        // Fiscal evidence never replaces the price explicitly accepted by the buyer.
        return amount(order.get("total"));
    }

    public static boolean highValue(PurchaseStore s, Map<String, Object> order) {
        return flag(s.snapshot(order).get("highValueRequired"));
    }

    public static String fingerprint(PurchaseStore s, Map<String, Object> order, String operation) {
        Object details;
        if ("CANCEL".equals(operation)) details = s.snapshot(order).get("cancellationReason");
        else if ("REFUND".equals(operation)) {
            details =
                    map(
                            "refunds",
                            s.rows(
                                    "SELECT r.id,r.amount::text FROM refund r JOIN payment p ON"
                                        + " p.id=r.payment_id WHERE p.order_id=? AND r.status IN"
                                        + " ('REQUESTED','PENDING','VERIFIED') ORDER BY r.id",
                                    order.get("id")),
                            "incident",
                            s.rows(
                                    "SELECT snapshot->>'incidentReason' reason FROM fulfillment"
                                        + " WHERE order_id=?",
                                    order.get("id")));
        } else
            details =
                    s.rows("SELECT retry_count FROM fulfillment WHERE order_id=?", order.get("id"));
        return IdempotencyService.hash(
                s.json.encode(
                        map(
                                "quote",
                                order.get("accepted_quote_id"),
                                "total",
                                text(order.get("total")),
                                "commercialValue",
                                s.snapshot(order).get("commercialValue"),
                                "operation",
                                operation,
                                "details",
                                details)));
    }

    public static void requireControl(
            PurchaseStore s, Map<String, Object> order, String operation) {
        if (!highValue(s, order)) return;
        String fingerprint = fingerprint(s, order, operation);
        if (s.rows(
                        "SELECT 1 FROM financial_control c WHERE c.order_id=? AND c.operation=? AND"
                            + " c.fingerprint=? AND c.approved_by IS NOT NULL AND EXISTS(SELECT 1"
                            + " FROM membership m JOIN user_role ur ON ur.user_id=m.user_id AND"
                            + " ur.role=m.role JOIN app_user u ON u.id=m.user_id WHERE"
                            + " m.branch_id=? AND m.role='PHARMACIST' AND m.active AND u.active AND"
                            + " m.user_id=c.requested_by) AND EXISTS(SELECT 1 FROM membership m"
                            + " JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role JOIN"
                            + " app_user u ON u.id=m.user_id WHERE m.branch_id=? AND"
                            + " m.role='PHARMACIST' AND m.active AND u.active AND"
                            + " m.user_id=c.approved_by)",
                        order.get("id"),
                        operation,
                        fingerprint,
                        order.get("branch_id"),
                        order.get("branch_id"))
                .isEmpty())
            throw AppException.conflict(
                    "HIGH_VALUE_CONTROL_REQUIRED",
                    "Esta operación de alto valor necesita aprobación vigente de dos farmacéuticos"
                        + " distintos de la sucursal.");
    }

    public static void requireNotCancelling(PurchaseStore s, Map<String, Object> order) {
        if (flag(s.snapshot(order).get("cancellationPending")))
            throw AppException.conflict(
                    "CANCELLATION_PENDING",
                    "La cancelación solicitada detuvo este pedido; no puede continuar su"
                        + " preparación ni entrega.");
    }

    public static void requireNotReplacing(PurchaseStore s, Map<String, Object> order) {
        if (s.snapshot(order).get("activeReplacementId") != null)
            throw AppException.conflict(
                    "REPLACEMENT_ACTIVE",
                    "El reclamo tiene una reposición aceptada activa; resolvé ese pedido antes de"
                        + " confirmar una devolución o cancelación del original.");
    }
}
