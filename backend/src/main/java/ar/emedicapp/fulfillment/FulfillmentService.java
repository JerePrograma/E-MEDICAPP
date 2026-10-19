package ar.emedicapp.fulfillment;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.*;
import ar.emedicapp.payments.FinancialRules;
import ar.emedicapp.purchase.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class FulfillmentService {
    private final PurchaseStore s;
    private final IdempotencyService idem;
    private final SecureRandom random = new SecureRandom();

    public FulfillmentService(PurchaseStore s, IdempotencyService idem) {
        this.s = s;
        this.idem = idem;
    }

    private void groupReady(UUID purchase) {
        if (!Set.of("PAID", "FULFILLING", "COMPENSATING")
                .contains(text(s.purchase(purchase, false).get("status"))))
            throw AppException.conflict(
                    "GROUP_NOT_READY",
                    "La compra completa debe tener pagos y condiciones resueltos antes del"
                        + " cumplimiento.");
        if (!s.rows(
                        "SELECT 1 FROM child_order WHERE purchase_id=? AND status NOT IN"
                            + " ('PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED','RESOLVED')",
                        purchase)
                .isEmpty())
            throw AppException.conflict(
                    "COMPLETE_PAYMENT_REQUIRED",
                    "Todos los pedidos activos deben tener el cobro confirmado antes del"
                        + " cumplimiento; cada farmacia resuelve su propia incidencia.");
    }

    public int setting(Map<String, Object> fulfillment, String key, int fallback) {
        var snapshot = s.snapshot(fulfillment);
        if (snapshot.get("config") instanceof Map<?, ?> config
                && config.get(key) instanceof Number number) return number.intValue();
        return fallback;
    }

    private void handoutReady(Map<String, Object> o, Map<String, Object> f) {
        FinancialRules.requireNotCancelling(s, o);
        var branch = s.row("SELECT active,verified FROM branch WHERE id=?", o.get("branch_id"));
        if (!flag(branch.get("active")) || !flag(branch.get("verified")))
            throw AppException.conflict(
                    "BRANCH_NOT_ELIGIBLE",
                    "La farmacia no conserva habilitación para una nueva entrega física; resolvé el"
                        + " pedido sin despacharlo.");
        FinancialRules.requireControl(s, o, "DISPATCH");
        if (FinancialRules.grossPaid(s, id(o.get("id"))).compareTo(FinancialRules.due(s, o)) < 0
                || FinancialRules.refunds(s, id(o.get("id")), true).signum() > 0)
            throw AppException.conflict(
                    "ORDER_BALANCE_PENDING",
                    "El pedido tiene un saldo o compensación pendiente; resolvé los hechos"
                        + " financieros antes de entregar el paquete.");
        if (f.get("retry_scheduled_at") != null
                && instant(f.get("retry_scheduled_at")).isAfter(Instant.now()))
            throw AppException.conflict(
                    "RETRY_NOT_DUE",
                    "El único reintento está coordinado para una fecha posterior.");
    }

    private void replacementReservationCurrent(Map<String, Object> order) {
        if (s.snapshot(order).get("replacementRequestId") != null
                && !s.rows(
                                "SELECT 1 FROM reservation WHERE order_id=? AND status='HELD' AND"
                                    + " expires_at<=now()",
                                order.get("id"))
                        .isEmpty())
            throw AppException.conflict(
                    "REPLACEMENT_RESERVATION_EXPIRED",
                    "La reserva sin cargo de reposición venció antes de dispensar; hace falta una"
                        + " propuesta nueva.");
    }

    @Transactional
    public Map<String, Object> prepare(UUID order, PurchaseDtos.Version r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
        s.pharmacy(branch, false);
        s.version(o, r.expectedVersion());
        FinancialRules.requireNotCancelling(s, o);
        if (flag(s.snapshot(o).get("replacementReviewPending")))
            throw AppException.conflict(
                    "REPLACEMENT_REVIEW_REQUIRED",
                    "La reposición necesita revisión farmacéutica de receta y nota nuevas antes de"
                        + " preparar.");
        replacementReservationCurrent(o);
        groupReady(purchase);
        if (!"PAID".equals(o.get("status")))
            throw AppException.conflict(
                    "PREPARATION_STATE_INVALID", "Sólo se prepara un pedido con pago confirmado.");
        s.exec(
                "UPDATE child_order SET status='PREPARING',version=version+1,updated_at=now() WHERE"
                    + " id=?",
                order);
        s.exec(
                "UPDATE fulfillment SET status='PREPARING',version=version+1,updated_at=now() WHERE"
                    + " order_id=?",
                order);
        s.exec(
                "UPDATE purchase SET status='FULFILLING',version=version+1,updated_at=now() WHERE"
                    + " id=?",
                purchase);
        s.audit.record(s.access.actor(), "PREPARATION_STARTED", "child_order", order, Map.of());
        return s.view(purchase);
    }

    @Transactional
    public Map<String, Object> dispense(UUID order, PurchaseDtos.Dispense r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), true);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "DISPENSE:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
                    s.pharmacy(branch, true);
                    s.version(o, r.expectedVersion());
                    FinancialRules.requireNotCancelling(s, o);
                    if (flag(s.snapshot(o).get("replacementReviewPending")))
                        throw AppException.conflict(
                                "REPLACEMENT_REVIEW_REQUIRED",
                                "La reposición todavía necesita su revisión farmacéutica"
                                    + " documentada.");
                    replacementReservationCurrent(o);
                    groupReady(purchase);
                    if (!"PREPARING".equals(o.get("status")))
                        throw AppException.conflict(
                                "DISPENSATION_STATE_INVALID",
                                "La dispensación se registra después de preparar el pedido.");
                    var p = s.purchase(purchase, false);
                    var b = s.row("SELECT * FROM branch WHERE id=?", branch);
                    if (!flag(b.get("active")) || !flag(b.get("verified")))
                        throw AppException.conflict(
                                "BRANCH_NOT_ELIGIBLE",
                                "La farmacia no conserva habilitación operativa vigente.");
                    if (p.get("family_authorization_id") != null
                            && s.rows(
                                            "SELECT 1 FROM family_authorization WHERE id=? AND"
                                                + " status='APPROVED' AND (expires_at IS NULL OR"
                                                + " expires_at>now())",
                                            p.get("family_authorization_id"))
                                    .isEmpty())
                        throw AppException.conflict(
                                "AUTHORIZATION_INVALID",
                                "La autorización del titular necesita revisión antes de"
                                    + " dispensar.");
                    boolean special = false;
                    Set<UUID> prescriptions = new HashSet<>();
                    for (var i : s.items(order)) {
                        boolean cold = flag(i.get("cold_chain")),
                                restricted = flag(i.get("restricted"));
                        special |= cold || restricted;
                        if (cold || restricted) {
                            var caps = s.json.readMap(text(b.get("capabilities")));
                            if (caps.get("capabilityEvidenceDocumentId") == null
                                    || caps.get("verifiedBy") == null
                                    || caps.get("verifiedAt") == null
                                    || (cold && !flag(b.get("cold_chain_capable")))
                                    || (restricted && !flag(b.get("restricted_capable"))))
                                throw AppException.conflict(
                                        "SPECIAL_CAPABILITY_REQUIRED",
                                        "Esta categoría exige capacidades de conservación o"
                                            + " dispensa documentadas.");
                            s.evidence(
                                    id(caps.get("capabilityEvidenceDocumentId")),
                                    branch,
                                    "CAPABILITY");
                            s.evidence(
                                    r.conditionEvidenceDocumentId(),
                                    branch,
                                    "DELIVERY_PROOF",
                                    "OTHER");
                            if (text(r.conditionNote()).isBlank())
                                throw AppException.badRequest(
                                        "CONDITION_EVIDENCE_REQUIRED",
                                        "Registrá los controles reales del paquete y sus"
                                            + " condiciones de conservación.");
                        }
                        if (cold) {
                            var meta = s.json.readMap(text(i.get("metadata")));
                            if (meta.get("minTemperatureC") == null
                                    || meta.get("maxTemperatureC") == null
                                    || r.temperatureC() == null)
                                throw AppException.conflict(
                                        "THERMAL_REQUIREMENTS_MISSING",
                                        "El producto requiere rango documentado y una observación"
                                            + " térmica real.");
                            if (r.temperatureC().compareTo(amount(meta.get("minTemperatureC"))) < 0
                                    || r.temperatureC()
                                                    .compareTo(amount(meta.get("maxTemperatureC")))
                                            > 0)
                                throw AppException.conflict(
                                        "TEMPERATURE_EXCURSION",
                                        "La observación está fuera del rango del producto; registrá"
                                            + " una incidencia y no despaches.");
                        }
                    }
                    var reservations =
                            s.rows(
                                    "SELECT r.*,pi.prescription_item_id rx_item FROM reservation r"
                                        + " JOIN purchase_item pi ON pi.id=(SELECT"
                                        + " oi.purchase_item_id FROM order_item oi WHERE"
                                        + " oi.id=r.order_item_id) WHERE r.order_id=? AND"
                                        + " r.status='HELD' ORDER BY r.product_id FOR UPDATE OF r",
                                    order);
                    if (reservations.isEmpty())
                        throw AppException.conflict(
                                "RESERVATION_REQUIRED",
                                "No existen reservas activas para dispensar este pedido.");
                    for (var reserve : reservations) {
                        int qty = ((Number) reserve.get("quantity")).intValue();
                        var stock =
                                s.row(
                                        "SELECT * FROM branch_stock WHERE branch_id=? AND"
                                            + " product_id=? FOR UPDATE",
                                        branch,
                                        reserve.get("product_id"));
                        if (num(stock.get("reserved_quantity")) < qty
                                || num(stock.get("quantity")) < qty)
                            throw AppException.conflict(
                                    "STOCK_RESERVATION_CONFLICT",
                                    "La reserva y el stock necesitan conciliación antes de"
                                        + " dispensar.");
                        if (reserve.get("prescription_item_id") != null) {
                            var rx =
                                    s.row(
                                            "SELECT ri.*,p.status"
                                                + " rx_status,p.provider_verification,p.expires_at"
                                                + " FROM prescription_item ri JOIN prescription p"
                                                + " ON p.id=ri.prescription_id WHERE ri.id=? FOR"
                                                + " UPDATE OF ri,p",
                                            reserve.get("prescription_item_id"));
                            if (!"APPROVED".equals(rx.get("rx_status"))
                                    || "UNKNOWN".equals(rx.get("provider_verification"))
                                    || rx.get("expires_at") == null
                                    || !instant(rx.get("expires_at")).isAfter(Instant.now())
                                    || text(r.prescriptionConsumptionReference()).isBlank())
                                throw AppException.conflict(
                                        "PRESCRIPTION_CONSUMPTION_REQUIRED",
                                        "Verificá receta vigente y registrá la referencia de"
                                            + " dispensación o consumo oficial comprobado.");
                            s.exec(
                                    "UPDATE prescription_item SET"
                                        + " reserved_quantity=reserved_quantity-?,dispensed_quantity=dispensed_quantity+?"
                                        + " WHERE id=?",
                                    qty,
                                    qty,
                                    rx.get("id"));
                            prescriptions.add(id(rx.get("prescription_id")));
                        }
                        s.exec(
                                "UPDATE branch_stock SET"
                                    + " quantity=quantity-?,reserved_quantity=reserved_quantity-?,version=version+1,updated_at=now()"
                                    + " WHERE branch_id=? AND product_id=?",
                                qty,
                                qty,
                                branch,
                                reserve.get("product_id"));
                        if (reserve.get("lot_id") != null) {
                            var lot =
                                    s.row(
                                            "SELECT * FROM stock_lot WHERE id=? FOR UPDATE",
                                            reserve.get("lot_id"));
                            if (!"AVAILABLE".equals(lot.get("status"))
                                    || (lot.get("expires_on") != null
                                            && java.time.LocalDate.parse(
                                                            text(lot.get("expires_on")))
                                                    .isBefore(java.time.LocalDate.now()))
                                    || num(lot.get("reserved_quantity")) < qty
                                    || num(lot.get("quantity")) < qty)
                                throw AppException.conflict(
                                        "LOT_NOT_USABLE",
                                        "El lote reservado venció o requiere conciliación; no se"
                                            + " puede dispensar.");
                            s.exec(
                                    "UPDATE stock_lot SET"
                                        + " quantity=quantity-?,reserved_quantity=reserved_quantity-?,version=version+1"
                                        + " WHERE id=?",
                                    qty,
                                    qty,
                                    reserve.get("lot_id"));
                        }
                        s.exec(
                                "UPDATE reservation SET"
                                    + " status='CONSUMED',dispensed_quantity=quantity WHERE id=?",
                                reserve.get("id"));
                        s.exec(
                                "UPDATE order_item SET dispensed_quantity=quantity WHERE id=?",
                                reserve.get("order_item_id"));
                        s.exec(
                                "INSERT INTO"
                                    + " stock_movement(id,branch_id,product_id,reservation_id,actor_user_id,kind,quantity_delta,reserved_delta,reason)"
                                    + " VALUES(?,?,?,?,?,'DISPENSE',?,?,?)",
                                UUID.randomUUID(),
                                branch,
                                reserve.get("product_id"),
                                reserve.get("id"),
                                a.id(),
                                -qty,
                                -qty,
                                text(r.prescriptionConsumptionReference()).isBlank()
                                        ? "Dispensación registrada por farmacéutico"
                                        : r.prescriptionConsumptionReference());
                    }
                    for (UUID rx : prescriptions) {
                        s.exec(
                                "INSERT INTO"
                                    + " review(id,order_id,branch_id,prescription_id,kind,status,reviewed_by,reason,snapshot)"
                                    + " VALUES(?,?,?,?,'PRESCRIPTION','APPROVED',?,?,cast(? as"
                                    + " jsonb))",
                                UUID.randomUUID(),
                                order,
                                branch,
                                rx,
                                a.id(),
                                r.prescriptionConsumptionReference(),
                                s.json.encode(map("action", "CONSUMPTION_RECORDED")));
                        if (s.rows(
                                        "SELECT 1 FROM prescription_item WHERE prescription_id=?"
                                            + " AND authorized_quantity>dispensed_quantity",
                                        rx)
                                .isEmpty())
                            s.exec("UPDATE prescription SET status='CONSUMED' WHERE id=?", rx);
                    }
                    var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
                    s.exec(
                            "UPDATE fulfillment SET"
                                + " status='READY',custody_holder='PHARMACY',pickup_expires_at=now()+make_interval(hours"
                                + " => ?),version=version+1,updated_at=now() WHERE id=?",
                            setting(
                                    f,
                                    "pickupDeadlineHours",
                                    s.config.current().pickupDeadlineHours()),
                            f.get("id"));
                    s.exec(
                            "UPDATE child_order SET"
                                + " status='DISPENSED',version=version+1,updated_at=now() WHERE"
                                + " id=?",
                            order);
                    s.exec(
                            "INSERT INTO"
                                + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,temperature_c,condition,snapshot)"
                                + " VALUES(?,?,?,?,'DISPENSED',?,?,?,cast(? as jsonb))",
                            UUID.randomUUID(),
                            f.get("id"),
                            branch,
                            a.id(),
                            r.conditionEvidenceDocumentId(),
                            r.temperatureC(),
                            text(r.conditionNote()),
                            s.json.encode(map("manualObservation", true, "special", special)));
                    autoAssign(order, null, a);
                    s.audit.record(
                            a,
                            "DISPENSATION_RECORDED",
                            "child_order",
                            order,
                            map("special", special));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> assign(UUID order, PurchaseDtos.Assign r) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
        s.pharmacy(branch, false);
        s.version(o, r.expectedVersion());
        FinancialRules.requireNotCancelling(s, o);
        groupReady(purchase);
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!"DELIVERY".equals(f.get("mode"))
                || !"DISPENSED".equals(o.get("status"))
                || !Set.of("READY", "ASSIGNED", "ACCEPTED").contains(text(f.get("status"))))
            throw AppException.conflict(
                    "ASSIGNMENT_STATE_INVALID",
                    "Asigná un cadete propio sobre un pedido dispensado y listo para entrega.");
        if (!"PHARMACY".equals(f.get("custody_holder")))
            throw AppException.conflict(
                    "PHYSICAL_CUSTODY_REQUIRED",
                    "La reasignación requiere que el paquete esté físicamente en la farmacia.");
        if (o.get("invoice_document_id") == null)
            throw AppException.conflict(
                    "INVOICE_REQUIRED",
                    "Registrá la factura emitida por la farmacia antes de entregar el paquete.");
        if (r.driverId() == null
                || s.rows(
                                "SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id JOIN"
                                    + " user_role ur ON ur.user_id=m.user_id AND ur.role=m.role"
                                    + " WHERE m.branch_id=? AND m.user_id=? AND m.role='DRIVER' AND"
                                    + " m.active AND u.active AND"
                                    + " coalesce(m.capabilities->>'available','true')<>'false'",
                                branch,
                                r.driverId())
                        .isEmpty())
            throw AppException.forbidden(
                    "OWN_DRIVER_REQUIRED",
                    "El cadete debe pertenecer a esta sucursal, estar activo y disponible.");
        checkDriverCapabilities(order, r.driverId());
        if (!driverReady(order, id(f.get("id")), r.driverId()))
            throw AppException.conflict(
                    "DRIVER_CAPACITY_UNAVAILABLE",
                    "El cadete no conserva turno disponible, capacidad libre o aptitud para recibir"
                        + " esta tarea.");
        if (!flag(
                s.row("SELECT driver_available FROM branch WHERE id=?", branch)
                        .get("driver_available")))
            throw AppException.conflict(
                    "DRIVER_UNAVAILABLE",
                    "La sucursal no tiene disponibilidad logística confirmada.");
        if (s.rows("SELECT 1 FROM branch WHERE id=? AND active AND verified", branch).isEmpty())
            throw AppException.conflict(
                    "BRANCH_NOT_ELIGIBLE",
                    "La sucursal no conserva habilitación para asignar un nuevo despacho; resolvé"
                        + " los pedidos comprometidos.");
        if (f.get("driver_user_id") != null
                && !Objects.equals(f.get("driver_user_id"), r.driverId()))
            s.exec(
                    "INSERT INTO"
                        + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,reason)"
                        + " VALUES(?,?,?,?,'REASSIGNED','Cambio de cadete antes de retiro; acceso"
                        + " anterior revocado')",
                    UUID.randomUUID(),
                    f.get("id"),
                    branch,
                    s.access.actor().id());
        var assignmentSnapshot = s.snapshot(f);
        assignmentSnapshot.put(
                "assignmentMessage",
                "Cadete propio asignado por la farmacia; espera su aceptación.");
        assignmentSnapshot.put("automaticAssignment", false);
        s.exec(
                "UPDATE fulfillment SET"
                    + " status='ASSIGNED',driver_user_id=?,task_expires_at=now()+make_interval(mins"
                    + " => ?),proof_code_hash=NULL,snapshot=cast(? as"
                    + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                r.driverId(),
                setting(f, "taskAcceptanceMinutes", s.config.current().taskAcceptanceMinutes()),
                s.json.encode(assignmentSnapshot),
                f.get("id"));
        s.exec("UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?", order);
        s.audit.record(
                s.access.actor(),
                "OWN_DRIVER_ASSIGNED",
                "child_order",
                order,
                map("driverId", r.driverId()));
        return s.view(purchase);
    }

    private boolean driverReady(UUID order, UUID fulfillment, UUID driver) {
        var o = s.order(order, false);
        var members =
                s.rows(
                        "SELECT m.id,m.capabilities FROM membership m JOIN app_user u ON"
                            + " u.id=m.user_id JOIN user_role ur ON ur.user_id=m.user_id AND"
                            + " ur.role=m.role WHERE m.branch_id=? AND m.user_id=? AND"
                            + " m.role='DRIVER' AND m.active AND u.active AND u.email_verified FOR"
                            + " UPDATE OF u,m",
                        o.get("branch_id"),
                        driver);
        if (members.isEmpty()) return false;
        var caps = s.json.readMap(text(members.getFirst().get("capabilities")));
        if (Boolean.FALSE.equals(caps.get("available"))) return false;
        int capacity = 1;
        try {
            if (caps.get("capacity") != null)
                capacity = Integer.parseInt(text(caps.get("capacity")));
        } catch (NumberFormatException ex) {
            return false;
        }
        if (capacity < 1 || capacity > 20) return false;
        long active =
                num(
                        s.row(
                                        "SELECT count(*) count FROM fulfillment WHERE"
                                            + " driver_user_id=? AND id<>? AND status IN"
                                            + " ('ASSIGNED','ACCEPTED','PICKED_UP','RETURN_REQUIRED')",
                                        driver,
                                        fulfillment)
                                .get("count"));
        if (active >= capacity) return false;
        try {
            checkDriverCapabilities(order, driver);
            return true;
        } catch (AppException ex) {
            return false;
        }
    }

    public void autoAssign(UUID order, UUID excludedDriver, Actor actor) {
        var o = s.order(order, false);
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!"DELIVERY".equals(f.get("mode"))
                || !"READY".equals(f.get("status"))
                || !"PHARMACY".equals(f.get("custody_holder"))
                || flag(s.snapshot(o).get("cancellationPending"))) return;
        UUID selected = null;
        if (o.get("invoice_document_id") != null
                && !s.rows(
                                "SELECT 1 FROM branch WHERE id=? AND driver_available AND active"
                                    + " AND verified",
                                o.get("branch_id"))
                        .isEmpty()) {
            for (var candidate :
                    s.rows(
                            "SELECT u.id FROM membership m JOIN user_role ur ON"
                                + " ur.user_id=m.user_id AND ur.role=m.role JOIN app_user u ON"
                                + " u.id=m.user_id WHERE m.branch_id=? AND m.role='DRIVER' AND"
                                + " m.active AND u.active AND u.email_verified ORDER BY u.id",
                            o.get("branch_id"))) {
                UUID driver = id(candidate.get("id"));
                if (!Objects.equals(driver, excludedDriver)
                        && driverReady(order, id(f.get("id")), driver)) {
                    selected = driver;
                    break;
                }
            }
        }
        var snapshot = s.snapshot(f);
        snapshot.put(
                "assignmentMessage",
                o.get("invoice_document_id") == null
                        ? "Registrá la factura externa para asignar el cadete."
                        : selected == null
                                ? "Sin cadete disponible"
                                : "Cadete propio asignado automáticamente; espera su aceptación.");
        snapshot.put("automaticAssignment", true);
        if (selected == null)
            s.exec(
                    "UPDATE fulfillment SET snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    s.json.encode(snapshot),
                    f.get("id"));
        else {
            s.exec(
                    "UPDATE fulfillment SET"
                        + " status='ASSIGNED',driver_user_id=?,task_expires_at=now()+make_interval(mins"
                        + " => ?),snapshot=cast(? as jsonb),version=version+1,updated_at=now()"
                        + " WHERE id=?",
                    selected,
                    setting(f, "taskAcceptanceMinutes", s.config.current().taskAcceptanceMinutes()),
                    s.json.encode(snapshot),
                    f.get("id"));
            s.exec(
                    "INSERT INTO"
                        + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,reason,snapshot)"
                        + " VALUES(?,?,?,?,'REASSIGNED','Asignación automática antes del retiro"
                        + " físico',cast(? as jsonb))",
                    UUID.randomUUID(),
                    f.get("id"),
                    o.get("branch_id"),
                    actor == null ? null : actor.id(),
                    s.json.encode(
                            map(
                                    "system",
                                    actor == null,
                                    "driverId",
                                    selected,
                                    "automaticAssignment",
                                    true)));
        }
        bumpOrder(order);
        s.audit.record(
                actor,
                "AUTOMATIC_OWN_DRIVER_ASSIGNMENT",
                "child_order",
                order,
                map("driverId", selected, "noDriverAvailable", selected == null));
    }

    private Map<String, Object> assigned(UUID fulfillment) {
        var f = s.row("SELECT * FROM fulfillment WHERE id=?", fulfillment);
        ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, id(f.get("order_id")));
        f = s.row("SELECT * FROM fulfillment WHERE id=? FOR UPDATE", fulfillment);
        Actor a = s.access.actor();
        if (!a.id().equals(f.get("driver_user_id")))
            throw AppException.forbidden(
                    "DRIVER_ASSIGNMENT_REQUIRED", "La tarea debe estar asignada a tu cuenta.");
        s.access.requireBranchRole(id(f.get("branch_id")), "DRIVER");
        return f;
    }

    @Transactional
    public Map<String, Object> acceptTask(UUID fulfillment, PurchaseDtos.Version r) {
        var f = assigned(fulfillment);
        s.version(f, r.expectedVersion());
        if (!"ASSIGNED".equals(f.get("status")))
            throw AppException.conflict("TASK_STATE_INVALID", "La tarea no espera aceptación.");
        if (f.get("task_expires_at") != null
                && !instant(f.get("task_expires_at")).isAfter(Instant.now()))
            throw AppException.conflict(
                    "TASK_EXPIRED",
                    "El plazo de aceptación venció; la farmacia debe asignar una nueva tarea.");
        checkDriverCapabilities(id(f.get("order_id")), s.access.actor().id());
        if (!driverReady(id(f.get("order_id")), fulfillment, s.access.actor().id()))
            throw AppException.conflict(
                    "DRIVER_CAPACITY_UNAVAILABLE",
                    "No conservás disponibilidad o capacidad para aceptar esta tarea.");
        s.exec(
                "UPDATE fulfillment SET status='ACCEPTED',version=version+1,updated_at=now() WHERE"
                    + " id=?",
                fulfillment);
        s.audit.record(
                s.access.actor(), "DELIVERY_TASK_ACCEPTED", "fulfillment", fulfillment, Map.of());
        return delivery(fulfillment);
    }

    @Transactional
    public Map<String, Object> rejectTask(UUID fulfillment, FulfillmentDtos.Rejection r) {
        var f = assigned(fulfillment);
        s.version(f, r.expectedVersion());
        if (!Set.of("ASSIGNED", "ACCEPTED").contains(text(f.get("status")))
                || !"PHARMACY".equals(f.get("custody_holder")))
            throw AppException.conflict(
                    "TASK_REJECTION_INVALID",
                    "Sólo se rechaza una tarea antes de recibir físicamente el paquete; después"
                        + " corresponde retorno.");
        if (text(r.reason()).isBlank())
            throw AppException.badRequest(
                    "TASK_REJECTION_REASON_REQUIRED",
                    "Registrá el motivo del rechazo de la tarea.");
        Actor actor = s.access.actor();
        s.exec(
                "UPDATE fulfillment SET"
                    + " status='READY',driver_user_id=NULL,task_expires_at=NULL,proof_code_hash=NULL,version=version+1,updated_at=now()"
                    + " WHERE id=?",
                fulfillment);
        bumpOrder(id(f.get("order_id")));
        s.exec(
                "INSERT INTO custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,reason)"
                    + " VALUES(?,?,?,?,'TASK_REJECTED',?)",
                UUID.randomUUID(),
                fulfillment,
                f.get("branch_id"),
                actor.id(),
                r.reason());
        s.audit.record(
                actor,
                "DELIVERY_TASK_REJECTED",
                "fulfillment",
                fulfillment,
                map("reason", r.reason()));
        autoAssign(id(f.get("order_id")), actor.id(), actor);
        // Rejection revokes this driver's access; return the action receipt, not another
        // driver's newly assigned task or the buyer's complete purchase.
        return map(
                "taskRejected",
                true,
                "fulfillmentId",
                fulfillment,
                "message",
                "Rechazo registrado. La farmacia conserva el paquete y el sistema verifica otro"
                    + " cadete propio.");
    }

    private void bumpOrder(UUID order) {
        var o = s.order(order, false);
        s.exec("UPDATE child_order SET version=version+1,updated_at=now() WHERE id=?", order);
        s.exec(
                "UPDATE purchase SET version=version+1,updated_at=now() WHERE id=?",
                o.get("purchase_id"));
    }

    @Transactional
    public Map<String, Object> returnReceived(
            UUID order, FulfillmentDtos.ReturnReceived r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), true);
        Actor actor = s.access.actor();
        return idem.run(
                actor,
                "PHYSICAL_RETURN:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    UUID branch = id(o.get("branch_id"));
                    s.pharmacy(branch, true);
                    s.version(o, r.expectedVersion());
                    var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
                    boolean fromDriver =
                            "RETURN_REQUIRED".equals(f.get("status"))
                                    && "DRIVER".equals(f.get("custody_holder"));
                    boolean fromRecipient =
                            "INCIDENT".equals(f.get("status"))
                                    && "RECIPIENT".equals(f.get("custody_holder"));
                    if (!fromDriver && !fromRecipient)
                        throw AppException.conflict(
                                "RETURN_STATE_INVALID",
                                "Registrá una devolución física real del cadete o receptor"
                                    + " vinculada a una incidencia abierta.");
                    if (text(r.custodyNote()).isBlank())
                        throw AppException.badRequest(
                                "CUSTODY_NOTE_REQUIRED",
                                "Registrá la recepción física y la condición real del paquete"
                                    + " retornado.");
                    s.evidence(r.evidenceDocumentId(), branch, "DELIVERY_PROOF", "OTHER");
                    boolean excursion = false;
                    for (var item : s.items(order))
                        if (flag(item.get("cold_chain"))) {
                            var meta = s.json.readMap(text(item.get("metadata")));
                            if (r.temperatureC() == null
                                    || meta.get("minTemperatureC") == null
                                    || meta.get("maxTemperatureC") == null)
                                throw AppException.badRequest(
                                        "RETURN_TEMPERATURE_REQUIRED",
                                        "El retorno de cadena de frío requiere una observación"
                                            + " térmica real; su condición no repone stock"
                                            + " automáticamente.");
                            excursion |=
                                    r.temperatureC().compareTo(amount(meta.get("minTemperatureC")))
                                                    < 0
                                            || r.temperatureC()
                                                            .compareTo(
                                                                    amount(
                                                                            meta.get(
                                                                                    "maxTemperatureC")))
                                                    > 0;
                        }
                    var snapshot = s.snapshot(f);
                    snapshot.put("returnTemperatureExcursion", excursion);
                    snapshot.put("returnCustodyNote", r.custodyNote());
                    s.exec(
                            "UPDATE fulfillment SET"
                                + " status='RETURNED',custody_holder='PHARMACY',return_received_at=now(),driver_user_id=NULL,proof_code_hash=NULL,snapshot=cast(?"
                                + " as jsonb),version=version+1,updated_at=now() WHERE id=?",
                            s.json.encode(snapshot),
                            f.get("id"));
                    bumpOrder(order);
                    s.exec(
                            "INSERT INTO"
                                + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,evidence_document_id,temperature_c,condition,snapshot)"
                                + " VALUES(?,?,?,?,'RETURNED',?,?,?,cast(? as jsonb))",
                            UUID.randomUUID(),
                            f.get("id"),
                            branch,
                            actor.id(),
                            r.evidenceDocumentId(),
                            r.temperatureC(),
                            r.custodyNote(),
                            s.json.encode(
                                    map(
                                            "automaticRestock",
                                            false,
                                            "temperatureExcursion",
                                            excursion)));
                    s.audit.record(
                            actor,
                            "PHYSICAL_RETURN_RECEIVED",
                            "child_order",
                            order,
                            map(
                                    "evidenceDocumentId",
                                    r.evidenceDocumentId(),
                                    "automaticRestock",
                                    false,
                                    "temperatureExcursion",
                                    excursion));
                    return s.view(id(o.get("purchase_id")));
                });
    }

    @Transactional
    public boolean expire(UUID fulfillment) {
        var existing = s.row("SELECT * FROM fulfillment WHERE id=?", fulfillment);
        var o =
                ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(
                        s, id(existing.get("order_id")));
        var f = s.row("SELECT * FROM fulfillment WHERE id=? FOR UPDATE", fulfillment);
        boolean task =
                "ASSIGNED".equals(f.get("status"))
                        && f.get("task_expires_at") != null
                        && !instant(f.get("task_expires_at")).isAfter(Instant.now());
        boolean pickup =
                "PICKUP".equals(f.get("mode"))
                        && "READY".equals(f.get("status"))
                        && f.get("pickup_expires_at") != null
                        && !instant(f.get("pickup_expires_at")).isAfter(Instant.now());
        if (!task && !pickup) return false;
        if (!"PHARMACY".equals(f.get("custody_holder"))) return false;
        var snapshot = s.snapshot(f);
        String reason = task ? "TASK_ACCEPTANCE_EXPIRED" : "PICKUP_DEADLINE_EXPIRED";
        if (pickup) {
            snapshot.put("incidentPreviousStatus", f.get("status"));
            snapshot.put("incidentKind", "PICKUP_EXPIRED");
            snapshot.put("incidentReason", "El pedido no fue retirado dentro del plazo acordado.");
        }
        s.exec(
                "UPDATE fulfillment SET"
                    + " status=?,driver_user_id=NULL,task_expires_at=NULL,proof_code_hash=NULL,snapshot=cast(?"
                    + " as jsonb),version=version+1,updated_at=now() WHERE id=?",
                task ? "READY" : "INCIDENT",
                s.json.encode(snapshot),
                fulfillment);
        bumpOrder(id(o.get("id")));
        if (pickup)
            s.exec("UPDATE purchase SET status='COMPENSATING' WHERE id=?", o.get("purchase_id"));
        s.exec(
                "INSERT INTO"
                    + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,reason,snapshot)"
                    + " VALUES(?,?,?,NULL,?,?,cast(? as jsonb))",
                UUID.randomUUID(),
                fulfillment,
                f.get("branch_id"),
                task ? "TASK_REJECTED" : "INCIDENT",
                reason,
                s.json.encode(map("system", true, "automaticRestock", false)));
        s.audit.record(null, reason, "fulfillment", fulfillment, map("system", true));
        if (task)
            autoAssign(
                    id(f.get("order_id")),
                    f.get("driver_user_id") == null ? null : id(f.get("driver_user_id")),
                    null);
        return true;
    }

    @Transactional
    public Map<String, Object> pickup(UUID fulfillment, PurchaseDtos.Pickup r) {
        var f = assigned(fulfillment);
        s.version(f, r.expectedVersion());
        var o = s.order(id(f.get("order_id")), false);
        groupReady(id(o.get("purchase_id")));
        if (!"ACCEPTED".equals(f.get("status"))
                || !"DISPENSED".equals(o.get("status"))
                || text(r.custodyNote()).isBlank())
            throw AppException.conflict(
                    "PICKUP_STATE_INVALID",
                    "Confirmá la recepción física del paquete de una tarea aceptada.");
        handoutReady(o, f);
        if (!driverReady(id(o.get("id")), fulfillment, s.access.actor().id()))
            throw AppException.conflict(
                    "DRIVER_CAPACITY_UNAVAILABLE",
                    "Antes del retiro necesitás conservar turno disponible, capacidad y aptitud"
                        + " documentada.");
        checkConditions(
                id(o.get("id")),
                r.conditionEvidenceDocumentId(),
                r.temperatureC(),
                r.custodyNote(),
                true);
        s.exec(
                "UPDATE fulfillment SET"
                    + " status='PICKED_UP',custody_holder='DRIVER',task_expires_at=NULL,pickup_expires_at=NULL,return_received_at=NULL,version=version+1,updated_at=now()"
                    + " WHERE id=?",
                fulfillment);
        s.exec(
                "UPDATE child_order SET status='DISPATCHED',version=version+1,updated_at=now()"
                    + " WHERE id=?",
                o.get("id"));
        s.exec(
                "INSERT INTO"
                    + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,condition,evidence_document_id,temperature_c)"
                    + " VALUES(?,?,?,?,'DRIVER_RECEIVED',?,?,?)",
                UUID.randomUUID(),
                fulfillment,
                f.get("branch_id"),
                s.access.actor().id(),
                r.custodyNote(),
                r.conditionEvidenceDocumentId(),
                r.temperatureC());
        s.audit.record(
                s.access.actor(),
                "PACKAGE_PICKED_UP_FOR_DELIVERY",
                "fulfillment",
                fulfillment,
                Map.of());
        return delivery(fulfillment);
    }

    @Transactional
    public Map<String, Object> location(UUID fulfillment, PurchaseDtos.Location r) {
        var f = assigned(fulfillment);
        if (!"PICKED_UP".equals(f.get("status")))
            throw AppException.conflict(
                    "LOCATION_STATE_INVALID",
                    "La ubicación se comparte únicamente durante una entrega activa.");
        if (r.lat() == null
                || r.lon() == null
                || r.lat().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || r.lon().abs().compareTo(BigDecimal.valueOf(180)) > 0
                || r.accuracy() == null
                || r.accuracy().signum() < 0)
            throw AppException.badRequest(
                    "LOCATION_INVALID", "La ubicación y su precisión deben ser válidas.");
        var snap = s.snapshot(f);
        snap.put(
                "location",
                map(
                        "lat",
                        r.lat(),
                        "lon",
                        r.lon(),
                        "accuracy",
                        r.accuracy(),
                        "observedAt",
                        Instant.now().toString()));
        s.exec(
                "UPDATE fulfillment SET snapshot=cast(? as jsonb),updated_at=now() WHERE id=?",
                s.json.encode(snap),
                fulfillment);
        return delivery(fulfillment);
    }

    @Transactional
    public Map<String, Object> code(UUID order) {
        var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
        var p = s.purchase(id(o.get("purchase_id")), false);
        s.buyer(p);
        if (!Set.of("DISPENSED", "DISPATCHED").contains(text(o.get("status"))))
            throw AppException.conflict(
                    "RECEIPT_NOT_READY",
                    "El código está disponible cuando el pedido fue dispensado.");
        var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
        if (!Set.of("READY", "ASSIGNED", "ACCEPTED", "PICKED_UP").contains(text(f.get("status"))))
            throw AppException.conflict(
                    "RECEIPT_NOT_READY",
                    "La recepción está cerrada o necesita resolver una incidencia.");
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        // Only a hash is persisted. Displaying a new code revokes the previous one.
        s.exec(
                "UPDATE fulfillment SET proof_code_hash=?,proof_expires_at=now()+make_interval(mins"
                    + " => ?),proof_failed_attempts=0 WHERE id=?",
                IdempotencyService.hash(f.get("id") + ":" + code),
                setting(f, "receiptCodeMinutes", s.config.current().receiptCodeMinutes()),
                f.get("id"));
        var fresh = s.row("SELECT proof_expires_at FROM fulfillment WHERE id=?", f.get("id"));
        return map(
                "code",
                code,
                "expiresAt",
                fresh.get("proof_expires_at"),
                "message",
                "Este código nuevo reemplaza al anterior. Mostralo sólo al recibir.");
    }

    private Map<String, Object> checkCode(Map<String, Object> f, FulfillmentDtos.Receipt r) {
        if (!Boolean.TRUE.equals(r.recipientVerified()) || text(r.note()).isBlank())
            throw AppException.badRequest(
                    "RECIPIENT_VERIFICATION_REQUIRED",
                    "Comprobá la identidad y autorización del receptor y registrá la recepción.");
        if (f.get("proof_code_hash") == null
                || f.get("proof_expires_at") == null
                || !instant(f.get("proof_expires_at")).isAfter(Instant.now()))
            throw AppException.conflict(
                    "CODE_EXPIRED", "El receptor debe mostrar un código vigente.");
        if (num(f.get("proof_failed_attempts")) >= 5)
            throw AppException.conflict(
                    "CODE_ATTEMPTS_EXHAUSTED",
                    "El código está bloqueado; el comprador debe generar uno nuevo.");
        String hash = IdempotencyService.hash(f.get("id") + ":" + text(r.code()));
        if (!java.security.MessageDigest.isEqual(
                hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                text(f.get("proof_code_hash"))
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            s.exec(
                    "UPDATE fulfillment SET proof_failed_attempts=proof_failed_attempts+1 WHERE"
                        + " id=?",
                    f.get("id"));
            s.audit.record(
                    s.access.actor(),
                    "RECEIPT_CODE_REJECTED",
                    "fulfillment",
                    id(f.get("id")),
                    Map.of());
            return map(
                    "receiptRejected",
                    true,
                    "code",
                    "CODE_INVALID",
                    "message",
                    "El código no coincide. El intento quedó registrado.");
        }
        return null;
    }

    public void checkDriverCapabilities(UUID order, UUID driver) {
        var o = s.order(order, false);
        UUID branch = id(o.get("branch_id"));
        boolean cold = s.items(order).stream().anyMatch(i -> flag(i.get("cold_chain"))),
                restricted = s.items(order).stream().anyMatch(i -> flag(i.get("restricted")));
        if (!cold && !restricted) return;
        var members =
                s.rows(
                        "SELECT m.capabilities FROM membership m JOIN app_user u ON u.id=m.user_id"
                            + " JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role WHERE"
                            + " m.user_id=? AND m.branch_id=? AND m.role='DRIVER' AND m.active AND"
                            + " u.active",
                        driver,
                        branch);
        if (members.isEmpty())
            throw AppException.forbidden(
                    "OWN_DRIVER_REQUIRED",
                    "El cadete debe conservar permiso vigente para esta sucursal.");
        var caps = s.json.readMap(text(members.getFirst().get("capabilities")));
        if ((cold && !flag(caps.get("coldChain")))
                || (restricted && !flag(caps.get("restricted")))
                || caps.get("capabilityEvidenceDocumentId") == null
                || caps.get("verifiedAt") == null
                || caps.get("verifiedBy") == null)
            throw AppException.forbidden(
                    "DRIVER_SPECIAL_CAPABILITY_REQUIRED",
                    "El cadete necesita capacidad documentada, evidencia, fecha y responsable de"
                        + " verificación para esta categoría.");
        s.evidence(id(caps.get("capabilityEvidenceDocumentId")), branch, "CAPABILITY");
    }

    public void checkConditions(
            UUID order, UUID evidence, BigDecimal temperature, String note, boolean driver) {
        var o = s.order(order, false);
        UUID branch = id(o.get("branch_id"));
        for (var i : s.items(order)) {
            boolean cold = flag(i.get("cold_chain")), restricted = flag(i.get("restricted"));
            if (!cold && !restricted) continue;
            s.evidence(evidence, branch, "DELIVERY_PROOF", "OTHER");
            if (text(note).isBlank())
                throw AppException.badRequest(
                        "CONDITION_NOTE_REQUIRED",
                        "Documentá las condiciones de custodia verificadas.");
            if (driver) {
                checkDriverCapabilities(order, s.access.actor().id());
                if (!Objects.equals(
                        s.row("SELECT owner_user_id FROM document WHERE id=?", evidence)
                                .get("owner_user_id"),
                        s.access.actor().id()))
                    throw AppException.forbidden(
                            "OWN_CUSTODY_EVIDENCE_REQUIRED",
                            "Adjuntá tu propia evidencia de esta observación física.");
            }
            if (cold) {
                var meta = s.json.readMap(text(i.get("metadata")));
                if (temperature == null
                        || meta.get("minTemperatureC") == null
                        || meta.get("maxTemperatureC") == null)
                    throw AppException.badRequest(
                            "TEMPERATURE_REQUIRED",
                            "Registrá una temperatura real contra el rango documentado del"
                                + " producto.");
                if (temperature.compareTo(amount(meta.get("minTemperatureC"))) < 0
                        || temperature.compareTo(amount(meta.get("maxTemperatureC"))) > 0)
                    throw AppException.conflict(
                            "TEMPERATURE_EXCURSION",
                            "La temperatura está fuera del rango; registrá una incidencia y detené"
                                + " la entrega.");
            }
        }
    }

    @Transactional
    public Map<String, Object> complete(UUID fulfillment, PurchaseDtos.Complete r, String key) {
        var f = s.row("SELECT * FROM fulfillment WHERE id=?", fulfillment);
        return complete(fulfillment, legacyReceipt(f, r), key);
    }

    private FulfillmentDtos.Receipt legacyReceipt(Map<String, Object> f, PurchaseDtos.Complete r) {
        var p = s.purchase(id(s.order(id(f.get("order_id")), false).get("purchase_id")), false);
        UUID document =
                p.get("family_authorization_id") == null
                        ? null
                        : id(
                                s.row(
                                                "SELECT document_id FROM family_authorization WHERE"
                                                    + " id=?",
                                                p.get("family_authorization_id"))
                                        .get("document_id"));
        return new FulfillmentDtos.Receipt(
                r.expectedVersion(),
                r.code(),
                r.recipientVerified(),
                r.note(),
                r.conditionEvidenceDocumentId(),
                r.temperatureC(),
                text(f.get("receiver_name")),
                text(f.get("receiver_identity")),
                document,
                true);
    }

    private void checkRecipient(
            Map<String, Object> f, Map<String, Object> o, FulfillmentDtos.Receipt r) {
        if (!Boolean.TRUE.equals(r.packageMatches()))
            throw AppException.conflict(
                    "PACKAGE_DISCREPANCY",
                    "Detené la entrega física: los medicamentos, cantidades y presentación deben"
                        + " comprobarse contra el pedido; registrá la discrepancia como"
                        + " incidencia.");
        if (text(r.recipientName()).isBlank()
                || text(r.recipientIdentity()).isBlank()
                || text(f.get("receiver_name")).isBlank()
                || text(f.get("receiver_identity")).isBlank())
            throw AppException.badRequest(
                    "RECIPIENT_IDENTITY_REQUIRED",
                    "Ingresá el nombre y DNI comprobados presencialmente contra el receptor"
                        + " declarado para esta compra.");
        if (!identity(text(r.recipientIdentity()))
                        .equals(identity(text(f.get("receiver_identity"))))
                || !text(r.recipientName())
                        .trim()
                        .replaceAll("\\s+", " ")
                        .equalsIgnoreCase(
                                text(f.get("receiver_name")).trim().replaceAll("\\s+", " ")))
            throw AppException.conflict(
                    "RECIPIENT_IDENTITY_MISMATCH",
                    "El receptor no coincide con el declarado; detené la entrega física y registrá"
                        + " la incidencia.");
        var p = s.purchase(id(o.get("purchase_id")), false);
        if (p.get("family_authorization_id") != null) {
            var auth =
                    s.row(
                            "SELECT * FROM family_authorization WHERE id=?",
                            p.get("family_authorization_id"));
            if (!"APPROVED".equals(auth.get("status"))
                    || !"BUY_AND_RECEIVE".equals(auth.get("scope"))
                    || (auth.get("expires_at") != null
                            && !instant(auth.get("expires_at")).isAfter(Instant.now()))
                    || !Objects.equals(auth.get("document_id"), r.authorizationDocumentId()))
                throw AppException.conflict(
                        "AUTHORIZATION_INVALID",
                        "La nota de esta compra debe conservar aprobación y vigencia para comprar y"
                            + " recibir; detené la entrega si fue revocada o venció.");
        } else if (r.authorizationDocumentId() != null)
            throw AppException.badRequest(
                    "AUTHORIZATION_UNRELATED", "La nota indicada no está vinculada a esta compra.");
    }

    private static String identity(String value) {
        return value.replaceAll("[.\\s-]", "").toUpperCase(Locale.ROOT);
    }

    @Transactional
    public Map<String, Object> complete(UUID fulfillment, FulfillmentDtos.Receipt r, String key) {
        assigned(fulfillment);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "COMPLETE_DELIVERY:" + fulfillment,
                key,
                r,
                () -> {
                    var f = assigned(fulfillment);
                    s.version(f, r.expectedVersion());
                    var o = s.order(id(f.get("order_id")), false);
                    groupReady(id(o.get("purchase_id")));
                    if (!"PICKED_UP".equals(f.get("status"))
                            || !"DISPATCHED".equals(o.get("status")))
                        throw AppException.conflict(
                                "DELIVERY_STATE_INVALID",
                                "La entrega requiere una tarea en camino y paquete despachado.");
                    FinancialRules.requireNotCancelling(s, o);
                    checkRecipient(f, o, r);
                    checkConditions(
                            id(o.get("id")),
                            r.conditionEvidenceDocumentId(),
                            r.temperatureC(),
                            r.note(),
                            true);
                    var rejected = checkCode(f, r);
                    if (rejected != null) return rejected;
                    finish(f, o, "DELIVERED", r);
                    return delivery(fulfillment);
                });
    }

    @Transactional
    public Map<String, Object> collect(UUID order, PurchaseDtos.Complete r, String key) {
        return collect(
                order,
                legacyReceipt(s.row("SELECT * FROM fulfillment WHERE order_id=?", order), r),
                key);
    }

    @Transactional
    public Map<String, Object> collect(UUID order, FulfillmentDtos.Receipt r, String key) {
        s.pharmacy(id(s.order(order, false).get("branch_id")), true);
        Actor a = s.access.actor();
        return idem.run(
                a,
                "COMPLETE_PICKUP:" + order,
                key,
                r,
                () -> {
                    var o = ar.emedicapp.replacements.ReplacementLifecycle.lockOrder(s, order);
                    s.pharmacy(id(o.get("branch_id")), true);
                    s.version(o, r.expectedVersion());
                    groupReady(id(o.get("purchase_id")));
                    var f = s.row("SELECT * FROM fulfillment WHERE order_id=? FOR UPDATE", order);
                    if (!"PICKUP".equals(f.get("mode"))
                            || !"READY".equals(f.get("status"))
                            || !"DISPENSED".equals(o.get("status")))
                        throw AppException.conflict(
                                "COLLECTION_STATE_INVALID",
                                "El retiro requiere un pedido dispensado y listo en farmacia.");
                    if (o.get("invoice_document_id") == null)
                        throw AppException.conflict(
                                "INVOICE_REQUIRED",
                                "Registrá la factura emitida por la farmacia antes del retiro.");
                    if (f.get("pickup_expires_at") != null
                            && !instant(f.get("pickup_expires_at")).isAfter(Instant.now()))
                        throw AppException.conflict(
                                "PICKUP_EXPIRED",
                                "El plazo de retiro venció; la farmacia debe resolver la incidencia"
                                    + " antes de entregar.");
                    handoutReady(o, f);
                    checkRecipient(f, o, r);
                    checkConditions(
                            order,
                            r.conditionEvidenceDocumentId(),
                            r.temperatureC(),
                            r.note(),
                            false);
                    var rejected = checkCode(f, r);
                    if (rejected != null) return rejected;
                    finish(f, o, "COLLECTED", r);
                    return s.view(id(o.get("purchase_id")));
                });
    }

    private void finish(
            Map<String, Object> f,
            Map<String, Object> o,
            String status,
            FulfillmentDtos.Receipt r) {
        s.exec(
                "UPDATE fulfillment SET"
                    + " status=?,custody_holder='RECIPIENT',proof_code_hash=NULL,proof_document_id=?,completed_at=now(),version=version+1,updated_at=now()"
                    + " WHERE id=?",
                status,
                r.conditionEvidenceDocumentId(),
                f.get("id"));
        s.exec(
                "UPDATE child_order SET status='COMPLETED',version=version+1,updated_at=now() WHERE"
                    + " id=?",
                o.get("id"));
        s.exec(
                "INSERT INTO"
                    + " custody_event(id,fulfillment_id,branch_id,actor_user_id,kind,condition,evidence_document_id,temperature_c)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                f.get("id"),
                f.get("branch_id"),
                s.access.actor().id(),
                status,
                r.note(),
                r.conditionEvidenceDocumentId(),
                r.temperatureC());
        s.finishIfComplete(id(o.get("purchase_id")));
        ar.emedicapp.replacements.ReplacementLifecycle.close(s, o, true);
        s.audit.record(
                s.access.actor(),
                "RECEIPT_CONFIRMED",
                "fulfillment",
                id(f.get("id")),
                map("mode", f.get("mode")));
    }

    public List<Map<String, Object>> drivers(UUID branch) {
        s.pharmacy(branch, false);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var d :
                s.rows(
                        "SELECT u.id,u.name,coalesce(m.capabilities->>'available','true')<>'false'"
                            + " available,m.capabilities FROM membership m JOIN app_user u ON"
                            + " u.id=m.user_id JOIN user_role ur ON ur.user_id=m.user_id AND"
                            + " ur.role=m.role WHERE m.branch_id=? AND m.role='DRIVER' AND m.active"
                            + " AND u.active AND u.email_verified ORDER BY u.name",
                        branch)) {
            var caps = s.json.readMap(text(d.remove("capabilities")));
            int capacity = 1;
            try {
                if (caps.get("capacity") != null)
                    capacity = Integer.parseInt(text(caps.get("capacity")));
            } catch (NumberFormatException ex) {
                capacity = 0;
            }
            long active =
                    num(
                            s.row(
                                            "SELECT count(*) count FROM fulfillment WHERE"
                                                + " driver_user_id=? AND status IN"
                                                + " ('ASSIGNED','ACCEPTED','PICKED_UP','RETURN_REQUIRED')",
                                            d.get("id"))
                                    .get("count"));
            d.put("capacity", capacity);
            d.put("activeTasks", active);
            d.put(
                    "available",
                    flag(d.get("available"))
                            && capacity > 0
                            && capacity <= 20
                            && active < capacity);
            result.add(d);
        }
        return result;
    }

    public Map<String, Object> delivery(UUID fulfillment) {
        var f =
                s.row(
                        "SELECT f.*,b.name branch_name,o.purchase_id FROM fulfillment f JOIN branch"
                            + " b ON b.id=f.branch_id JOIN child_order o ON o.id=f.order_id WHERE"
                            + " f.id=?",
                        fulfillment);
        Actor a = s.access.actor();
        boolean own = Objects.equals(f.get("driver_user_id"), a.id());
        if (own) s.access.requireBranchRole(id(f.get("branch_id")), "DRIVER");
        else s.pharmacy(id(f.get("branch_id")), false);
        var items = s.items(id(f.get("order_id")));
        boolean cold = items.stream().anyMatch(i -> flag(i.get("cold_chain"))),
                special = cold || items.stream().anyMatch(i -> flag(i.get("restricted")));
        var p = s.purchase(id(f.get("purchase_id")), false);
        UUID authorizationDocument = null;
        boolean authorizationRequired = p.get("family_authorization_id") != null,
                authorizationValid = !authorizationRequired;
        if (authorizationRequired) {
            var auth =
                    s.row(
                            "SELECT * FROM family_authorization WHERE id=?",
                            p.get("family_authorization_id"));
            authorizationDocument = id(auth.get("document_id"));
            authorizationValid =
                    "APPROVED".equals(auth.get("status"))
                            && "BUY_AND_RECEIVE".equals(auth.get("scope"))
                            && (auth.get("expires_at") == null
                                    || instant(auth.get("expires_at")).isAfter(Instant.now()));
        }
        return map(
                "id",
                f.get("id"),
                "orderId",
                f.get("order_id"),
                "orderVersion",
                s.order(id(f.get("order_id")), false).get("version"),
                "purchaseId",
                f.get("purchase_id"),
                "branchId",
                f.get("branch_id"),
                "branchName",
                f.get("branch_name"),
                "version",
                f.get("version"),
                "status",
                f.get("status"),
                "mode",
                f.get("mode"),
                "address",
                f.get("address"),
                "receiverName",
                f.get("receiver_name"),
                "receiverAuthorizationRequired",
                authorizationRequired,
                "receiverAuthorizationValid",
                authorizationValid,
                "authorizationDocumentId",
                authorizationDocument,
                "taskExpiresAt",
                f.get("task_expires_at"),
                "pickupExpiresAt",
                f.get("pickup_expires_at"),
                "retryCount",
                f.get("retry_count"),
                "retryScheduledAt",
                f.get("retry_scheduled_at"),
                "custodyHolder",
                f.get("custody_holder"),
                "returnReceivedAt",
                f.get("return_received_at"),
                "incidentKind",
                s.snapshot(f).get("incidentKind"),
                "coordinationNote",
                s.snapshot(f).get("coordinationNote"),
                "assignmentMessage",
                s.snapshot(f).get("assignmentMessage"),
                "location",
                s.snapshot(f).get("location"),
                "coldChain",
                cold,
                "special",
                special);
    }

    public List<Map<String, Object>> deliveries() {
        Actor a = s.access.actor();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var f :
                s.rows(
                        "SELECT f.id FROM fulfillment f WHERE (? AND f.driver_user_id=? AND"
                            + " EXISTS(SELECT 1 FROM membership m WHERE m.user_id=? AND"
                            + " m.branch_id=f.branch_id AND m.role='DRIVER' AND m.active)) OR"
                            + " EXISTS(SELECT 1 FROM membership m JOIN user_role ur ON"
                            + " ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=? AND"
                            + " m.branch_id=f.branch_id AND m.active AND m.role IN"
                            + " ('PHARMACY','PHARMACIST')) ORDER BY f.created_at DESC LIMIT 100",
                        a.hasRole("DRIVER"),
                        a.id(),
                        a.id(),
                        a.id())) result.add(delivery(id(f.get("id"))));
        return result;
    }
}
