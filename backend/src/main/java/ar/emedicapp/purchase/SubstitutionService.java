package ar.emedicapp.purchase;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.AppException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class SubstitutionService {
    private final PurchaseStore s;
    private final PurchaseService purchases;

    public SubstitutionService(PurchaseStore s, PurchaseService purchases) {
        this.s = s;
        this.purchases = purchases;
    }

    public record Proposal(
            long expectedVersion,
            UUID lineId,
            UUID productId,
            int quantity,
            String reason,
            UUID evidenceDocumentId,
            boolean prescriptionCompatible) {}

    public record Response(long expectedVersion, String decision) {}

    @Transactional
    public Map<String, Object> propose(UUID order, Proposal input) {
        var o = s.order(order, true);
        var purchase = id(o.get("purchase_id"));
        var p = s.purchase(purchase, false);
        var branch = id(o.get("branch_id"));
        s.pharmacy(branch, true);
        s.version(o, input.expectedVersion());
        if (!"PROPOSED".equals(o.get("status"))
                || !Set.of("ROUTING", "QUOTED").contains(text(p.get("status"))))
            throw AppException.conflict(
                    "SUBSTITUTION_STATE_INVALID",
                    "Una compra aceptada debe resolverse mediante una incidencia.");
        s.requireNoPendingChange(purchase);
        if (input.productId() == null
                || input.quantity() < 1
                || input.quantity() > 100
                || text(input.reason()).trim().length() < 3
                || text(input.reason()).length() > 2000)
            throw AppException.badRequest(
                    "SUBSTITUTION_INVALID",
                    "Indicá la presentación alternativa, cantidad y motivo profesional.");
        var lines =
                s.rows(
                        "SELECT pi.*,pr.name,pr.presentation,pr.requires_prescription FROM"
                            + " order_item oi JOIN purchase_item pi ON pi.id=oi.purchase_item_id"
                            + " JOIN product pr ON pr.id=pi.product_id WHERE oi.id=? AND"
                            + " oi.order_id=? AND oi.active AND pi.active",
                        input.lineId(),
                        order);
        if (lines.isEmpty())
            throw AppException.badRequest(
                    "SUBSTITUTION_LINE_INVALID", "Seleccioná una línea vigente de este pedido.");
        var original = lines.getFirst();
        var replacement = s.row("SELECT * FROM product WHERE id=? AND active", input.productId());
        if (input.productId().equals(original.get("product_id"))
                || !s.rows(
                                "SELECT 1 FROM purchase_item WHERE purchase_id=? AND product_id=?"
                                        + " AND active",
                                purchase,
                                input.productId())
                        .isEmpty())
            throw AppException.badRequest(
                    "SUBSTITUTION_PRODUCT_INVALID",
                    "Elegí una presentación diferente que no figure ya en la solicitud.");
        boolean clinical =
                flag(original.get("requires_prescription"))
                        || flag(replacement.get("requires_prescription"));
        if (clinical) {
            if (original.get("prescription_item_id") == null)
                throw AppException.conflict(
                        "NEW_PRESCRIPTION_REQUIRED",
                        "La alternativa requiere una nueva solicitud con la receta"
                                + " correspondiente.");
            if (!input.prescriptionCompatible()
                    || num(original.get("quantity")) != input.quantity())
                throw AppException.badRequest(
                        "SUBSTITUTION_CLINICAL_REQUIRED",
                        "Confirmá que la sustitución está permitida por la receta, sin cambiar la"
                                + " cantidad autorizada. Si cambia la prescripción, se necesita una"
                                + " receta nueva.");
            var rx =
                    s.row(
                            "SELECT rx.* FROM prescription_item ri JOIN prescription rx ON"
                                    + " rx.id=ri.prescription_id WHERE ri.id=? FOR UPDATE OF rx",
                            original.get("prescription_item_id"));
            if (Set.of("REJECTED", "EXPIRED", "CONSUMED").contains(text(rx.get("status"))))
                throw AppException.conflict(
                        "PRESCRIPTION_INVALID",
                        "Esta versión de receta no admite una sustitución.");
            if (input.evidenceDocumentId() == null)
                throw AppException.badRequest(
                        "EVIDENCE_REQUIRED",
                        "Adjuntá la evidencia clínica que respalda la sustitución.");
            var evidence = s.row("SELECT * FROM document WHERE id=?", input.evidenceDocumentId());
            if (!"PRESCRIPTION".equals(evidence.get("kind"))
                    || !Objects.equals(evidence.get("owner_user_id"), s.access.actor().id())
                    || (evidence.get("branch_id") != null
                            && !Objects.equals(evidence.get("branch_id"), branch)))
                throw AppException.forbidden(
                        "DOCUMENT_SCOPE_INVALID",
                        "La evidencia clínica debe pertenecer al farmacéutico responsable y quedar"
                                + " vinculada a esta revisión.");
        }
        var stock =
                s.rows(
                        "SELECT 1 FROM branch_stock WHERE branch_id=? AND product_id=? AND active"
                                + " AND currency=? AND quantity-reserved_quantity>=?",
                        branch,
                        input.productId(),
                        p.get("currency"),
                        input.quantity());
        if (stock.isEmpty())
            throw AppException.conflict(
                    "SUBSTITUTION_STOCK_UNAVAILABLE",
                    "Esta sucursal no dispone de la presentación alternativa y cantidad"
                            + " propuestas.");
        for (var child :
                s.rows(
                        "SELECT id FROM child_order WHERE purchase_id=? ORDER BY branch_id",
                        purchase))
            s.release(
                    id(child.get("id")),
                    s.access.actor(),
                    "Propuesta de sustitución pendiente de consentimiento");
        s.exec(
                "UPDATE quote SET status='SUPERSEDED' WHERE purchase_id=? AND status='ISSUED'",
                purchase);
        s.exec(
                "UPDATE child_order SET snapshot=snapshot ||"
                    + " '{\"reviewApproved\":false}'::jsonb,version=version+1 WHERE purchase_id=?"
                    + " AND status='PROPOSED'",
                purchase);
        UUID proposal = UUID.randomUUID();
        var snap =
                map(
                        "originalProductId",
                        original.get("product_id"),
                        "originalName",
                        original.get("name"),
                        "originalPresentation",
                        original.get("presentation"),
                        "originalQuantity",
                        original.get("quantity"),
                        "name",
                        replacement.get("name"),
                        "presentation",
                        replacement.get("presentation"));
        s.exec(
                "INSERT INTO"
                    + " substitution_proposal(id,purchase_id,order_id,branch_id,original_item_id,product_id,quantity,reason,evidence_document_id,prescription_compatible,proposed_by,expires_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,now()+make_interval(mins => ?),cast(? as"
                    + " jsonb))",
                proposal,
                purchase,
                order,
                branch,
                original.get("id"),
                input.productId(),
                input.quantity(),
                input.reason().trim(),
                input.evidenceDocumentId(),
                input.prescriptionCompatible(),
                s.access.actor().id(),
                s.config.current().quoteMinutes(),
                s.json.encode(snap));
        s.exec(
                "INSERT INTO"
                    + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id,snapshot)"
                    + " VALUES(?,?,?,'SUBSTITUTION','PENDING',?,?,?,cast(? as jsonb))",
                UUID.randomUUID(),
                order,
                branch,
                s.access.actor().id(),
                input.reason().trim(),
                input.evidenceDocumentId(),
                s.json.encode(map("proposalId", proposal, "clinical", clinical)));
        var ps = s.snapshot(p);
        ps.put(
                "message",
                "La farmacia propuso una presentación alternativa. Revisá el cambio antes de"
                        + " continuar; después recibirás un presupuesto nuevo.");
        s.exec(
                "UPDATE purchase SET status='ROUTING',total=0,snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                s.json.encode(ps),
                purchase);
        s.audit.record(
                s.access.actor(),
                "SUBSTITUTION_PROPOSED",
                "purchase",
                purchase,
                map(
                        "proposalId",
                        proposal,
                        "originalItemId",
                        original.get("id"),
                        "productId",
                        input.productId(),
                        "quantity",
                        input.quantity()));
        return s.view(purchase);
    }

    @Transactional
    public Map<String, Object> respond(UUID purchase, UUID proposal, Response input) {
        var p = s.purchase(purchase, true);
        s.buyer(p);
        s.version(p, input.expectedVersion());
        var change =
                s.row(
                        "SELECT * FROM substitution_proposal WHERE id=? AND purchase_id=? FOR"
                                + " UPDATE",
                        proposal,
                        purchase);
        if (!"PENDING".equals(change.get("status"))
                || !Set.of("ROUTING", "QUOTED").contains(text(p.get("status"))))
            throw AppException.conflict(
                    "SUBSTITUTION_CLOSED", "Esta propuesta ya no está pendiente.");
        if (!Set.of("ACCEPT", "REJECT").contains(text(input.decision())))
            throw AppException.badRequest(
                    "DECISION_INVALID", "Elegí aceptar o rechazar la presentación propuesta.");
        if (!instant(change.get("expires_at")).isAfter(Instant.now()))
            throw AppException.conflict(
                    "SUBSTITUTION_EXPIRED",
                    "La propuesta venció. Esperá la liberación o solicitá una nueva revisión.");
        if ("ACCEPT".equals(input.decision())) {
            var original =
                    s.row(
                            "SELECT * FROM purchase_item WHERE id=? AND active FOR UPDATE",
                            change.get("original_item_id"));
            s.exec("UPDATE purchase_item SET active=false WHERE id=?", original.get("id"));
            s.exec(
                    "INSERT INTO"
                        + " purchase_item(id,purchase_id,product_id,quantity,prescription_item_id,supersedes_id)"
                        + " VALUES(?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    purchase,
                    change.get("product_id"),
                    change.get("quantity"),
                    original.get("prescription_item_id"),
                    original.get("id"));
        }
        s.exec(
                "UPDATE substitution_proposal SET status=?,responded_by=?,responded_at=now() WHERE"
                        + " id=?",
                "ACCEPT".equals(input.decision()) ? "ACCEPTED" : "REJECTED",
                s.access.actor().id(),
                proposal);
        s.exec(
                "UPDATE review SET status=? WHERE order_id=? AND kind='SUBSTITUTION' AND"
                        + " snapshot->>'proposalId'=?",
                "ACCEPT".equals(input.decision()) ? "APPROVED" : "REJECTED",
                change.get("order_id"),
                proposal.toString());
        purchases.route(p);
        s.audit.record(
                s.access.actor(),
                "SUBSTITUTION_" + input.decision(),
                "purchase",
                purchase,
                map("proposalId", proposal, "newQuoteConsentRequired", true));
        return s.view(purchase);
    }

    @Transactional
    public boolean expire(UUID purchase, UUID proposal) {
        var p = s.purchase(purchase, true);
        var changes =
                s.rows(
                        "SELECT * FROM substitution_proposal WHERE id=? AND purchase_id=? AND"
                                + " status='PENDING' AND expires_at<=now() FOR UPDATE",
                        proposal,
                        purchase);
        if (changes.isEmpty()) return false;
        s.exec(
                "UPDATE substitution_proposal SET status='EXPIRED',responded_at=now() WHERE id=?",
                proposal);
        if (Set.of("ROUTING", "QUOTED").contains(text(p.get("status")))) {
            var ps = s.snapshot(p);
            ps.put(
                    "message",
                    "La presentación alternativa venció. Podés volver a buscar para los"
                            + " medicamentos originales.");
            s.exec(
                    "UPDATE purchase SET status='UNAVAILABLE',snapshot=cast(? as"
                            + " jsonb),version=version+1 WHERE id=?",
                    s.json.encode(ps),
                    purchase);
        }
        s.audit.record(
                null,
                "SYSTEM_SUBSTITUTION_EXPIRY",
                "purchase",
                purchase,
                map("proposalId", proposal));
        return true;
    }
}
