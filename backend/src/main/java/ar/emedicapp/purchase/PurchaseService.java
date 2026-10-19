package ar.emedicapp.purchase;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.common.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class PurchaseService {
    private final PurchaseStore s;
    private final IdempotencyService idem;
    private final Clock clock;
    private final RoutingPlanner planner = new RoutingPlanner();

    @Value("${emedicapp.mode:DEMO}")
    private String appMode;

    public PurchaseService(PurchaseStore s, IdempotencyService idem, Clock clock) {
        this.s = s;
        this.idem = idem;
        this.clock = clock;
    }

    private Map<String, Object> branchConditions(Map<String, Object> branch) {
        var caps = s.json.readMap(text(branch.get("capabilities")));
        return map(
                "name",
                branch.get("name"),
                "merchantName",
                branch.get("merchant_name"),
                "address",
                branch.get("address"),
                "paymentInstructions",
                branch.get("payment_instructions"),
                "deliveryRadiusKm",
                amount(caps.getOrDefault("deliveryRadiusKm", "20"))
                        .stripTrailingZeros()
                        .toPlainString(),
                "opensAt",
                caps.get("opensAt"),
                "closesAt",
                caps.get("closesAt"),
                "deliveryMinutes",
                caps.getOrDefault("deliveryMinutes", 60));
    }

    @Transactional
    public Map<String, Object> create(PurchaseDtos.Create r, String key) {
        Actor actor = s.access.actor();
        actor.requireRole("BUYER");
        return idem.run(actor, "CREATE_PURCHASE", key, r, () -> createInternal(r));
    }

    private Map<String, Object> createInternal(PurchaseDtos.Create r) {
        Actor actor = s.access.actor();
        actor.requireRole("BUYER");
        if (r.items() == null || r.items().isEmpty() || r.items().size() > 30)
            throw AppException.badRequest(
                    "BASKET_REQUIRED", "Agregá entre uno y treinta productos.");
        if (r.beneficiaryName() == null
                || r.beneficiaryName().isBlank()
                || r.beneficiaryBirthDate() == null
                || r.beneficiaryBirthDate().isAfter(LocalDate.now(clock).minusYears(18)))
            throw AppException.badRequest(
                    "ADULT_REQUIRED",
                    "El beneficiario debe ser adulto y tener nombre y fecha de nacimiento"
                            + " válidos.");
        if (!Set.of("DELIVERY", "PICKUP").contains(text(r.mode())))
            throw AppException.badRequest("MODE_INVALID", "Elegí entrega o retiro.");
        var cfg = s.config.current();
        if (("DELIVERY".equals(r.mode()) && !cfg.deliveryEnabled())
                || ("PICKUP".equals(r.mode()) && !cfg.pickupEnabled()))
            throw AppException.conflict("MODE_DISABLED", "Esta modalidad no está habilitada.");
        if (r.destinationLat() == null
                || r.destinationLon() == null
                || r.destinationLat().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || r.destinationLon().abs().compareTo(BigDecimal.valueOf(180)) > 0)
            throw AppException.badRequest(
                    "DESTINATION_REQUIRED", "Confirmá un punto válido para buscar la farmacia.");
        if ("DELIVERY".equals(r.mode()) && text(r.destinationAddress()).length() < 5)
            throw AppException.badRequest("ADDRESS_REQUIRED", "Confirmá el domicilio de entrega.");
        Map<UUID, Integer> required = new LinkedHashMap<>();
        for (var i : r.items()) {
            if (i.productId() == null
                    || i.quantity() < 1
                    || i.quantity() > 100
                    || required.putIfAbsent(i.productId(), i.quantity()) != null)
                throw AppException.badRequest(
                        "BASKET_INVALID",
                        "Cada producto debe aparecer una vez, con una cantidad entre 1 y 100.");
            s.row("SELECT id FROM product WHERE id=? AND active", i.productId());
        }
        String beneficiaryIdentity =
                r.thirdParty()
                        ? text(r.beneficiaryIdentity()).trim().replaceAll("[.\\s]", "")
                        : text(s.row("SELECT dni FROM app_user WHERE id=?", actor.id()).get("dni"));
        String suppliedReceiverIdentity =
                text(r.receiverIdentity()).trim().replaceAll("[.\\s]", "");
        boolean identityDiffers =
                !suppliedReceiverIdentity.isBlank()
                        && !suppliedReceiverIdentity.equals(beneficiaryIdentity);
        if (identityDiffers && text(r.receiverName()).isBlank())
            throw AppException.badRequest(
                    "RECEIVER_NAME_REQUIRED", "Completá el nombre de la otra persona que recibe.");
        boolean differentReceiver =
                !text(r.receiverName()).isBlank()
                        && (!r.receiverName().trim().equalsIgnoreCase(r.beneficiaryName().trim())
                                || identityDiffers);
        boolean noteRequired = r.thirdParty() || differentReceiver;
        String receiverName =
                differentReceiver ? r.receiverName().trim() : r.beneficiaryName().trim();
        String receiverIdentity =
                differentReceiver
                        ? text(r.receiverIdentity()).trim().replaceAll("[.\\s]", "")
                        : beneficiaryIdentity;
        if (!beneficiaryIdentity.matches("[0-9]{7,12}") || !receiverIdentity.matches("[0-9]{7,12}"))
            throw AppException.badRequest(
                    "IDENTITY_REQUIRED",
                    "Completá el DNI del beneficiario y de la persona que recibe o retira.");
        if (text(r.receiverName()).length() > 200 || text(r.receiverIdentity()).length() > 80)
            throw AppException.badRequest(
                    "RECEIVER_INVALID", "Revisá los datos de la persona que recibe.");
        if (!r.thirdParty()) {
            Map<String, Object> u =
                    s.row("SELECT name,birth_date FROM app_user WHERE id=?", actor.id());
            if (!text(u.get("name")).equalsIgnoreCase(r.beneficiaryName().trim())
                    || !text(u.get("birth_date")).equals(r.beneficiaryBirthDate().toString()))
                throw AppException.badRequest(
                        "SELF_IDENTITY_MISMATCH",
                        "Para comprar para otra persona adulta, indicá representación y adjuntá"
                                + " autorización.");
        }
        if (noteRequired) {
            if (r.authorizationDocumentId() == null)
                throw AppException.badRequest(
                        "AUTHORIZATION_REQUIRED", "Adjuntá la nota firmada del titular adulto.");
            s.unusedAuthorizationDocument(r.authorizationDocumentId());
        }
        List<Map<String, Object>> prior =
                s.rows(
                        "SELECT id FROM beneficiary WHERE owner_user_id=? AND name=? AND"
                                + " birth_date=? ORDER BY created_at LIMIT 1",
                        actor.id(),
                        r.beneficiaryName().trim(),
                        r.beneficiaryBirthDate());
        UUID beneficiary = prior.isEmpty() ? UUID.randomUUID() : id(prior.getFirst().get("id"));
        if (prior.isEmpty())
            s.exec(
                    "INSERT INTO beneficiary(id,owner_user_id,name,birth_date) VALUES(?,?,?,?)",
                    beneficiary,
                    actor.id(),
                    r.beneficiaryName().trim(),
                    r.beneficiaryBirthDate());
        UUID auth = null;
        if (noteRequired) {
            auth = UUID.randomUUID();
            s.exec(
                    "INSERT INTO"
                        + " family_authorization(id,beneficiary_id,buyer_user_id,document_id,scope,receiver_name,receiver_identity)"
                        + " VALUES(?,?,?,?,'BUY_AND_RECEIVE',?,?)",
                    auth,
                    beneficiary,
                    actor.id(),
                    r.authorizationDocumentId(),
                    receiverName,
                    receiverIdentity);
            s.rememberAuthorizationDocument(auth, r.authorizationDocumentId());
        }
        boolean rx =
                required.keySet().stream()
                        .anyMatch(
                                p ->
                                        flag(
                                                s.row(
                                                                "SELECT requires_prescription FROM"
                                                                        + " product WHERE id=?",
                                                                p)
                                                        .get("requires_prescription")));
        UUID prescription = null;
        if (rx && r.prescriptionDocumentId() == null)
            throw AppException.badRequest(
                    "PRESCRIPTION_REQUIRED", "Esta cesta requiere una receta original.");
        if (r.prescriptionDocumentId() != null) {
            s.ownedDocument(r.prescriptionDocumentId(), "PRESCRIPTION");
            Map<String, Object> d =
                    s.row("SELECT * FROM document WHERE id=?", r.prescriptionDocumentId());
            List<Map<String, Object>> existing =
                    s.rows(
                            "SELECT p.* FROM prescription p JOIN document d ON d.id=p.document_id"
                                    + " WHERE d.sha256=? ORDER BY p.created_at LIMIT 1",
                            d.get("sha256"));
            if (!existing.isEmpty()) {
                Map<String, Object> p = existing.getFirst();
                if (Set.of("REJECTED", "EXPIRED", "CONSUMED").contains(text(p.get("status"))))
                    throw AppException.conflict(
                            "PRESCRIPTION_BLOCKED",
                            "Esta versión de la receta no es utilizable. Adjuntá un original"
                                    + " corregido y válido.");
                if (!Objects.equals(p.get("owner_user_id"), actor.id())
                        || !Objects.equals(p.get("beneficiary_id"), beneficiary))
                    throw AppException.conflict(
                            "PRESCRIPTION_ALREADY_LINKED",
                            "El original ya está asociado a otro beneficiario o titular.");
                prescription = id(p.get("id"));
            } else {
                prescription = UUID.randomUUID();
                s.exec(
                        "INSERT INTO prescription(id,owner_user_id,beneficiary_id,document_id)"
                                + " VALUES(?,?,?,?)",
                        prescription,
                        actor.id(),
                        beneficiary,
                        r.prescriptionDocumentId());
            }
        }
        UUID purchase = UUID.randomUUID();
        Map<String, Object> coverageRequest = null;
        if (!text(r.coverageProvider()).isBlank()
                || !text(r.coveragePlan()).isBlank()
                || !text(r.coverageMemberNumber()).isBlank()
                || r.coverageDocumentId() != null) {
            if (text(r.coverageProvider()).isBlank()
                    || text(r.coverageProvider()).length() > 160
                    || text(r.coveragePlan()).length() > 160
                    || text(r.coverageMemberNumber()).length() > 120
                    || (text(r.coverageMemberNumber()).isBlank() && r.coverageDocumentId() == null))
                throw AppException.badRequest(
                        "COVERAGE_REQUEST_INVALID",
                        "Indicá obra social o prepaga y número de afiliado o credencial.");
            if (r.coverageDocumentId() != null)
                s.ownedDocument(r.coverageDocumentId(), "HEALTH_COVERAGE");
            coverageRequest =
                    map(
                            "provider",
                            r.coverageProvider().trim(),
                            "plan",
                            text(r.coveragePlan()).trim(),
                            "memberNumber",
                            text(r.coverageMemberNumber()).trim(),
                            "evidenceDocumentId",
                            r.coverageDocumentId());
        }
        Map<String, Object> snapshot =
                map(
                        "config",
                        s.config.snapshot(),
                        "preferredBranchId",
                        r.preferredBranchId(),
                        "thirdParty",
                        r.thirdParty(),
                        "beneficiaryName",
                        r.beneficiaryName(),
                        "prescriptionDocumentId",
                        r.prescriptionDocumentId(),
                        "authorizationDocumentId",
                        r.authorizationDocumentId(),
                        "receiverName",
                        receiverName,
                        "receiverIdentity",
                        receiverIdentity,
                        "beneficiaryIdentity",
                        beneficiaryIdentity,
                        "coverageRequest",
                        coverageRequest,
                        "message",
                        "Buscando disponibilidad confirmable");
        s.exec(
                "INSERT INTO"
                    + " purchase(id,buyer_user_id,beneficiary_id,family_authorization_id,mode,delivery_address,latitude,longitude,status,currency,snapshot)"
                    + " VALUES(?,?,?,?,?,?,?,?,'ROUTING',?,cast(? as jsonb))",
                purchase,
                actor.id(),
                beneficiary,
                auth,
                r.mode(),
                text(r.destinationAddress()),
                r.destinationLat(),
                r.destinationLon(),
                cfg.currency(),
                s.json.encode(snapshot));
        for (var entry : required.entrySet()) {
            UUID rxItem = null;
            if (prescription != null
                    && flag(
                            s.row(
                                            "SELECT requires_prescription FROM product WHERE id=?",
                                            entry.getKey())
                                    .get("requires_prescription"))) {
                UUID newId = UUID.randomUUID();
                s.exec(
                        "INSERT INTO"
                            + " prescription_item(id,prescription_id,product_id,authorized_quantity)"
                            + " VALUES(?,?,?,?) ON CONFLICT(prescription_id,product_id) DO NOTHING",
                        newId,
                        prescription,
                        entry.getKey(),
                        entry.getValue());
                rxItem =
                        id(
                                s.row(
                                                "SELECT id FROM prescription_item WHERE"
                                                        + " prescription_id=? AND product_id=?",
                                                prescription,
                                                entry.getKey())
                                        .get("id"));
            }
            s.exec(
                    "INSERT INTO"
                        + " purchase_item(id,purchase_id,product_id,quantity,prescription_item_id)"
                        + " VALUES(?,?,?,?,?)",
                    UUID.randomUUID(),
                    purchase,
                    entry.getKey(),
                    entry.getValue(),
                    rxItem);
        }
        route(s.purchase(purchase, true));
        s.audit.record(
                actor,
                "PURCHASE_CREATED",
                "purchase",
                purchase,
                map(
                        "mode",
                        r.mode(),
                        "productCount",
                        required.size(),
                        "configVersion",
                        cfg.version()));
        return s.view(purchase);
    }

    private boolean capability(Map<String, Object> b, Map<String, Object> product) {
        if (flag(product.get("cold_chain")) && !flag(b.get("cold_chain_capable"))) return false;
        if (flag(product.get("restricted")) && !flag(b.get("restricted_capable"))) return false;
        if (flag(product.get("cold_chain")) || flag(product.get("restricted"))) {
            Map<String, Object> caps = s.json.readMap(text(b.get("capabilities")));
            if (caps.get("capabilityEvidenceDocumentId") == null
                    || caps.get("verifiedBy") == null
                    || caps.get("verifiedAt") == null) return false;
            if (s.rows(
                            "SELECT 1 FROM document WHERE id=? AND branch_id=? AND"
                                    + " kind='CAPABILITY'",
                            id(caps.get("capabilityEvidenceDocumentId")),
                            b.get("id"))
                    .isEmpty()) return false;
        }
        return true;
    }

    void route(Map<String, Object> p) {
        UUID purchase = id(p.get("id"));
        if ("REJECTED".equals(p.get("status")))
            throw AppException.conflict(
                    "CLINICAL_REJECTION",
                    "La solicitud fue rechazada por un motivo sanitario o de validez; necesita una"
                            + " receta corregida.");
        for (var old :
                s.rows(
                        "SELECT id FROM child_order WHERE purchase_id=? ORDER BY branch_id",
                        purchase))
            s.release(
                    id(old.get("id")),
                    s.access.actor(),
                    "Presupuesto reemplazado antes de aceptación");
        var cfg = s.config.current();
        List<Map<String, Object>> items =
                s.rows(
                        "SELECT pi.*,p.requires_prescription,p.cold_chain,p.restricted FROM"
                                + " purchase_item pi JOIN product p ON p.id=pi.product_id WHERE"
                                + " pi.purchase_id=? AND pi.active ORDER BY pi.product_id",
                        purchase);
        Map<UUID, Integer> required = new LinkedHashMap<>();
        items.forEach(
                i ->
                        required.put(
                                id(i.get("product_id")), ((Number) i.get("quantity")).intValue()));
        Map<String, Object> snap = s.snapshot(p);
        UUID preferred =
                snap.get("preferredBranchId") == null ? null : id(snap.get("preferredBranchId"));
        UUID preferredChain = null;
        if (preferred != null) {
            var bs = s.rows("SELECT chain_id FROM branch WHERE id=?", preferred);
            if (!bs.isEmpty() && bs.getFirst().get("chain_id") != null)
                preferredChain = id(bs.getFirst().get("chain_id"));
        }
        List<RoutingPlanner.Candidate> candidates = new ArrayList<>();
        String sql =
                """
                SELECT b.*,
                  EXISTS(SELECT 1 FROM branch_subscription bs JOIN subscription_contract sc ON sc.id=bs.contract_id
                    JOIN subscription_period sp ON sp.contract_id=sc.id WHERE bs.branch_id=b.id AND bs.active
                    AND bs.starts_at<=now() AND (bs.ends_at IS NULL OR bs.ends_at>now())
                    AND sc.status IN ('ACTIVE','CANCEL_AT_PERIOD_END') AND sp.status='PAID'
                    AND (sp.source='MANUAL' OR ?='DEMO') AND sp.starts_at<=now() AND sp.ends_at>now()) subscribed,
                  EXISTS(SELECT 1 FROM branch_subscription bs JOIN subscription_contract sc ON sc.id=bs.contract_id
                    WHERE bs.branch_id=b.id AND bs.active AND (sc.status='SUSPENDED' OR
                      (sc.status IN ('ACTIVE','CANCEL_AT_PERIOD_END') AND NOT EXISTS
                        (SELECT 1 FROM subscription_period sp WHERE sp.contract_id=sc.id AND sp.status='PAID' AND (sp.source='MANUAL' OR ?='DEMO') AND sp.ends_at>now() AND sp.starts_at<=now())
                       AND COALESCE((SELECT max(sp.ends_at) FROM subscription_period sp WHERE sp.contract_id=sc.id AND sp.status='PAID'),sc.starts_at)
                         + make_interval(days => ?)<now()))) suspended
                FROM branch b LEFT JOIN chain c ON c.id=b.chain_id
                WHERE b.active AND b.verified AND b.agreement_accepted AND (c.id IS NULL OR c.active)
                  AND NOT EXISTS(SELECT 1 FROM routing_attempt ra WHERE ra.purchase_id=? AND ra.branch_id=b.id AND ra.status='REJECTED')
                """;
        for (Map<String, Object> b : s.rows(sql, appMode, appMode, cfg.graceDays(), purchase)) {
            if (flag(b.get("suspended"))) continue;
            var branchCaps = s.json.readMap(text(b.get("capabilities")));
            if (!BranchAvailability.open(branchCaps, LocalTime.now(clock))) continue;
            double distance =
                    RoutingPlanner.distance(
                            amount(p.get("latitude")).doubleValue(),
                            amount(p.get("longitude")).doubleValue(),
                            amount(b.get("latitude")).doubleValue(),
                            amount(b.get("longitude")).doubleValue());
            if ("DELIVERY".equals(p.get("mode"))) {
                if (!BranchAvailability.withinRadius(branchCaps, distance)
                        || !flag(b.get("delivery_enabled"))
                        || !flag(b.get("driver_available"))
                        || s.rows(
                                        "SELECT 1 FROM membership m JOIN app_user u ON"
                                            + " u.id=m.user_id JOIN user_role ur ON"
                                            + " ur.user_id=m.user_id AND ur.role=m.role WHERE"
                                            + " m.branch_id=? AND m.role='DRIVER' AND m.active AND"
                                            + " u.active AND"
                                            + " coalesce(m.capabilities->>'available','true')<>'false'",
                                        b.get("id"))
                                .isEmpty()) continue;
            } else if (!flag(b.get("pickup_enabled"))) continue;
            Map<UUID, Integer> available = new LinkedHashMap<>();
            for (Map<String, Object> i : items) {
                if (!capability(b, i)) continue;
                var stock =
                        s.rows(
                                "SELECT quantity-reserved_quantity available FROM branch_stock"
                                        + " WHERE branch_id=? AND product_id=? AND active AND"
                                        + " currency=?",
                                b.get("id"),
                                i.get("product_id"),
                                p.get("currency"));
                if (!stock.isEmpty())
                    available.put(
                            id(i.get("product_id")),
                            ((Number) stock.getFirst().get("available")).intValue());
            }
            if ("DELIVERY".equals(p.get("mode"))) {
                boolean cold =
                        items.stream()
                                .anyMatch(
                                        i ->
                                                available.getOrDefault(id(i.get("product_id")), 0)
                                                                > 0
                                                        && flag(i.get("cold_chain")));
                boolean restricted =
                        items.stream()
                                .anyMatch(
                                        i ->
                                                available.getOrDefault(id(i.get("product_id")), 0)
                                                                > 0
                                                        && flag(i.get("restricted")));
                if ((cold || restricted)
                        && s.rows(
                                        "SELECT 1 FROM membership m JOIN app_user u ON"
                                            + " u.id=m.user_id JOIN user_role ur ON"
                                            + " ur.user_id=m.user_id AND ur.role=m.role JOIN"
                                            + " document d ON"
                                            + " d.id::text=m.capabilities->>'capabilityEvidenceDocumentId'"
                                            + " AND d.branch_id=m.branch_id AND d.kind='CAPABILITY'"
                                            + " WHERE m.branch_id=? AND m.role='DRIVER' AND"
                                            + " m.active AND u.active AND"
                                            + " coalesce(m.capabilities->>'available','true')<>'false'"
                                            + " AND (NOT ? OR m.capabilities->>'coldChain'='true')"
                                            + " AND (NOT ? OR m.capabilities->>'restricted'='true')"
                                            + " AND m.capabilities->>'verifiedBy' IS NOT NULL AND"
                                            + " m.capabilities->>'verifiedAt' IS NOT NULL",
                                        b.get("id"),
                                        cold,
                                        restricted)
                                .isEmpty()) continue;
            }
            candidates.add(
                    new RoutingPlanner.Candidate(
                            id(b.get("id")),
                            b.get("chain_id") == null ? null : id(b.get("chain_id")),
                            flag(b.get("subscribed")),
                            distance,
                            available));
        }
        var allocations =
                planner.plan(
                        required, candidates, preferred, preferredChain, cfg.fallbackEnabled());
        boolean indivisiblePrescription =
                !s.rows(
                                "SELECT 1 FROM purchase_item pi JOIN prescription_item ri ON"
                                        + " ri.id=pi.prescription_item_id JOIN prescription rx ON"
                                        + " rx.id=ri.prescription_id WHERE pi.purchase_id=? AND"
                                        + " rx.metadata->>'splitAllowed'='false'",
                                purchase)
                        .isEmpty();
        if (indivisiblePrescription && allocations.size() > 1) allocations = List.of();
        s.exec(
                "UPDATE quote SET status='SUPERSEDED' WHERE purchase_id=? AND status IN"
                        + " ('DRAFT','ISSUED')",
                purchase);
        s.exec(
                "UPDATE routing_attempt SET status='EXPIRED' WHERE purchase_id=? AND"
                        + " status='PENDING'",
                purchase);
        s.exec(
                "UPDATE child_order SET status='CANCELLED',version=version+1,updated_at=now() WHERE"
                        + " purchase_id=? AND status='PROPOSED'",
                purchase);
        if (allocations.isEmpty()) {
            snap.put("config", s.config.snapshot());
            snap.put(
                    "message",
                    cfg.messages()
                            .getOrDefault(
                                    "unavailable",
                                    "Por ahora no encontramos una farmacia que pueda completar esta"
                                            + " solicitud con stock y capacidad disponibles."));
            s.exec(
                    "UPDATE purchase SET status='UNAVAILABLE',snapshot=cast(? as"
                            + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                    s.json.encode(snap),
                    purchase);
            return;
        }
        int rank = 0;
        for (var allocation : allocations) {
            UUID branch = allocation.candidate().branchId();
            var old =
                    s.rows(
                            "SELECT * FROM child_order WHERE purchase_id=? AND branch_id=?",
                            purchase,
                            branch);
            UUID order = old.isEmpty() ? UUID.randomUUID() : id(old.getFirst().get("id"));
            if (old.isEmpty())
                s.exec(
                        "INSERT INTO child_order(id,purchase_id,branch_id,currency,snapshot)"
                                + " VALUES(?,?,?,?,cast(? as jsonb))",
                        order,
                        purchase,
                        branch,
                        p.get("currency"),
                        "{}");
            else {
                s.exec(
                        "UPDATE child_order SET"
                            + " status='PROPOSED',total=0,snapshot='{}'::jsonb,version=version+1"
                            + " WHERE id=?",
                        order);
                s.exec("UPDATE order_item SET active=false WHERE order_id=? AND active", order);
            }
            s.exec(
                    "UPDATE child_order SET snapshot=cast(? as jsonb) WHERE id=?",
                    s.json.encode(map("reviewEpoch", UUID.randomUUID())),
                    order);
            for (var q : allocation.quantities().entrySet()) {
                UUID purchaseItem =
                        id(
                                items.stream()
                                        .filter(i -> q.getKey().equals(i.get("product_id")))
                                        .findFirst()
                                        .orElseThrow()
                                        .get("id"));
                s.exec(
                        "INSERT INTO order_item(id,order_id,purchase_item_id,product_id,quantity)"
                                + " VALUES(?,?,?,?,?)",
                        UUID.randomUUID(),
                        order,
                        purchaseItem,
                        q.getKey(),
                        q.getValue());
            }
            s.exec(
                    "INSERT INTO"
                        + " routing_attempt(id,purchase_id,branch_id,partition_key,status,rank,snapshot,expires_at)"
                        + " VALUES(?,?,?,?,'PENDING',?,cast(? as jsonb),now()+make_interval(mins =>"
                        + " ?))",
                    UUID.randomUUID(),
                    purchase,
                    branch,
                    order.toString(),
                    rank++,
                    s.json.encode(
                            map(
                                    "subscribed",
                                    allocation.candidate().subscribed(),
                                    "distanceKm",
                                    allocation.candidate().distanceKm())),
                    cfg.quoteMinutes());
        }
        snap.put(
                "message",
                "Las farmacias están verificando disponibilidad y condiciones. Todavía no hay una"
                        + " compra aceptada.");
        s.exec(
                "UPDATE purchase SET status='ROUTING',total=0,snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                s.json.encode(snap),
                purchase);
    }

    @Transactional
    public Map<String, Object> review(UUID orderId, PurchaseDtos.Review r) {
        Map<String, Object> o = s.order(orderId, true);
        UUID branch = id(o.get("branch_id")), purchase = id(o.get("purchase_id"));
        s.pharmacy(branch, true);
        s.version(o, r.expectedVersion());
        s.requireNoPendingChange(purchase);
        if (!"PROPOSED".equals(o.get("status"))
                || !Set.of("ROUTING", "QUOTED")
                        .contains(text(s.purchase(purchase, false).get("status"))))
            throw AppException.conflict(
                    "REVIEW_STATE_INVALID", "Este pedido ya no admite una revisión de propuesta.");
        Actor actor = s.access.actor();
        Map<String, Object> p = s.purchase(purchase, false);
        String reviewEpoch = text(s.snapshot(o).get("reviewEpoch"));
        if (reviewEpoch.isBlank() || "QUOTED".equals(p.get("status")))
            reviewEpoch = UUID.randomUUID().toString();
        if ("QUOTED".equals(p.get("status"))) {
            for (var child :
                    s.rows(
                            "SELECT id FROM child_order WHERE purchase_id=? ORDER BY branch_id",
                            purchase))
                s.release(
                        id(child.get("id")), actor, "Revisión de presupuesto antes de aceptación");
            s.exec(
                    "UPDATE quote SET status='SUPERSEDED' WHERE purchase_id=? AND status='ISSUED'",
                    purchase);
            s.exec("UPDATE purchase SET status='ROUTING',version=version+1 WHERE id=?", purchase);
        }
        if ("REJECT".equals(r.decision())) {
            if (text(r.reason()).isBlank())
                throw AppException.badRequest("REASON_REQUIRED", "Indicá el motivo del rechazo.");
            String rejectionKind = text(r.rejectionKind()).isBlank() ? "STOCK" : r.rejectionKind();
            if (!Set.of(
                            "STOCK",
                            "CLINICAL",
                            "VALIDITY",
                            "COVERAGE",
                            "CAPACITY",
                            "OTHER",
                            "NON_SPLITTABLE")
                    .contains(rejectionKind))
                throw AppException.badRequest(
                        "REJECTION_KIND_INVALID",
                        "Elegí el motivo que determina cómo continuar la solicitud.");
            if (Set.of("CLINICAL", "VALIDITY").contains(rejectionKind)) {
                for (var rx :
                        s.rows(
                                "SELECT DISTINCT ri.prescription_id FROM purchase_item pi JOIN"
                                    + " prescription_item ri ON ri.id=pi.prescription_item_id WHERE"
                                    + " pi.purchase_id=?",
                                purchase)) {
                    s.exec(
                            "UPDATE prescription SET"
                                + " status='REJECTED',provider_verification='REJECTED',metadata=metadata"
                                + " || cast(? as jsonb) WHERE id=?",
                            s.json.encode(
                                    map(
                                            "rejectedBy",
                                            actor.id(),
                                            "rejectedAt",
                                            Instant.now().toString(),
                                            "rejectionKind",
                                            rejectionKind,
                                            "reason",
                                            r.reason())),
                            rx.get("prescription_id"));
                }
                for (var child :
                        s.rows(
                                "SELECT id FROM child_order WHERE purchase_id=? ORDER BY branch_id",
                                purchase))
                    s.release(
                            id(child.get("id")),
                            actor,
                            "Rechazo sanitario de la versión de receta");
                s.exec(
                        "UPDATE quote SET status='REJECTED' WHERE purchase_id=? AND"
                                + " status='ISSUED'",
                        purchase);
                s.exec(
                        "UPDATE routing_attempt SET status='REJECTED',reason=? WHERE purchase_id=?"
                                + " AND status='PENDING'",
                        r.reason(),
                        purchase);
                s.exec(
                        "UPDATE child_order SET status='CANCELLED',version=version+1 WHERE"
                                + " purchase_id=? AND status='PROPOSED'",
                        purchase);
                var rejection = s.snapshot(p);
                rejection.put(
                        "message",
                        "La farmacia rechazó esta solicitud por un motivo sanitario o de validez: "
                                + r.reason());
                rejection.put("rejectionKind", rejectionKind);
                s.exec(
                        "UPDATE purchase SET status='REJECTED',snapshot=cast(? as"
                                + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                        s.json.encode(rejection),
                        purchase);
                s.audit.record(
                        actor,
                        "PRESCRIPTION_VERSION_REJECTED",
                        "purchase",
                        purchase,
                        map("reason", r.reason(), "kind", rejectionKind));
                return s.view(purchase);
            }
            if ("NON_SPLITTABLE".equals(rejectionKind))
                s.exec(
                        "UPDATE prescription SET metadata=metadata ||"
                            + " '{\"splitAllowed\":false}'::jsonb WHERE id IN (SELECT"
                            + " ri.prescription_id FROM purchase_item pi JOIN prescription_item ri"
                            + " ON ri.id=pi.prescription_item_id WHERE pi.purchase_id=?)",
                        purchase);
            s.exec(
                    "UPDATE routing_attempt SET status='REJECTED',reason=? WHERE purchase_id=? AND"
                            + " branch_id=? AND status='PENDING'",
                    r.reason(),
                    purchase,
                    branch);
            route(p);
            s.audit.record(
                    actor,
                    "ORDER_REJECTED_REROUTED",
                    "child_order",
                    orderId,
                    map("reason", r.reason()));
            return s.view(purchase);
        }
        if (!"APPROVE".equals(r.decision()) || text(r.reason()).isBlank())
            throw AppException.badRequest(
                    "REVIEW_INVALID", "Indicá aprobación y evidencia de la revisión manual.");
        if (!r.authorizationChecked())
            throw AppException.badRequest(
                    "IDENTITY_REVIEW_REQUIRED",
                    "Comprobá la correspondencia de los datos del beneficiario y, si corresponde,"
                            + " la nota firmada de esta compra.");
        var pending =
                s.rows(
                        "SELECT 1 FROM routing_attempt WHERE purchase_id=? AND branch_id=? AND"
                                + " status IN ('PENDING','ACCEPTED') AND expires_at>now()",
                        purchase,
                        branch);
        if (pending.isEmpty())
            throw AppException.conflict(
                    "ROUTING_EXPIRED",
                    "La solicitud a esta farmacia venció. Se requiere una nueva búsqueda.");
        if (p.get("family_authorization_id") != null) {
            if (!r.authorizationChecked())
                throw AppException.badRequest(
                        "AUTHORIZATION_REVIEW_REQUIRED",
                        "Verificá manualmente la nota firmada y la identidad adulta.");
            s.exec(
                    "UPDATE family_authorization SET"
                        + " status='APPROVED',reviewed_by=?,reviewed_at=now(),reason=? WHERE id=?"
                        + " AND status IN ('PENDING','APPROVED')",
                    actor.id(),
                    r.reason(),
                    p.get("family_authorization_id"));
            s.exec(
                    "INSERT INTO"
                        + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id)"
                        + " SELECT ?,?,?,'FAMILY_AUTHORIZATION','APPROVED',?,?,document_id FROM"
                        + " family_authorization WHERE id=?",
                    UUID.randomUUID(),
                    orderId,
                    branch,
                    actor.id(),
                    r.reason(),
                    p.get("family_authorization_id"));
        }
        s.exec(
                "UPDATE beneficiary SET age_verified=true,verified_by=?,verified_at=now() WHERE"
                        + " id=?",
                actor.id(),
                p.get("beneficiary_id"));
        Map<UUID, PurchaseDtos.ReviewLine> supplied = new HashMap<>();
        if (r.lines() != null)
            for (var line : r.lines())
                if (line.productId() == null
                        || supplied.putIfAbsent(line.productId(), line) != null)
                    throw AppException.badRequest(
                            "REVIEW_LINES_INVALID", "Cada producto debe revisarse una sola vez.");
        BigDecimal total = BigDecimal.ZERO, subtotal = BigDecimal.ZERO;
        Map<String, Object> b = s.row("SELECT * FROM branch WHERE id=?", branch);
        if (r.deliveryMinutes() != null && (r.deliveryMinutes() < 1 || r.deliveryMinutes() > 1440))
            throw AppException.badRequest(
                    "DELIVERY_TIME_INVALID",
                    "El plazo informado debe estar entre 1 y 1440 minutos.");
        var conditions = branchConditions(b);
        boolean divided =
                num(
                                s.row(
                                                "SELECT count(*) count FROM child_order WHERE"
                                                        + " purchase_id=? AND status='PROPOSED'",
                                                purchase)
                                        .get("count"))
                        > 1;
        Set<UUID> prescriptions = new HashSet<>();
        for (var item : s.items(orderId)) {
            UUID product = id(item.get("product_id"));
            if (!capability(b, item))
                throw AppException.conflict(
                        "CAPABILITY_EVIDENCE_REQUIRED",
                        "La sucursal no tiene una capacidad especial verificada y documentada para"
                                + " este producto.");
            if (flag(item.get("cold_chain")) || flag(item.get("restricted")))
                s.evidence(r.conditionEvidenceDocumentId(), branch, "CAPABILITY", "OTHER");
            Map<String, Object> stock =
                    s.row(
                            "SELECT * FROM branch_stock WHERE branch_id=? AND product_id=? AND"
                                    + " active",
                            branch,
                            product);
            if (!Objects.equals(text(stock.get("currency")), text(p.get("currency"))))
                throw AppException.conflict(
                        "CURRENCY_CHANGED",
                        "La oferta cambió de moneda; se requiere un presupuesto nuevo.");
            if (num(stock.get("quantity")) - num(stock.get("reserved_quantity"))
                    < num(item.get("quantity")))
                throw AppException.conflict(
                        "STOCK_UNAVAILABLE",
                        "El stock informado ya no alcanza; rechazá la propuesta para buscar otra"
                                + " farmacia.");
            var line = supplied.get(product);
            BigDecimal coverage = line == null ? BigDecimal.ZERO : amount(line.coverageAmount());
            BigDecimal gross = amount(stock.get("price")).multiply(amount(item.get("quantity")));
            if (coverage.signum() < 0 || coverage.scale() > 2 || coverage.compareTo(gross) > 0)
                throw AppException.badRequest(
                        "COVERAGE_AMOUNT_INVALID",
                        "La cobertura no puede superar el importe ni ser negativa.");
            if (coverage.signum() > 0) {
                if (text(line.coverageReference()).isBlank())
                    throw AppException.badRequest(
                            "COVERAGE_REFERENCE_REQUIRED",
                            "Registrá la autorización real de cobertura.");
                s.coverageEvidence(line.coverageEvidenceDocumentId(), branch, orderId);
                s.exec(
                        "INSERT INTO"
                            + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id,snapshot)"
                            + " VALUES(?,?,?,'COVERAGE','APPROVED',?,?,?,cast(? as jsonb))",
                        UUID.randomUUID(),
                        orderId,
                        branch,
                        actor.id(),
                        line.coverageReference(),
                        line.coverageEvidenceDocumentId(),
                        s.json.encode(
                                map("productId", product, "amount", coverage.toPlainString())));
            }
            if (flag(item.get("requires_prescription"))) {
                if (divided && !Boolean.TRUE.equals(r.prescriptionSplittable()))
                    throw AppException.badRequest(
                            "PRESCRIPTION_SPLIT_REVIEW_REQUIRED",
                            "La compra está dividida. Confirmá profesionalmente que esta receta"
                                    + " admite la dispensa distribuida o rechazá por receta"
                                    + " indivisible.");
                Map<String, Object> pi =
                        s.row(
                                "SELECT ri.*,rx.status prescription_status FROM purchase_item pi"
                                    + " JOIN prescription_item ri ON ri.id=pi.prescription_item_id"
                                    + " JOIN prescription rx ON rx.id=ri.prescription_id WHERE"
                                    + " pi.id=? FOR UPDATE OF ri,rx",
                                item.get("purchase_item_id"));
                if (!r.originVerified()
                        || text(r.prescriptionReference()).isBlank()
                        || text(r.prescriptionIssuer()).isBlank()
                        || r.prescriptionExpiresAt() == null
                        || !r.prescriptionExpiresAt().toInstant().isAfter(Instant.now())
                        || line == null
                        || line.authorizedQuantity() == null
                        || line.authorizedQuantity() < num(item.get("quantity")))
                    throw AppException.badRequest(
                            "PRESCRIPTION_VERIFICATION_REQUIRED",
                            "Verificá origen autorizado, referencia, emisor, vigencia y cantidad"
                                    + " autorizada de cada producto.");
                if (Set.of("REJECTED", "EXPIRED", "CONSUMED")
                        .contains(text(pi.get("prescription_status"))))
                    throw AppException.conflict(
                            "PRESCRIPTION_NOT_USABLE",
                            "La receta no tiene saldo o vigencia utilizable.");
                if (line.authorizedQuantity()
                        < num(pi.get("reserved_quantity")) + num(pi.get("dispensed_quantity")))
                    throw AppException.conflict(
                            "PRESCRIPTION_BALANCE_CONFLICT",
                            "La cantidad revisada no puede quedar por debajo del uso y las reservas"
                                    + " existentes.");
                s.exec(
                        "UPDATE prescription_item SET authorized_quantity=? WHERE id=?",
                        line.authorizedQuantity(),
                        pi.get("id"));
                s.exec(
                        "UPDATE prescription SET"
                            + " status='APPROVED',provider_verification='MANUALLY_VERIFIED',external_reference=?,issuer=?,expires_at=?"
                            + " WHERE id=?",
                        r.prescriptionReference(),
                        r.prescriptionIssuer(),
                        r.prescriptionExpiresAt(),
                        pi.get("prescription_id"));
                if (divided)
                    s.exec(
                            "UPDATE prescription SET metadata=metadata ||"
                                    + " '{\"splitAllowed\":true}'::jsonb WHERE id=?",
                            pi.get("prescription_id"));
                prescriptions.add(id(pi.get("prescription_id")));
            }
            Map<String, Object> lineSnapshot =
                    map(
                            "name",
                            item.get("name"),
                            "presentation",
                            item.get("presentation"),
                            "unitPrice",
                            amount(stock.get("price")).toPlainString(),
                            "stockVersion",
                            stock.get("version"),
                            "coverageAmount",
                            coverage.toPlainString(),
                            "coverageReference",
                            line == null ? null : line.coverageReference(),
                            "coverageEvidenceDocumentId",
                            line == null ? null : line.coverageEvidenceDocumentId(),
                            "reviewedBy",
                            actor.id(),
                            "reviewedAt",
                            Instant.now().toString(),
                            "requiresPrescription",
                            item.get("requires_prescription"),
                            "coldChain",
                            item.get("cold_chain"),
                            "restricted",
                            item.get("restricted"),
                            "productMetadata",
                            s.json.readMap(text(item.get("metadata"))));
            s.exec(
                    "UPDATE order_item SET snapshot=cast(? as jsonb) WHERE id=?",
                    s.json.encode(lineSnapshot),
                    item.get("id"));
            subtotal = subtotal.add(gross);
            total = total.add(gross.subtract(coverage));
        }
        for (UUID rx : prescriptions)
            s.exec(
                    "INSERT INTO"
                        + " review(id,order_id,branch_id,prescription_id,kind,status,reviewed_by,reason)"
                        + " VALUES(?,?,?,?,'PRESCRIPTION','APPROVED',?,?)",
                    UUID.randomUUID(),
                    orderId,
                    branch,
                    rx,
                    actor.id(),
                    r.reason());
        boolean high = subtotal.compareTo(s.config.current().highValueAmount()) >= 0;
        String fingerprint =
                IdempotencyService.hash(
                        s.json.encode(
                                map(
                                        "reviewEpoch",
                                        reviewEpoch,
                                        "branchConditions",
                                        conditions,
                                        "deliveryMinutes",
                                        r.deliveryMinutes() == null
                                                ? conditions.get("deliveryMinutes")
                                                : r.deliveryMinutes(),
                                        "authorizationDocumentId",
                                        s.snapshot(p).get("authorizationDocumentId"),
                                        "prescriptionReference",
                                        r.prescriptionReference(),
                                        "prescriptionIssuer",
                                        r.prescriptionIssuer(),
                                        "prescriptionExpiresAt",
                                        r.prescriptionExpiresAt(),
                                        "lines",
                                        s.items(orderId).stream()
                                                .map(
                                                        i ->
                                                                map(
                                                                        "product",
                                                                        i.get("product_id"),
                                                                        "quantity",
                                                                        i.get("quantity"),
                                                                        "unitPrice",
                                                                        s.snapshot(i)
                                                                                .get("unitPrice"),
                                                                        "coverageAmount",
                                                                        s.snapshot(i)
                                                                                .get(
                                                                                        "coverageAmount"),
                                                                        "coverageReference",
                                                                        s.snapshot(i)
                                                                                .get(
                                                                                        "coverageReference"),
                                                                        "coverageEvidenceDocumentId",
                                                                        s.snapshot(i)
                                                                                .get(
                                                                                        "coverageEvidenceDocumentId")))
                                                .toList())));
        s.exec(
                "INSERT INTO"
                    + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id,snapshot)"
                    + " VALUES(?,?,?,?,'APPROVED',?,?,?,cast(? as jsonb))",
                UUID.randomUUID(),
                orderId,
                branch,
                high ? "HIGH_VALUE" : "AVAILABILITY",
                actor.id(),
                r.reason(),
                r.conditionEvidenceDocumentId(),
                s.json.encode(map("fingerprint", fingerprint)));
        long reviewCount =
                num(
                        s.row(
                                        "SELECT count(DISTINCT r.reviewed_by) count FROM review r"
                                            + " JOIN app_user u ON u.id=r.reviewed_by AND u.active"
                                            + " AND u.email_verified JOIN user_role ur ON"
                                            + " ur.user_id=u.id AND ur.role='PHARMACIST' JOIN"
                                            + " membership m ON m.user_id=u.id AND"
                                            + " m.branch_id=r.branch_id AND m.role='PHARMACIST' AND"
                                            + " m.active WHERE r.order_id=? AND r.kind=? AND"
                                            + " r.status='APPROVED' AND"
                                            + " r.snapshot->>'fingerprint'=?",
                                        orderId,
                                        high ? "HIGH_VALUE" : "AVAILABILITY",
                                        fingerprint)
                                .get("count"));
        Map<String, Object> os =
                map(
                        "reviewEpoch",
                        reviewEpoch,
                        "reviewApproved",
                        !high || reviewCount >= 2,
                        "reviewCount",
                        reviewCount,
                        "highValueRequired",
                        high,
                        "fingerprint",
                        fingerprint,
                        "conditionEvidenceDocumentId",
                        r.conditionEvidenceDocumentId(),
                        "branchConditions",
                        conditions,
                        "deliveryMinutes",
                        r.deliveryMinutes() == null
                                ? conditions.get("deliveryMinutes")
                                : r.deliveryMinutes());
        s.exec(
                "UPDATE child_order SET total=?,snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                total,
                s.json.encode(os),
                orderId);
        s.exec(
                "UPDATE routing_attempt SET status='ACCEPTED' WHERE purchase_id=? AND branch_id=?"
                        + " AND status='PENDING'",
                purchase,
                branch);
        issueQuote(purchase);
        s.audit.record(
                actor,
                "ORDER_REVIEWED",
                "child_order",
                orderId,
                map("highValue", high, "reviewCount", reviewCount));
        return s.view(purchase);
    }

    private void issueQuote(UUID purchase) {
        var orders =
                s.rows(
                        "SELECT * FROM child_order WHERE purchase_id=? AND status='PROPOSED' ORDER"
                                + " BY branch_id",
                        purchase);
        if (orders.isEmpty()
                || orders.stream()
                        .anyMatch(
                                o ->
                                        !flag(s.snapshot(o).get("reviewApproved"))
                                                || s.quoteReviewCount(o)
                                                        < (flag(
                                                                        s.snapshot(o)
                                                                                .get(
                                                                                        "highValueRequired"))
                                                                ? 2
                                                                : 1))) return;
        for (var order : orders)
            s.release(id(order.get("id")), s.access.actor(), "Nueva versión de presupuesto");
        s.exec(
                "UPDATE quote SET status='SUPERSEDED' WHERE purchase_id=? AND status IN"
                        + " ('DRAFT','ISSUED')",
                purchase);
        int version =
                ((Number)
                                s.row(
                                                "SELECT coalesce(max(version),0)+1 version FROM"
                                                        + " quote WHERE purchase_id=?",
                                                purchase)
                                        .get("version"))
                        .intValue();
        UUID quote = UUID.randomUUID();
        BigDecimal subtotal = BigDecimal.ZERO, coverage = BigDecimal.ZERO;
        for (var o : orders)
            for (var i : s.items(id(o.get("id")))) {
                var snap = s.snapshot(i);
                subtotal =
                        subtotal.add(
                                amount(snap.get("unitPrice")).multiply(amount(i.get("quantity"))));
                coverage = coverage.add(amount(snap.get("coverageAmount")));
            }
        s.exec(
                "INSERT INTO"
                    + " quote(id,purchase_id,version,status,subtotal,coverage_amount,total,currency,expires_at,snapshot)"
                    + " VALUES(?,?,?,'ISSUED',?,?,?,?,now()+make_interval(mins => ?),cast(? as"
                    + " jsonb))",
                quote,
                purchase,
                version,
                subtotal,
                coverage,
                subtotal.subtract(coverage),
                s.purchase(purchase, false).get("currency"),
                s.config.current().quoteMinutes(),
                s.json.encode(
                        map(
                                "config",
                                s.config.snapshot(),
                                "branches",
                                orders.stream().map(o -> o.get("branch_id")).toList())));
        for (var o : orders)
            for (var i : s.items(id(o.get("id")))) {
                var snap = s.snapshot(i);
                s.exec(
                        "INSERT INTO"
                            + " quote_line(id,quote_id,order_id,purchase_item_id,product_id,quantity,unit_price,coverage_amount,total,snapshot)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,cast(? as jsonb))",
                        UUID.randomUUID(),
                        quote,
                        o.get("id"),
                        i.get("purchase_item_id"),
                        i.get("product_id"),
                        i.get("quantity"),
                        amount(snap.get("unitPrice")),
                        amount(snap.get("coverageAmount")),
                        amount(snap.get("unitPrice"))
                                .multiply(amount(i.get("quantity")))
                                .subtract(amount(snap.get("coverageAmount"))),
                        s.json.encode(snap));
            }
        reserveQuote(s.purchase(purchase, false), s.row("SELECT * FROM quote WHERE id=?", quote));
        Map<String, Object> ps = s.snapshot(s.purchase(purchase, false));
        ps.put("config", s.config.snapshot());
        ps.put(
                "message",
                s.config
                        .current()
                        .messages()
                        .getOrDefault(
                                "quoteConsent",
                                "Presupuesto completo disponible. Revisá cada farmacia y aceptá"
                                        + " expresamente."));
        s.exec(
                "UPDATE purchase SET status='QUOTED',total=?,snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                subtotal.subtract(coverage),
                s.json.encode(ps),
                purchase);
    }

    private void reserveQuote(Map<String, Object> p, Map<String, Object> q) {
        Actor actor = s.access.actor();
        for (var l :
                s.rows(
                        "SELECT ql.*,oi.id item_id,pi.prescription_item_id FROM quote_line ql JOIN"
                            + " order_item oi ON oi.order_id=ql.order_id AND"
                            + " oi.product_id=ql.product_id AND oi.active JOIN purchase_item pi ON"
                            + " pi.id=ql.purchase_item_id WHERE ql.quote_id=? ORDER BY"
                            + " ql.product_id,ql.order_id",
                        q.get("id"))) {
            Map<String, Object> o = s.order(id(l.get("order_id")), false);
            UUID branch = id(o.get("branch_id"));
            Map<String, Object> b = s.row("SELECT * FROM branch WHERE id=?", branch);
            if (!flag(b.get("active"))
                    || !flag(b.get("verified"))
                    || !flag(b.get("agreement_accepted")))
                throw AppException.conflict(
                        "BRANCH_NO_LONGER_ELIGIBLE",
                        "La farmacia cambió sus condiciones; se requiere un presupuesto nuevo.");
            if (!Objects.equals(s.snapshot(o).get("branchConditions"), branchConditions(b))
                    || !BranchAvailability.open(
                            s.json.readMap(text(b.get("capabilities"))), LocalTime.now(clock)))
                throw AppException.conflict(
                        "BRANCH_CONDITIONS_CHANGED",
                        "La farmacia cambió sus datos o su horario operativo; se requiere revisar y"
                                + " aceptar un presupuesto nuevo.");
            var stock =
                    s.row(
                            "SELECT * FROM branch_stock WHERE branch_id=? AND product_id=? FOR"
                                    + " UPDATE",
                            branch,
                            l.get("product_id"));
            if (!flag(stock.get("active"))
                    || amount(stock.get("price")).compareTo(amount(l.get("unit_price"))) != 0
                    || !Objects.equals(text(stock.get("currency")), text(p.get("currency"))))
                throw AppException.conflict(
                        "QUOTE_CHANGED",
                        "El precio o la disponibilidad cambió; la farmacia debe reemitir el"
                                + " presupuesto.");
            int quantity = ((Number) l.get("quantity")).intValue();
            if (num(stock.get("quantity")) - num(stock.get("reserved_quantity")) < quantity)
                throw AppException.conflict(
                        "STOCK_CONFLICT",
                        "Otra compra tomó el stock; se requiere una propuesta nueva.");
            if (l.get("prescription_item_id") != null) {
                Map<String, Object> rx =
                        s.row(
                                "SELECT ri.*,rx.status"
                                    + " rx_status,rx.provider_verification,rx.expires_at FROM"
                                    + " prescription_item ri JOIN prescription rx ON"
                                    + " rx.id=ri.prescription_id WHERE ri.id=? FOR UPDATE OF ri,rx",
                                l.get("prescription_item_id"));
                if (!"APPROVED".equals(rx.get("rx_status"))
                        || "UNKNOWN".equals(rx.get("provider_verification"))
                        || rx.get("expires_at") == null
                        || !instant(rx.get("expires_at")).isAfter(Instant.now())
                        || num(rx.get("authorized_quantity"))
                                        - num(rx.get("reserved_quantity"))
                                        - num(rx.get("dispensed_quantity"))
                                < quantity)
                    throw AppException.conflict(
                            "PRESCRIPTION_BALANCE_INVALID",
                            "La receta no tiene saldo o verificación vigente para esta compra.");
                s.exec(
                        "UPDATE prescription_item SET reserved_quantity=reserved_quantity+? WHERE"
                                + " id=?",
                        quantity,
                        l.get("prescription_item_id"));
            }
            s.exec(
                    "UPDATE branch_stock SET"
                        + " reserved_quantity=reserved_quantity+?,version=version+1,updated_at=now()"
                        + " WHERE branch_id=? AND product_id=?",
                    quantity,
                    branch,
                    l.get("product_id"));
            UUID reservation = UUID.randomUUID();
            var lineSnapshot = s.snapshot(l);
            boolean special =
                    flag(lineSnapshot.get("coldChain")) || flag(lineSnapshot.get("restricted"));
            UUID lot = null;
            var lots =
                    s.rows(
                            "SELECT * FROM stock_lot WHERE branch_id=? AND product_id=? AND"
                                    + " status='AVAILABLE' AND (expires_on IS NULL OR"
                                    + " expires_on>=current_date) AND quantity-reserved_quantity>=?"
                                    + " ORDER BY expires_on NULLS LAST,lot_code LIMIT 1 FOR UPDATE",
                            branch,
                            l.get("product_id"),
                            quantity);
            if (!lots.isEmpty()) {
                var chosen = lots.getFirst();
                if (special && chosen.get("expires_on") == null)
                    throw AppException.conflict(
                            "LOT_EXPIRY_REQUIRED",
                            "El lote especial necesita vencimiento verificado.");
                lot = id(chosen.get("id"));
                s.exec(
                        "UPDATE stock_lot SET"
                            + " reserved_quantity=reserved_quantity+?,version=version+1 WHERE id=?",
                        quantity,
                        lot);
            } else if (special
                    || !s.rows(
                                    "SELECT 1 FROM stock_lot WHERE branch_id=? AND product_id=?",
                                    branch,
                                    l.get("product_id"))
                            .isEmpty())
                throw AppException.conflict(
                        "LOT_UNAVAILABLE",
                        "No hay un lote vigente con cantidad suficiente para esta línea; confirmá"
                                + " lotes y reemití la propuesta.");
            s.exec(
                    "INSERT INTO"
                        + " reservation(id,order_id,order_item_id,branch_id,product_id,lot_id,prescription_item_id,quantity,expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    reservation,
                    l.get("order_id"),
                    l.get("item_id"),
                    branch,
                    l.get("product_id"),
                    lot,
                    l.get("prescription_item_id"),
                    quantity,
                    q.get("expires_at"));
            s.exec(
                    "UPDATE order_item SET quote_line_id=? WHERE id=?",
                    l.get("id"),
                    l.get("item_id"));
            s.exec(
                    "INSERT INTO"
                        + " stock_movement(id,branch_id,product_id,reservation_id,actor_user_id,kind,quantity_delta,reserved_delta,reason)"
                        + " VALUES(?,?,?,?,?,'RESERVE',0,?,'Reserva de presupuesto emitido')",
                    UUID.randomUUID(),
                    branch,
                    l.get("product_id"),
                    reservation,
                    actor.id(),
                    quantity);
        }
    }

    private void validateReservedQuote(Map<String, Object> p, Map<String, Object> q) {
        var lines =
                s.rows(
                        "SELECT ql.*,oi.id item_id FROM quote_line ql JOIN order_item oi ON"
                            + " oi.quote_line_id=ql.id AND oi.active WHERE ql.quote_id=? ORDER BY"
                            + " ql.product_id,ql.order_id",
                        q.get("id"));
        if (lines.isEmpty()
                || lines.size()
                        != num(
                                s.row(
                                                "SELECT count(*) count FROM quote_line WHERE"
                                                        + " quote_id=?",
                                                q.get("id"))
                                        .get("count")))
            throw AppException.conflict(
                    "QUOTE_INCOMPLETE",
                    "El presupuesto dejó de contener todas sus líneas vigentes; solicitá una nueva"
                            + " propuesta.");
        for (var line : lines) {
            var order = s.order(id(line.get("order_id")), false);
            if (s.quoteReviewCount(order)
                    < (flag(s.snapshot(order).get("highValueRequired")) ? 2 : 1))
                throw AppException.conflict(
                        "QUOTE_REVIEW_REVOKED",
                        "La propuesta perdió una revisión profesional vigente. La farmacia debe"
                                + " revisarla nuevamente antes de aceptar.");
            var branch = s.row("SELECT * FROM branch WHERE id=?", order.get("branch_id"));
            if (!flag(branch.get("active"))
                    || !flag(branch.get("verified"))
                    || !flag(branch.get("agreement_accepted")))
                throw AppException.conflict(
                        "BRANCH_NO_LONGER_ELIGIBLE",
                        "La farmacia dejó de estar habilitada para esta propuesta.");
            if (!Objects.equals(
                    s.snapshot(order).get("branchConditions"), branchConditions(branch)))
                throw AppException.conflict(
                        "BRANCH_CONDITIONS_CHANGED",
                        "Las condiciones de la farmacia cambiaron; solicitá un presupuesto nuevo.");
            if (!BranchAvailability.open(
                    s.json.readMap(text(branch.get("capabilities"))), LocalTime.now(clock)))
                throw AppException.conflict(
                        "BRANCH_CLOSED",
                        "La sucursal cerró antes de la aceptación. Solicitá una propuesta"
                                + " disponible en su horario operativo.");
            var stock =
                    s.row(
                            "SELECT * FROM branch_stock WHERE branch_id=? AND product_id=? FOR"
                                    + " UPDATE",
                            branch.get("id"),
                            line.get("product_id"));
            if (!flag(stock.get("active"))
                    || amount(stock.get("price")).compareTo(amount(line.get("unit_price"))) != 0
                    || !Objects.equals(stock.get("currency"), p.get("currency")))
                throw AppException.conflict(
                        "QUOTE_CHANGED",
                        "El precio o la disponibilidad cambió; la farmacia debe emitir un"
                                + " presupuesto nuevo.");
            var held =
                    s.rows(
                            "SELECT"
                                + " r.*,ri.authorized_quantity,ri.reserved_quantity,ri.dispensed_quantity"
                                + " rx_dispensed,rx.status rx_status,rx.expires_at rx_expires FROM"
                                + " reservation r LEFT JOIN prescription_item ri ON"
                                + " ri.id=r.prescription_item_id LEFT JOIN prescription rx ON"
                                + " rx.id=ri.prescription_id WHERE r.order_item_id=? AND"
                                + " r.status='HELD' AND r.expires_at>now() FOR UPDATE OF r",
                            line.get("item_id"));
            if (held.size() != 1
                    || num(held.getFirst().get("quantity")) != num(line.get("quantity")))
                throw AppException.conflict(
                        "RESERVATION_EXPIRED",
                        "La reserva de este presupuesto venció. Solicitá una nueva propuesta.");
            var reservation = held.getFirst();
            if (reservation.get("prescription_item_id") != null
                    && (!"APPROVED".equals(reservation.get("rx_status"))
                            || reservation.get("rx_expires") == null
                            || !instant(reservation.get("rx_expires")).isAfter(Instant.now())
                            || num(reservation.get("authorized_quantity"))
                                    < num(reservation.get("reserved_quantity"))
                                            + num(reservation.get("rx_dispensed"))))
                throw AppException.conflict(
                        "PRESCRIPTION_BALANCE_INVALID",
                        "La receta perdió vigencia o saldo; se requiere revisión farmacéutica.");
        }
    }

    @Transactional
    public boolean expireQuote(UUID purchase) {
        var p = s.purchase(purchase, true);
        if (!"QUOTED".equals(p.get("status"))
                || s.rows(
                                "SELECT 1 FROM quote WHERE purchase_id=? AND status='ISSUED' AND"
                                        + " expires_at<=now()",
                                purchase)
                        .isEmpty()) return false;
        for (var order :
                s.rows(
                        "SELECT id FROM child_order WHERE purchase_id=? ORDER BY branch_id",
                        purchase))
            s.release(id(order.get("id")), null, "Vencimiento de presupuesto no aceptado");
        s.exec(
                "UPDATE quote SET status='EXPIRED' WHERE purchase_id=? AND status='ISSUED'",
                purchase);
        var snap = s.snapshot(p);
        snap.put(
                "message",
                "El presupuesto venció y liberamos el stock. Solicitá una nueva búsqueda para"
                        + " recibir otra propuesta.");
        s.exec(
                "UPDATE purchase SET status='UNAVAILABLE',snapshot=cast(? as"
                        + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                s.json.encode(snap),
                purchase);
        s.audit.record(null, "SYSTEM_QUOTE_EXPIRY", "purchase", purchase, Map.of());
        return true;
    }

    @Transactional
    public Map<String, Object> reviewAuthorization(
            UUID order, PurchaseDtos.AuthorizationReview input) {
        var child = s.order(order, true);
        UUID purchase = id(child.get("purchase_id"));
        s.pharmacy(id(child.get("branch_id")), true);
        s.version(child, input.expectedVersion());
        if (!Set.of("APPROVE", "REJECT", "REQUEST_INFO").contains(text(input.decision()))
                || text(input.reason()).isBlank())
            throw AppException.badRequest(
                    "AUTHORIZATION_REVIEW_INVALID",
                    "Elegí una decisión y registrá el motivo de la revisión de la nota.");
        var p = s.purchase(purchase, false);
        if (p.get("family_authorization_id") == null)
            throw AppException.conflict(
                    "AUTHORIZATION_NOT_REQUIRED",
                    "Esta compra no tiene una autorización de tercero.");
        if ("APPROVE".equals(input.decision())
                && Set.of("REJECTED", "REVOKED", "EXPIRED")
                        .contains(
                                text(
                                        s.row(
                                                        "SELECT status FROM family_authorization"
                                                                + " WHERE id=?",
                                                        p.get("family_authorization_id"))
                                                .get("status"))))
            throw AppException.conflict(
                    "NEW_AUTHORIZATION_REQUIRED",
                    "La nota fue rechazada, revocada o venció. El comprador debe presentar una nota"
                            + " nueva antes de aprobar.");
        if (Set.of("COMPLETED", "CANCELLED", "RESOLVED", "REJECTED")
                .contains(text(p.get("status"))))
            throw AppException.conflict("PURCHASE_CLOSED", "La compra ya fue cerrada.");
        String status =
                switch (input.decision()) {
                    case "APPROVE" -> "APPROVED";
                    case "REJECT" -> "REJECTED";
                    default -> "PENDING";
                };
        var actor = s.access.actor();
        s.exec(
                "UPDATE family_authorization SET status=?,reviewed_by=?,reviewed_at=now(),reason=?"
                        + " WHERE id=?",
                status,
                actor.id(),
                input.reason(),
                p.get("family_authorization_id"));
        s.exec("UPDATE child_order SET version=version+1 WHERE purchase_id=?", purchase);
        s.exec("UPDATE purchase SET version=version+1 WHERE id=?", purchase);
        s.audit.record(
                actor,
                "AUTHORIZATION_REVIEWED",
                "purchase",
                purchase,
                map("status", status, "reason", input.reason()));
        return s.view(purchase);
    }

    @Transactional
    public Map<String, Object> revokeAuthorization(UUID purchase, PurchaseDtos.Version input) {
        var p = s.purchase(purchase, true);
        s.buyer(p);
        s.version(p, input.expectedVersion());
        if (p.get("family_authorization_id") == null
                || Set.of("COMPLETED", "CANCELLED", "RESOLVED").contains(text(p.get("status"))))
            throw AppException.conflict(
                    "AUTHORIZATION_CLOSED",
                    "La compra no tiene una autorización vigente que pueda revocarse.");
        s.exec(
                "UPDATE family_authorization SET status='REVOKED',reason='Revocada por el"
                        + " comprador' WHERE id=?",
                p.get("family_authorization_id"));
        s.exec("UPDATE purchase SET version=version+1 WHERE id=?", purchase);
        s.exec("UPDATE child_order SET version=version+1 WHERE purchase_id=?", purchase);
        s.audit.record(s.access.actor(), "AUTHORIZATION_REVOKED", "purchase", purchase, Map.of());
        return s.view(purchase);
    }

    @Transactional
    public Map<String, Object> replaceAuthorization(
            UUID purchase, PurchaseDtos.AuthorizationReplacement input) {
        var p = s.purchase(purchase, true);
        s.buyer(p);
        s.version(p, input.expectedVersion());
        if (p.get("family_authorization_id") == null
                || Set.of("COMPLETED", "CANCELLED", "RESOLVED", "REJECTED")
                        .contains(text(p.get("status"))))
            throw AppException.conflict(
                    "AUTHORIZATION_CLOSED", "Esta compra no admite reemplazar una autorización.");
        s.unusedAuthorizationDocument(input.documentId());
        s.rememberAuthorizationDocument(id(p.get("family_authorization_id")), input.documentId());
        var before = s.snapshot(p);
        Object previous = before.get("authorizationDocumentId");
        before.put("authorizationDocumentId", input.documentId());
        s.exec(
                "UPDATE family_authorization SET"
                    + " document_id=?,status='PENDING',reviewed_by=NULL,reviewed_at=NULL,reason='Nota"
                    + " corregida pendiente de revisión',expires_at=NULL WHERE id=?",
                input.documentId(),
                p.get("family_authorization_id"));
        s.exec(
                "UPDATE purchase SET snapshot=cast(? as jsonb),version=version+1,updated_at=now()"
                        + " WHERE id=?",
                s.json.encode(before),
                purchase);
        s.exec("UPDATE child_order SET version=version+1 WHERE purchase_id=?", purchase);
        s.audit.record(
                s.access.actor(),
                "AUTHORIZATION_REPLACED",
                "purchase",
                purchase,
                map("previousDocumentId", previous, "documentId", input.documentId()));
        return s.view(purchase);
    }

    @Transactional
    public Map<String, Object> accept(UUID purchase, PurchaseDtos.Version r, String key) {
        Actor actor = s.access.actor();
        s.buyer(s.purchase(purchase, false));
        return idem.run(
                actor,
                "ACCEPT:" + purchase,
                key,
                r,
                () -> {
                    Map<String, Object> p = s.purchase(purchase, true);
                    s.buyer(p);
                    s.version(p, r.expectedVersion());
                    s.requireNoPendingChange(purchase);
                    if (!"QUOTED".equals(p.get("status")))
                        throw AppException.conflict(
                                "QUOTE_REQUIRED",
                                "La compra necesita un presupuesto completo vigente.");
                    Map<String, Object> q =
                            s.row(
                                    "SELECT * FROM quote WHERE purchase_id=? AND status='ISSUED'"
                                            + " ORDER BY version DESC LIMIT 1 FOR UPDATE",
                                    purchase);
                    if (!instant(q.get("expires_at")).isAfter(Instant.now()))
                        throw AppException.conflict(
                                "QUOTE_EXPIRED",
                                "El presupuesto venció; solicitá una nueva revisión antes de"
                                        + " aceptar.");
                    if (p.get("family_authorization_id") != null
                            && s.rows(
                                            "SELECT 1 FROM family_authorization WHERE id=? AND"
                                                + " status='APPROVED' AND (expires_at IS NULL OR"
                                                + " expires_at>now())",
                                            p.get("family_authorization_id"))
                                    .isEmpty())
                        throw AppException.conflict(
                                "AUTHORIZATION_INVALID",
                                "La autorización del titular no está vigente.");
                    validateReservedQuote(p, q);
                    var frozenConfig = s.json.readMap(s.json.encode(s.snapshot(q).get("config")));
                    int paymentMinutes =
                            ((Number) frozenConfig.getOrDefault("reservationMinutes", 30))
                                    .intValue();
                    s.exec(
                            "UPDATE reservation SET expires_at=now()+make_interval(mins => ?) WHERE"
                                + " order_id IN (SELECT id FROM child_order WHERE purchase_id=?)"
                                + " AND status='HELD'",
                            paymentMinutes,
                            purchase);
                    s.exec(
                            "UPDATE quote SET status='ACCEPTED',accepted_by=?,accepted_at=now()"
                                    + " WHERE id=?",
                            actor.id(),
                            q.get("id"));
                    s.exec(
                            "UPDATE child_order SET"
                                + " status='ACCEPTED',accepted_quote_id=?,version=version+1,updated_at=now()"
                                + " WHERE purchase_id=? AND status='PROPOSED'",
                            q.get("id"),
                            purchase);
                    s.exec(
                            "UPDATE purchase SET"
                                + " status='ACCEPTED',accepted_quote_id=?,version=version+1,updated_at=now()"
                                + " WHERE id=?",
                            q.get("id"),
                            purchase);
                    for (var o :
                            s.rows(
                                    "SELECT * FROM child_order WHERE purchase_id=? AND"
                                            + " status='ACCEPTED'",
                                    purchase))
                        s.exec(
                                "INSERT INTO"
                                    + " fulfillment(id,order_id,branch_id,mode,address,receiver_name,receiver_identity,snapshot)"
                                    + " VALUES(?,?,?,?,?,?,?,cast(? as jsonb))",
                                UUID.randomUUID(),
                                o.get("id"),
                                o.get("branch_id"),
                                p.get("mode"),
                                p.get("delivery_address"),
                                text(s.snapshot(p).get("receiverName")),
                                text(s.snapshot(p).get("receiverIdentity")),
                                s.json.encode(map("config", frozenConfig)));
                    s.audit.record(
                            actor,
                            "QUOTE_ACCEPTED",
                            "purchase",
                            purchase,
                            map(
                                    "quoteId",
                                    q.get("id"),
                                    "quoteVersion",
                                    q.get("version"),
                                    "total",
                                    amount(q.get("total")).toPlainString()));
                    return s.view(purchase);
                });
    }

    @Transactional
    public Map<String, Object> reroute(UUID purchase, PurchaseDtos.Version r) {
        Map<String, Object> p = s.purchase(purchase, true);
        s.buyer(p);
        s.version(p, r.expectedVersion());
        s.requireNoPendingChange(purchase);
        if (!Set.of("ROUTING", "QUOTED", "UNAVAILABLE").contains(text(p.get("status"))))
            throw AppException.conflict(
                    "REROUTE_STATE_INVALID",
                    "Una compra aceptada requiere cancelación o incidencia, no cambio silencioso de"
                            + " farmacia.");
        route(p);
        s.audit.record(s.access.actor(), "PURCHASE_REROUTED", "purchase", purchase, Map.of());
        return s.view(purchase);
    }

    public Map<String, Object> get(UUID purchase) {
        return s.view(purchase);
    }

    public List<Map<String, Object>> list() {
        Actor a = s.access.actor();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var p :
                s.rows(
                        "SELECT DISTINCT p.id,p.created_at FROM purchase p LEFT JOIN child_order o"
                            + " ON o.purchase_id=p.id WHERE (? AND p.buyer_user_id=?) OR"
                            + " EXISTS(SELECT 1 FROM membership m JOIN user_role ur ON"
                            + " ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=? AND"
                            + " m.branch_id=o.branch_id AND m.active AND m.role IN"
                            + " ('PHARMACY','PHARMACIST')) ORDER BY p.created_at DESC LIMIT 100",
                        a.hasRole("BUYER"),
                        a.id(),
                        a.id())) result.add(s.view(id(p.get("id"))));
        return result;
    }
}
