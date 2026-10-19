package ar.emedicapp.replacements;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;
import ar.emedicapp.purchase.IdempotencyService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Explicit disposable-DB fixtures; no provider calls and no real patient files. */
@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class ReplacementIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired ReplacementService service;
    MockMvc mvc;
    Session buyer, pharmacist, operator;
    final UUID buyerId = DemoIds.id("user:comprador@demo.invalid"),
            pharmacistId = DemoIds.id("user:farmaceutico@demo.invalid"),
            operatorId = DemoIds.id("user:farmacia@demo.invalid"),
            driverId = DemoIds.id("user:cadete@demo.invalid");
    final Set<UUID> fixtures = new HashSet<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        p.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        p.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() throws Exception {
        fixtures.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        buyer = login("comprador@demo.invalid");
        pharmacist = login("farmaceutico@demo.invalid");
        operator = login("farmacia@demo.invalid");
    }

    @AfterEach
    void releaseOnlyFixtureDriverCustodyAssignments() {
        for (UUID f : fixtures) sql("UPDATE fulfillment SET driver_user_id=NULL WHERE id=?", f);
    }

    @Test
    void physicalReturnThenNewStockAndLotFulfillReplacementWithoutRewritingOriginal()
            throws Exception {
        Fixture f = fixture(false, false);
        sql(
                "UPDATE fulfillment SET"
                        + " status='RETURN_REQUIRED',custody_holder='DRIVER',driver_user_id=? WHERE"
                        + " id=?",
                driverId,
                f.fulfillment);
        sql("UPDATE child_order SET status='DISPATCHED' WHERE id=?", f.order);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/replacements",
                proposal(f, "RETURNED"),
                null,
                409);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/return-received",
                Map.of(
                        "expectedVersion",
                        orderVersion(f.order),
                        "custodyNote",
                        "Paquete original recibido, separado del stock disponible",
                        "evidenceDocumentId",
                        f.evidence),
                null,
                200);
        var request = propose(f, "RETURNED");
        UUID replacement = id(request, "id");
        mvc.perform(get("/api/orders/" + f.order + "/replacements").session(buyer.session))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + f.order + "/replacements").session(operator.session))
                .andExpect(status().isForbidden());
        var accept = accept(f, replacement);
        String key = UUID.randomUUID().toString();
        var accepted =
                post(buyer, "/api/replacements/" + replacement + "/accept", accept, key, 200);
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, key, 200);
        UUID newOrder = id(accepted, "replacementOrderId"),
                newPurchase = id(accepted, "replacementPurchaseId");
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                f.branch,
                                f.product))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                f.branch,
                                f.product))
                .isEqualTo(5);
        assertThat(count("SELECT reserved_quantity FROM stock_lot WHERE id=?", f.lot)).isEqualTo(1);
        assertThat(count("SELECT retry_count FROM fulfillment WHERE order_id=?", newOrder))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", newOrder)).isZero();
        assertThat(value("SELECT total::text FROM purchase WHERE id=?", newPurchase))
                .isEqualTo("0.00");
        assertThat(uuid("SELECT invoice_document_id FROM child_order WHERE id=?", newOrder))
                .isEqualTo(f.invoice);
        post(
                pharmacist,
                "/api/orders/" + newOrder + "/prepare",
                Map.of("expectedVersion", orderVersion(newOrder)),
                null,
                409);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/resolve-incident",
                Map.of(
                        "expectedVersion",
                        orderVersion(f.order),
                        "decision",
                        "CLOSE_WITHOUT_REFUND",
                        "reason",
                        "No se puede cerrar la incidencia mientras se cumple la reposición",
                        "evidenceDocumentId",
                        f.evidence),
                null,
                409);
        review(f, replacement, null);
        finishPickup(f, newOrder, null);
        assertThat(value("SELECT status FROM replacement_request WHERE id=?", replacement))
                .isEqualTo("FULFILLED");
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT dispensed_quantity FROM order_item WHERE id=?", f.line))
                .isEqualTo(1);
        assertThat(value("SELECT status FROM reservation WHERE id=?", f.reservation))
                .isEqualTo("CONSUMED");
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                f.branch,
                                f.product))
                .isEqualTo(4);
        assertThat(count("SELECT quantity FROM stock_lot WHERE id=?", f.lot)).isEqualTo(4);
        assertThat(
                        count(
                                "SELECT count(*) FROM stock_movement WHERE branch_id=? AND"
                                        + " quantity_delta>0",
                                f.branch))
                .isZero();
        assertThat(uuid("SELECT invoice_document_id FROM child_order WHERE id=?", f.order))
                .isEqualTo(f.invoice);
        assertThat(
                        count(
                                "SELECT count(*) FROM replacement_request WHERE"
                                        + " original_order_id=?",
                                f.order))
                .isEqualTo(1);
    }

    @Test
    void stockLossAfterProposalRollsBackAcceptanceAndLeavesOriginalEvidenceIntact()
            throws Exception {
        Fixture f = fixture(false, false);
        UUID replacement = id(propose(f, "NO_RETURN_DOCUMENTED"), "id");
        sql(
                "UPDATE branch_stock SET quantity=0 WHERE branch_id=? AND product_id=?",
                f.branch,
                f.product);
        long purchases = count("SELECT count(*) FROM purchase WHERE buyer_user_id=?", buyerId);
        post(
                buyer,
                "/api/replacements/" + replacement + "/accept",
                accept(f, replacement),
                null,
                409);
        assertThat(value("SELECT status FROM replacement_request WHERE id=?", replacement))
                .isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM purchase WHERE buyer_user_id=?", buyerId))
                .isEqualTo(purchases);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                f.branch,
                                f.product))
                .isZero();
        assertThat(count("SELECT reserved_quantity FROM stock_lot WHERE id=?", f.lot)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                        + " status='CONSUMED'",
                                f.order))
                .isEqualTo(1);
        assertThat(uuid("SELECT invoice_document_id FROM child_order WHERE id=?", f.order))
                .isEqualTo(f.invoice);
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE id=? AND"
                                        + " snapshot->>'activeReplacementId' IS NOT NULL",
                                f.order))
                .isZero();
        Fixture expiring = fixture(false, false);
        UUID expiringRequest = id(propose(expiring, "NO_RETURN_DOCUMENTED"), "id");
        var accepted =
                post(
                        buyer,
                        "/api/replacements/" + expiringRequest + "/accept",
                        accept(expiring, expiringRequest),
                        null,
                        200);
        UUID expiringOrder = id(accepted, "replacementOrderId");
        sql(
                "UPDATE reservation SET expires_at=now()-interval '1 minute' WHERE order_id=? AND"
                        + " status='HELD'",
                expiringOrder);
        assertThat(service.expire(expiringRequest)).isTrue();
        assertThat(service.expire(expiringRequest)).isFalse();
        assertThat(value("SELECT status FROM child_order WHERE id=?", expiringOrder))
                .isEqualTo("CANCELLED");
        assertThat(value("SELECT status FROM replacement_request WHERE id=?", expiringRequest))
                .isEqualTo("CANCELLED");
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                expiring.branch,
                                expiring.product))
                .isZero();
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", expiring.fulfillment))
                .isEqualTo("INCIDENT");
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE id=? AND"
                                        + " snapshot->>'activeReplacementId' IS NOT NULL",
                                expiring.order))
                .isZero();
    }

    @Test
    void newPrescriptionAndExplicitAuthorizedQuantityAreRequiredWithoutRestoringOldRights()
            throws Exception {
        Fixture f = fixture(true, false);
        UUID replacement = id(propose(f, "NO_RETURN_DOCUMENTED"), "id");
        var accept = accept(f, replacement);
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 400);
        accept.put("prescriptionDocumentId", f.originalPrescriptionDocument);
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 409);
        accept.put("prescriptionDocumentId", document(buyerId, null, "PRESCRIPTION", null));
        var accepted =
                post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 200);
        UUID newOrder = id(accepted, "replacementOrderId");
        post(
                pharmacist,
                "/api/orders/" + newOrder + "/prepare",
                Map.of("expectedVersion", orderVersion(newOrder)),
                null,
                409);
        var review = reviewBody(f, replacement);
        review.put("originVerified", false);
        review.put("lines", List.of(Map.of("lineId", f.line, "authorizedQuantity", 1)));
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 400);
        review.put("originVerified", true);
        review.put("lines", List.of(Map.of("lineId", f.line, "authorizedQuantity", 0)));
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 400);
        review.put("lines", List.of(Map.of("lineId", f.line, "authorizedQuantity", 1)));
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 200);
        assertThat(
                        count(
                                "SELECT dispensed_quantity FROM prescription_item WHERE id=?",
                                f.originalPrescriptionItem))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM prescription_item WHERE id=?",
                                f.originalPrescriptionItem))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation r JOIN prescription_item ri ON"
                                        + " ri.id=r.prescription_item_id WHERE r.order_id=? AND"
                                        + " ri.id<>? AND ri.authorized_quantity=1 AND"
                                        + " ri.reserved_quantity=1",
                                newOrder,
                                f.originalPrescriptionItem))
                .isEqualTo(1);
        finishPickup(f, newOrder, null);
        assertThat(
                        count(
                                "SELECT dispensed_quantity FROM prescription_item WHERE id=?",
                                f.originalPrescriptionItem))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM purchase_item pi JOIN prescription_item ri ON"
                                    + " ri.id=pi.prescription_item_id WHERE pi.purchase_id=? AND"
                                    + " ri.id<>? AND ri.dispensed_quantity=1",
                                id(accepted, "replacementPurchaseId"),
                                f.originalPrescriptionItem))
                .isEqualTo(1);
    }

    @Test
    void replacementNeedsFreshFamilyNoteAndCurrentManualApprovalBeforeHandout() throws Exception {
        Fixture f = fixture(false, true);
        UUID replacement = id(propose(f, "NO_RETURN_DOCUMENTED"), "id");
        var accept = accept(f, replacement);
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 400);
        accept.put("authorizationDocumentId", f.originalAuthorizationDocument);
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 409);
        String usedHash =
                value(
                        "SELECT sha256::text FROM document WHERE id=?",
                        f.originalAuthorizationDocument);
        accept.put(
                "authorizationDocumentId",
                document(buyerId, null, "FAMILY_AUTHORIZATION", usedHash));
        post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 409);
        UUID note = document(buyerId, null, "FAMILY_AUTHORIZATION", null);
        accept.put("authorizationDocumentId", note);
        var accepted =
                post(buyer, "/api/replacements/" + replacement + "/accept", accept, null, 200);
        UUID newPurchase = id(accepted, "replacementPurchaseId"),
                newOrder = id(accepted, "replacementOrderId");
        UUID auth = uuid("SELECT family_authorization_id FROM purchase WHERE id=?", newPurchase);
        assertThat(value("SELECT status FROM family_authorization WHERE id=?", auth))
                .isEqualTo("PENDING");
        assertThat(
                        count(
                                "SELECT count(*) FROM family_authorization_usage WHERE"
                                        + " document_id=?",
                                note))
                .isEqualTo(1);
        var review = reviewBody(f, replacement);
        review.put("authorizationChecked", false);
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 400);
        review.put("authorizationChecked", true);
        sql("UPDATE family_authorization SET status='REVOKED' WHERE id=?", auth);
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 409);
        sql("UPDATE family_authorization SET status='PENDING' WHERE id=?", auth);
        post(pharmacist, "/api/replacements/" + replacement + "/review", review, null, 200);
        prepareAndDispense(f, newOrder);
        var receipt = receipt(f, newOrder, note);
        sql("UPDATE family_authorization SET status='REVOKED' WHERE id=?", auth);
        post(pharmacist, "/api/orders/" + newOrder + "/pickup-complete", receipt, null, 409);
        assertThat(value("SELECT status FROM fulfillment WHERE order_id=?", newOrder))
                .isEqualTo("READY");
        sql("UPDATE family_authorization SET status='APPROVED' WHERE id=?", auth);
        post(pharmacist, "/api/orders/" + newOrder + "/pickup-complete", receipt, null, 200);
        assertThat(
                        value(
                                "SELECT status FROM family_authorization WHERE id=?",
                                f.originalAuthorization))
                .isEqualTo("APPROVED");
    }

    @Test
    void commonMedicineWithRegisteredUnusableLotsCannotReserveWithoutLot() throws Exception {
        for (String unavailable : List.of("EXPIRED", "QUARANTINED", "FULLY_RESERVED")) {
            Fixture f = fixture(false, false);
            UUID replacement = id(propose(f, "NO_RETURN_DOCUMENTED"), "id");
            if ("EXPIRED".equals(unavailable))
                sql("UPDATE stock_lot SET expires_on=current_date-1 WHERE id=?", f.lot);
            else if ("QUARANTINED".equals(unavailable))
                sql("UPDATE stock_lot SET status='QUARANTINED' WHERE id=?", f.lot);
            else sql("UPDATE stock_lot SET reserved_quantity=quantity WHERE id=?", f.lot);
            long purchases = count("SELECT count(*) FROM purchase WHERE buyer_user_id=?", buyerId);
            var rejected =
                    post(
                            buyer,
                            "/api/replacements/" + replacement + "/accept",
                            accept(f, replacement),
                            null,
                            409);
            assertThat(rejected.get("code").asText()).as(unavailable).isEqualTo("LOT_UNAVAILABLE");
            assertThat(count("SELECT count(*) FROM purchase WHERE buyer_user_id=?", buyerId))
                    .isEqualTo(purchases);
            assertThat(
                            count(
                                    "SELECT reserved_quantity FROM branch_stock WHERE branch_id=?"
                                            + " AND product_id=?",
                                    f.branch,
                                    f.product))
                    .isZero();
            assertThat(
                            count(
                                    "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                            + " status='CONSUMED'",
                                    f.order))
                    .isEqualTo(1);
            assertThat(value("SELECT status FROM replacement_request WHERE id=?", replacement))
                    .isEqualTo("PROPOSED");
            sql(
                    "UPDATE stock_lot SET"
                        + " status='AVAILABLE',expires_on=current_date+60,reserved_quantity=0 WHERE"
                        + " id=?",
                    f.lot);
            var accepted =
                    post(
                            buyer,
                            "/api/replacements/" + replacement + "/accept",
                            accept(f, replacement),
                            null,
                            200);
            assertThat(
                            uuid(
                                    "SELECT lot_id FROM reservation WHERE order_id=? AND"
                                            + " status='HELD'",
                                    id(accepted, "replacementOrderId")))
                    .isEqualTo(f.lot);
            assertThat(count("SELECT reserved_quantity FROM stock_lot WHERE id=?", f.lot))
                    .isEqualTo(1);
        }
    }

    @Test
    void realSelfPurchaseUsesFrozenIdentityAndReplacementDoesNotRequireFamilyNote()
            throws Exception {
        Fixture resources = fixture(false, false);
        UUID account = UUID.randomUUID();
        String email = "replacement-self-" + account + "@test.invalid";
        String name = "Persona de reposición " + account.toString().substring(0, 8);
        String dni =
                "91"
                        + String.format(
                                Locale.ROOT,
                                "%010d",
                                ThreadLocalRandom.current().nextLong(10_000_000_000L));
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " SELECT ?,?,?,password_hash,'1990-01-01',true,? FROM app_user WHERE id=?",
                account,
                email,
                name,
                dni,
                buyerId);
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'BUYER')", account);
        Session actualBuyer = login(email);
        var created =
                post(
                        actualBuyer,
                        "/api/purchases",
                        Map.of(
                                "beneficiaryName",
                                name,
                                "beneficiaryBirthDate",
                                "1990-01-01",
                                "thirdParty",
                                false,
                                "mode",
                                "PICKUP",
                                "destinationLat",
                                -32.889,
                                "destinationLon",
                                -68.845,
                                "destinationAddress",
                                "Domicilio de prueba",
                                "preferredBranchId",
                                resources.branch,
                                "items",
                                List.of(Map.of("productId", resources.product, "quantity", 1))),
                        null,
                        200);
        UUID purchase = id(created, "id"), order = id(created.get("orders").get(0), "id");
        UUID beneficiary = uuid("SELECT beneficiary_id FROM purchase WHERE id=?", purchase);
        assertThat(value("SELECT identity_reference FROM beneficiary WHERE id=?", beneficiary))
                .isEmpty();
        assertThat(
                        value(
                                "SELECT snapshot->>'beneficiaryIdentity' FROM purchase WHERE id=?",
                                purchase))
                .isEqualTo(dni);
        post(
                pharmacist,
                "/api/orders/" + order + "/review",
                Map.of(
                        "expectedVersion",
                        orderVersion(order),
                        "decision",
                        "APPROVE",
                        "reason",
                        "Stock físico y modalidad de retiro comprobados",
                        "authorizationChecked",
                        true,
                        "deliveryMinutes",
                        30,
                        "lines",
                        List.of(Map.of("productId", resources.product, "coverageAmount", "0.00"))),
                null,
                200);
        post(
                actualBuyer,
                "/api/purchases/" + purchase + "/accept",
                Map.of(
                        "expectedVersion",
                        count("SELECT version FROM purchase WHERE id=?", purchase)),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + order + "/payment",
                Map.of(
                        "expectedVersion",
                        orderVersion(order),
                        "reference",
                        "COBRO-REAL-TEST-" + order,
                        "evidenceDocumentId",
                        document(pharmacistId, resources.branch, "PAYMENT_RECEIPT", null),
                        "verifiedExternally",
                        true,
                        "amount",
                        "1000.00",
                        "currency",
                        "ARS"),
                null,
                200);
        UUID invoice = document(pharmacistId, resources.branch, "INVOICE", null);
        post(
                pharmacist,
                "/api/orders/" + order + "/invoice",
                Map.of(
                        "expectedVersion",
                        orderVersion(order),
                        "reference",
                        "FACTURA-REAL-TEST-" + order,
                        "evidenceDocumentId",
                        invoice),
                null,
                200);
        prepareAndDispense(resources, order);
        post(
                actualBuyer,
                "/api/orders/" + order + "/incident",
                Map.of(
                        "expectedVersion",
                        orderVersion(order),
                        "kind",
                        "PRODUCT_DISCREPANCY",
                        "reason",
                        "Discrepancia constatada antes del retiro; solicita evaluación"
                                + " farmacéutica"),
                null,
                200);
        Fixture original =
                new Fixture(
                        resources.branch,
                        beneficiary,
                        purchase,
                        order,
                        uuid("SELECT id FROM fulfillment WHERE order_id=?", order),
                        resources.product,
                        resources.lot,
                        uuid("SELECT id FROM order_item WHERE order_id=?", order),
                        uuid("SELECT id FROM reservation WHERE order_id=?", order),
                        invoice,
                        resources.evidence,
                        null,
                        null,
                        null,
                        null);
        UUID replacement = id(propose(original, "NO_RETURN_DOCUMENTED"), "id");
        post(
                actualBuyer,
                "/api/replacements/" + replacement + "/accept",
                Map.of(
                        "expectedVersion",
                        replacementVersion(replacement),
                        "receiverName",
                        name,
                        "receiverIdentity",
                        "77777777"),
                null,
                400);
        String formattedDni =
                dni.substring(0, 2) + "." + dni.substring(2, 5) + "." + dni.substring(5);
        var accepted =
                post(
                        actualBuyer,
                        "/api/replacements/" + replacement + "/accept",
                        Map.of(
                                "expectedVersion",
                                replacementVersion(replacement),
                                "receiverName",
                                name,
                                "receiverIdentity",
                                formattedDni),
                        null,
                        200);
        assertThat(accepted.get("requiresAuthorization").asBoolean()).isFalse();
        UUID replacementPurchase = id(accepted, "replacementPurchaseId"),
                replacementOrder = id(accepted, "replacementOrderId");
        assertThat(
                        count(
                                "SELECT count(*) FROM purchase WHERE id=? AND"
                                        + " family_authorization_id IS NULL",
                                replacementPurchase))
                .isEqualTo(1);
        assertThat(
                        value(
                                "SELECT snapshot->>'beneficiaryIdentity' FROM purchase WHERE id=?",
                                replacementPurchase))
                .isEqualTo(dni);
        assertThat(
                        value(
                                "SELECT receiver_identity FROM fulfillment WHERE order_id=?",
                                replacementOrder))
                .isEqualTo(dni);
        assertThat(value("SELECT status FROM child_order WHERE id=?", replacementOrder))
                .isEqualTo("PAID");
    }

    private Fixture fixture(boolean rx, boolean family) {
        UUID branch = UUID.randomUUID(),
                beneficiary = UUID.randomUUID(),
                purchase = UUID.randomUUID(),
                order = UUID.randomUUID(),
                f = UUID.randomUUID(),
                quote = UUID.randomUUID(),
                product = UUID.randomUUID(),
                lot = UUID.randomUUID(),
                item = UUID.randomUUID(),
                line = UUID.randomUUID(),
                quoteLine = UUID.randomUUID(),
                reservation = UUID.randomUUID();
        String buyerName = value("SELECT name FROM app_user WHERE id=?", buyerId);
        sql(
                "INSERT INTO branch(id,name,address,latitude,longitude,verified,agreement_accepted)"
                        + " VALUES(?,'Farmacia de reposición aislada','Domicilio de"
                        + " prueba',-32.889,-68.845,true,true)",
                branch);
        for (var actor : Map.of(pharmacistId, "PHARMACIST", operatorId, "PHARMACY").entrySet())
            sql(
                    "INSERT INTO membership(id,user_id,branch_id,role) VALUES(?,?,?,?)",
                    UUID.randomUUID(),
                    actor.getKey(),
                    branch,
                    actor.getValue());
        sql(
                "INSERT INTO beneficiary(id,owner_user_id,name,birth_date,identity_reference)"
                        + " VALUES(?,?,?,'1985-04-15',?)",
                beneficiary,
                buyerId,
                family ? "Familiar adulta de prueba" : buyerName,
                family ? "37876543" : "28456781");
        UUID auth = null, authDoc = null, rxItem = null, rxDoc = null;
        if (family) {
            auth = UUID.randomUUID();
            authDoc = document(buyerId, null, "FAMILY_AUTHORIZATION", null);
            sql(
                    "INSERT INTO"
                        + " family_authorization(id,beneficiary_id,buyer_user_id,document_id,scope,status,reviewed_by,reviewed_at)"
                        + " VALUES(?,?,?,?,'BUY_AND_RECEIVE','APPROVED',?,now())",
                    auth,
                    beneficiary,
                    buyerId,
                    authDoc,
                    pharmacistId);
            sql(
                    "INSERT INTO family_authorization_usage(sha256,document_id,authorization_id)"
                            + " SELECT sha256,id,? FROM document WHERE id=?",
                    auth,
                    authDoc);
        }
        sql(
                "INSERT INTO product(id,code,name,presentation,requires_prescription)"
                        + " VALUES(?,?,'Medicamento aislado para reposición','Envase de prueba',?)",
                product,
                "REPLACEMENT-TEST-" + product,
                rx);
        sql(
                "INSERT INTO branch_stock(branch_id,product_id,quantity,price) VALUES(?,?,5,1000)",
                branch,
                product);
        sql(
                "INSERT INTO stock_lot(id,branch_id,product_id,lot_code,expires_on,quantity)"
                        + " VALUES(?,?,?,'LOTE-NUEVO',current_date+60,5)",
                lot,
                branch,
                product);
        if (rx) {
            UUID prescription = UUID.randomUUID();
            rxItem = UUID.randomUUID();
            rxDoc = document(buyerId, null, "PRESCRIPTION", null);
            sql(
                    "INSERT INTO"
                        + " prescription(id,owner_user_id,beneficiary_id,document_id,external_reference,issuer,provider_verification,status,expires_at)"
                        + " VALUES(?,?,?,?,?,'Emisor de"
                        + " prueba','MANUALLY_VERIFIED','CONSUMED',now()+interval '1 day')",
                    prescription,
                    buyerId,
                    beneficiary,
                    rxDoc,
                    "RX-ORIGINAL-" + prescription);
            sql(
                    "INSERT INTO"
                        + " prescription_item(id,prescription_id,product_id,authorized_quantity,dispensed_quantity)"
                        + " VALUES(?,?,?,1,1)",
                    rxItem,
                    prescription,
                    product);
        }
        sql(
                "INSERT INTO"
                    + " purchase(id,buyer_user_id,beneficiary_id,family_authorization_id,mode,latitude,longitude,status,total,snapshot)"
                    + " VALUES(?,?,?,?,'PICKUP',-32.889,-68.845,'COMPENSATING',1000,cast(? as"
                    + " jsonb))",
                purchase,
                buyerId,
                beneficiary,
                auth,
                json.writeValueAsString(
                        Map.of(
                                "receiverName",
                                buyerName,
                                "receiverIdentity",
                                "28456781",
                                "thirdParty",
                                family)));
        sql(
                "INSERT INTO"
                    + " quote(id,purchase_id,version,status,subtotal,total,expires_at,accepted_by,accepted_at)"
                    + " VALUES(?,?,1,'ACCEPTED',1000,1000,now()+interval '1 hour',?,now())",
                quote,
                purchase,
                buyerId);
        sql("UPDATE purchase SET accepted_quote_id=? WHERE id=?", quote, purchase);
        UUID invoice = document(pharmacistId, branch, "INVOICE", null),
                evidence = document(pharmacistId, branch, "DELIVERY_PROOF", null);
        sql(
                "INSERT INTO"
                    + " child_order(id,purchase_id,branch_id,status,total,accepted_quote_id,invoice_document_id,snapshot)"
                    + " VALUES(?,?,?,'DISPENSED',1000,?,?,cast(? as jsonb))",
                order,
                purchase,
                branch,
                quote,
                invoice,
                json.writeValueAsString(
                        Map.of(
                                "reviewApproved",
                                true,
                                "highValueRequired",
                                false,
                                "invoiceReference",
                                "FACTURA-ORIGINAL-" + order)));
        sql(
                "INSERT INTO purchase_item(id,purchase_id,product_id,quantity,prescription_item_id)"
                        + " VALUES(?,?,?,1,?)",
                item,
                purchase,
                product,
                rxItem);
        sql(
                "INSERT INTO"
                    + " quote_line(id,quote_id,order_id,purchase_item_id,product_id,quantity,unit_price,total)"
                    + " VALUES(?,?,?,?,?,1,1000,1000)",
                quoteLine,
                quote,
                order,
                item,
                product);
        sql(
                "INSERT INTO"
                    + " order_item(id,order_id,purchase_item_id,product_id,quantity,quote_line_id,dispensed_quantity,snapshot)"
                    + " VALUES(?,?,?,?,1,?,1,'{\"unitPrice\":1000,\"coverageAmount\":0}')",
                line,
                order,
                item,
                product,
                quoteLine);
        sql(
                "INSERT INTO"
                    + " reservation(id,order_id,order_item_id,branch_id,product_id,prescription_item_id,quantity,dispensed_quantity,status,expires_at)"
                    + " VALUES(?,?,?,?,?,?,1,1,'CONSUMED',now()+interval '1 hour')",
                reservation,
                order,
                line,
                branch,
                product,
                rxItem);
        sql(
                "INSERT INTO"
                    + " fulfillment(id,order_id,branch_id,mode,status,receiver_name,receiver_identity,retry_count,custody_holder,snapshot)"
                    + " VALUES(?,?,?,'PICKUP','INCIDENT',?,'28456781',1,'PHARMACY','{\"incidentKind\":\"PRODUCT_DISCREPANCY\",\"incidentReason\":\"Discrepancia"
                    + " documentada tras dispensación\"}')",
                f,
                order,
                branch,
                buyerName);
        sql(
                "INSERT INTO"
                    + " payment(id,order_id,branch_id,amount,currency,status,external_reference,verified_by,verified_at)"
                    + " VALUES(?,?,?,1000,'ARS','VERIFIED',?,?,now())",
                UUID.randomUUID(),
                order,
                branch,
                "ORIGINAL-PAID-" + order,
                pharmacistId);
        fixtures.add(f);
        return new Fixture(
                branch,
                beneficiary,
                purchase,
                order,
                f,
                product,
                lot,
                line,
                reservation,
                invoice,
                evidence,
                auth,
                authDoc,
                rxItem,
                rxDoc);
    }

    private Map<String, Object> proposal(Fixture f, String disposition) {
        return Map.of(
                "expectedVersion",
                orderVersion(f.order),
                "lines",
                List.of(Map.of("lineId", f.line, "quantity", 1)),
                "reason",
                "Reposición del mismo medicamento y presentación sin cargo",
                "evidenceDocumentId",
                f.evidence,
                "originalDisposition",
                disposition,
                "dispositionNote",
                "Condición y custodia del original comprobadas por la farmacia; sin reingreso a"
                        + " stock",
                "mode",
                "PICKUP",
                "deliveryMinutes",
                30);
    }

    private JsonNode propose(Fixture f, String disposition) throws Exception {
        return post(
                pharmacist,
                "/api/orders/" + f.order + "/replacements",
                proposal(f, disposition),
                null,
                200);
    }

    private Map<String, Object> accept(Fixture f, UUID replacement) {
        return new LinkedHashMap<>(
                Map.of(
                        "expectedVersion",
                        replacementVersion(replacement),
                        "receiverName",
                        value("SELECT name FROM app_user WHERE id=?", buyerId),
                        "receiverIdentity",
                        "28456781"));
    }

    private Map<String, Object> reviewBody(Fixture f, UUID replacement) {
        return new LinkedHashMap<>(
                Map.of(
                        "expectedVersion",
                        replacementVersion(replacement),
                        "reason",
                        "Evaluación farmacéutica real de las unidades nuevas y documentos",
                        "conditionEvidenceDocumentId",
                        f.evidence,
                        "authorizationChecked",
                        true,
                        "originVerified",
                        true,
                        "prescriptionReference",
                        "RX-NUEVA-" + replacement,
                        "issuer",
                        "Emisor de prueba de reposición",
                        "expiresAt",
                        Instant.now().plusSeconds(86400).toString()));
    }

    private void review(Fixture f, UUID replacement, Integer quantity) throws Exception {
        var body = reviewBody(f, replacement);
        if (quantity != null)
            body.put("lines", List.of(Map.of("lineId", f.line, "authorizedQuantity", quantity)));
        post(pharmacist, "/api/replacements/" + replacement + "/review", body, null, 200);
    }

    private void prepareAndDispense(Fixture f, UUID order) throws Exception {
        post(
                pharmacist,
                "/api/orders/" + order + "/prepare",
                Map.of("expectedVersion", orderVersion(order)),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + order + "/dispense",
                Map.of(
                        "expectedVersion",
                        orderVersion(order),
                        "prescriptionConsumptionReference",
                        "DISPENSA-REPOSICION-" + order,
                        "conditionNote",
                        "Envase nuevo controlado",
                        "conditionEvidenceDocumentId",
                        f.evidence),
                null,
                200);
    }

    private Map<String, Object> receipt(Fixture f, UUID order, UUID note) throws Exception {
        var response =
                mvc.perform(get("/api/orders/" + order + "/receipt-code").session(buyer.session))
                        .andExpect(status().isOk())
                        .andReturn();
        var code = json.readTree(response.getResponse().getContentAsString()).get("code").asText();
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "expectedVersion",
                                orderVersion(order),
                                "code",
                                code,
                                "recipientVerified",
                                true,
                                "packageMatches",
                                true,
                                "note",
                                "Identidad presencial y medicamento coinciden con la reposición",
                                "recipientName",
                                value("SELECT name FROM app_user WHERE id=?", buyerId),
                                "recipientIdentity",
                                "28456781"));
        if (note != null) body.put("authorizationDocumentId", note);
        return body;
    }

    private void finishPickup(Fixture f, UUID order, UUID note) throws Exception {
        prepareAndDispense(f, order);
        post(
                pharmacist,
                "/api/orders/" + order + "/pickup-complete",
                receipt(f, order, note),
                null,
                200);
    }

    private UUID document(UUID owner, UUID branch, String kind, String hash) {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " document(id,owner_user_id,branch_id,kind,original_name,content_type,size_bytes,storage_key,sha256)"
                    + " VALUES(?,?,?,?,?,'application/pdf',10,?,?)",
                id,
                owner,
                branch,
                kind,
                "documento-aislado-reposicion.pdf",
                "replacement-test:" + id,
                hash == null ? IdempotencyService.hash("document:" + id) : hash);
        return id;
    }

    private UUID id(JsonNode node, String key) {
        return UUID.fromString(node.get(key).asText());
    }

    private long orderVersion(UUID order) {
        return count("SELECT version FROM child_order WHERE id=?", order);
    }

    private long replacementVersion(UUID replacement) {
        return count("SELECT version FROM replacement_request WHERE id=?", replacement);
    }

    private void sql(String query, Object... args) {
        db.sql(query).params(args).update();
    }

    private long count(String query, Object... args) {
        return db.sql(query).params(args).query(Long.class).single();
    }

    private String value(String query, Object... args) {
        return db.sql(query).params(args).query(String.class).single();
    }

    private UUID uuid(String query, Object... args) {
        return db.sql(query).params(args).query(UUID.class).single();
    }

    private JsonNode post(Session session, String path, Object body, String key, int expected)
            throws Exception {
        var result =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post(path)
                                        .session(session.session)
                                        .header(session.header, session.token)
                                        .header(
                                                "Idempotency-Key",
                                                key == null ? UUID.randomUUID().toString() : key)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(
                        "%s: %s; cause=%s",
                        path,
                        result.getResponse().getContentAsString(),
                        result.getResolvedException())
                .isEqualTo(expected);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Session login(String email) throws Exception {
        Session csrf = csrf(null);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/auth/login")
                                .with(
                                        r -> {
                                            r.setRemoteAddr(
                                                    "127.4."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255)
                                                            + "."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255));
                                            return r;
                                        })
                                .session(csrf.session)
                                .header(csrf.header, csrf.token)
                                .param("email", email)
                                .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isOk());
        return csrf(csrf.session);
    }

    private Session csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Session(
                (MockHttpSession) result.getRequest().getSession(),
                body.get("token").asText(),
                body.get("headerName").asText());
    }

    record Session(MockHttpSession session, String token, String header) {}

    record Fixture(
            UUID branch,
            UUID beneficiary,
            UUID purchase,
            UUID order,
            UUID fulfillment,
            UUID product,
            UUID lot,
            UUID line,
            UUID reservation,
            UUID invoice,
            UUID evidence,
            UUID originalAuthorization,
            UUID originalAuthorizationDocument,
            UUID originalPrescriptionItem,
            UUID originalPrescriptionDocument) {}
}
