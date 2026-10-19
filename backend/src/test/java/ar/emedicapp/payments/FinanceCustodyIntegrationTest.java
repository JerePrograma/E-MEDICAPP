package ar.emedicapp.payments;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;
import ar.emedicapp.fulfillment.FulfillmentService;
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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Isolated transaction fixtures: only runs against the explicitly supplied disposable test DB. */
@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class FinanceCustodyIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired FulfillmentService fulfillment;
    MockMvc mvc;
    Session buyer, pharmacist, second, driver, operator;
    final Set<UUID> fixtureFulfillments = new HashSet<>();
    UUID buyerId = DemoIds.id("user:comprador@demo.invalid"),
            pharmacistId = DemoIds.id("user:farmaceutico@demo.invalid"),
            secondId = DemoIds.id("user:farmaceutico2@demo.invalid"),
            driverId = DemoIds.id("user:cadete@demo.invalid"),
            operatorId = DemoIds.id("user:farmacia@demo.invalid");

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
        fixtureFulfillments.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        buyer = login("comprador@demo.invalid");
        pharmacist = login("farmaceutico@demo.invalid");
        second = login("farmaceutico2@demo.invalid");
        driver = login("cadete@demo.invalid");
        operator = login("farmacia@demo.invalid");
    }

    @AfterEach
    void releaseOnlyIsolatedFixtureDrivers() {
        for (UUID fixture : fixtureFulfillments)
            sql("UPDATE fulfillment SET driver_user_id=NULL WHERE id=?", fixture);
    }

    @Test
    void paymentRequiresRealAmountCurrencyAndSettlesOnlyTheRemainingBalance() throws Exception {
        Fixture f = fixture("ACCEPTED", "PENDING", "PICKUP", false);
        UUID proof = evidence(f.branch, "PAYMENT_RECEIPT");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/payment",
                confirm(f, "-1", "ARS", proof),
                null,
                400);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/payment",
                confirm(f, "1000", "USD", proof),
                null,
                400);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/payment",
                confirm(f, "1000.001", "ARS", proof),
                null,
                400);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/payment",
                confirm(f, "1001", "ARS", proof),
                null,
                409);
        var invalidMethod = confirm(f, "1000", "ARS", proof);
        invalidMethod.put("method", "SIMULATED");
        assertThat(
                        post(
                                        pharmacist,
                                        "/api/orders/" + f.order + "/payment",
                                        invalidMethod,
                                        null,
                                        400)
                                .get("code")
                                .asText())
                .isEqualTo("PAYMENT_METHOD_INVALID");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", f.order)).isZero();
        var first = confirm(f, "600", "ARS", proof);
        first.put("method", " transfer ");
        String key = UUID.randomUUID().toString();
        post(pharmacist, "/api/orders/" + f.order + "/payment", first, key, 200);
        post(pharmacist, "/api/orders/" + f.order + "/payment", first, key, 200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("ACCEPTED");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", f.order)).isEqualTo(1);
        assertThat(value("SELECT method FROM payment WHERE order_id=?", f.order))
                .isEqualTo("TRANSFER");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/prepare",
                Map.of("expectedVersion", version(f.order)),
                null,
                409);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/payment",
                confirm(f, "400", "ARS", proof),
                null,
                200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order)).isEqualTo("PAID");
        assertThat(value("SELECT sum(amount)::text FROM payment WHERE order_id=?", f.order))
                .isEqualTo("1000.00");
        assertThat(value("SELECT method FROM payment WHERE order_id=? AND amount=400", f.order))
                .isEqualTo("EXTERNAL_MANUAL");
    }

    @Test
    void proposedOrderCannotOpenFulfillmentIncident() throws Exception {
        Fixture f = fixture("PROPOSED", "PENDING", "PICKUP", false);
        post(
                buyer,
                "/api/orders/" + f.order + "/incident",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "kind",
                        "OTHER",
                        "reason",
                        "Consulta todavía sin presupuesto aceptado"),
                null,
                409);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("PENDING");
        assertThat(
                        count(
                                "SELECT count(*) FROM custody_event WHERE fulfillment_id=?",
                                f.fulfillment))
                .isZero();
    }

    @Test
    void cancellationStopsImmediatelyButHighValueRefundRequiresTwoCurrentPharmacists()
            throws Exception {
        Fixture f = fixture("PAID", "PENDING", "PICKUP", true);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/cancel",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "Solicitud de cancelación antes de preparación"),
                null,
                200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order)).isEqualTo("PAID");
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment p ON p.id=r.payment_id"
                                        + " WHERE p.order_id=?",
                                f.order))
                .isZero();
        assertThat(
                        value(
                                "SELECT snapshot->>'cancellationPending' FROM child_order WHERE"
                                        + " id=?",
                                f.order))
                .isEqualTo("true");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/prepare",
                Map.of("expectedVersion", version(f.order)),
                null,
                409);
        UUID controlProof = evidence(f.branch, "PAYMENT_RECEIPT");
        approve(f, "CANCEL", controlProof);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/cancel",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "Confirmación posterior a revisión de ambas cuentas"),
                null,
                200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("CANCELLED");
        UUID refund =
                uuid(
                        "SELECT r.id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE"
                                + " p.order_id=?",
                        f.order);
        UUID proof = evidence(f.branch, "PAYMENT_RECEIPT");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund",
                refund(f, refund, "1000", proof),
                null,
                409);
        approve(f, "REFUND", proof);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund",
                refund(f, refund, "900", proof),
                null,
                400);
        var body = refund(f, refund, "1000", proof);
        String key = UUID.randomUUID().toString();
        post(pharmacist, "/api/orders/" + f.order + "/refund", body, key, 200);
        post(pharmacist, "/api/orders/" + f.order + "/refund", body, key, 200);
        assertThat(value("SELECT status FROM refund WHERE id=?", refund)).isEqualTo("VERIFIED");
        assertThat(
                        count(
                                "SELECT count(*) FROM payment_operation WHERE payment_id=(SELECT id"
                                    + " FROM payment WHERE order_id=?) AND kind='REFUND_VERIFIED'",
                                f.order))
                .isEqualTo(1);
    }

    @Test
    void absentRecipientRequiresPhysicalReturnAndOnlyOneCoordinatedRetry() throws Exception {
        Fixture f = fixture("DISPATCHED", "PICKED_UP", "DELIVERY", false);
        incident(f, "RECIPIENT_ABSENT");
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("RETURN_REQUIRED");
        assertThat(value("SELECT custody_holder FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("DRIVER");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/assign-driver",
                Map.of("expectedVersion", version(f.order), "driverId", driverId),
                null,
                409);
        UUID proof = evidence(f.branch, "DELIVERY_PROOF");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/resolve-incident",
                retry(f, proof),
                null,
                409);
        post(
                operator,
                "/api/orders/" + f.order + "/return-received",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "custodyNote",
                        "Paquete cerrado recibido físicamente",
                        "evidenceDocumentId",
                        proof),
                null,
                403);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/return-received",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "custodyNote",
                        "Paquete cerrado recibido físicamente",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/resolve-incident",
                retry(f, proof),
                null,
                200);
        assertThat(count("SELECT retry_count FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo(1);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("ASSIGNED");
        assertThat(value("SELECT custody_holder FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("PHARMACY");
        sql(
                "UPDATE fulfillment SET"
                    + " status='PICKED_UP',custody_holder='DRIVER',driver_user_id=?,retry_scheduled_at=now()-interval"
                    + " '1 minute' WHERE id=?",
                driverId,
                f.fulfillment);
        sql("UPDATE child_order SET status='DISPATCHED' WHERE id=?", f.order);
        incident(f, "RECIPIENT_ABSENT");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/return-received",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "custodyNote",
                        "Segundo retorno físico; no se acordará otro despacho",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/resolve-incident",
                retry(f, proof),
                null,
                409);
        assertThat(
                        count(
                                "SELECT count(*) FROM stock_movement WHERE kind='RETURN' AND"
                                        + " branch_id=?",
                                f.branch))
                .isZero();
    }

    @Test
    void driverRejectsOnlyBeforeReceivingPackageAndTaskExpiryReleasesAssignment() throws Exception {
        Fixture f = fixture("DISPENSED", "ASSIGNED", "DELIVERY", false);
        post(
                driver,
                "/api/deliveries/" + f.fulfillment + "/reject",
                Map.of(
                        "expectedVersion",
                        fulfillmentVersion(f),
                        "reason",
                        "Imprevisto informado antes de recibir el paquete"),
                null,
                200);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("READY");
        sql(
                "UPDATE fulfillment SET"
                        + " status='ASSIGNED',driver_user_id=?,task_expires_at=now()-interval '1"
                        + " minute' WHERE id=?",
                driverId,
                f.fulfillment);
        fulfillment.expire(f.fulfillment);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("READY");
        assertThat(
                        count(
                                "SELECT count(*) FROM custody_event WHERE fulfillment_id=? AND"
                                        + " actor_user_id IS NULL AND snapshot->>'system'='true'",
                                f.fulfillment))
                .isEqualTo(1);
        sql(
                "UPDATE fulfillment SET status='PICKED_UP',custody_holder='DRIVER',driver_user_id=?"
                        + " WHERE id=?",
                driverId,
                f.fulfillment);
        post(
                driver,
                "/api/deliveries/" + f.fulfillment + "/reject",
                Map.of(
                        "expectedVersion",
                        fulfillmentVersion(f),
                        "reason",
                        "Paquete ya en mi poder"),
                null,
                409);
    }

    @Test
    void collectionRequiresObservedIdentityMatchingPackageAndCurrentAuthorization()
            throws Exception {
        Fixture f = fixture("DISPENSED", "READY", "PICKUP", false);
        UUID authDoc = evidence(f.branch, "FAMILY_AUTHORIZATION"), auth = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " family_authorization(id,beneficiary_id,buyer_user_id,document_id,scope,status,reviewed_by,reviewed_at)"
                    + " VALUES(?,?,?,?,'BUY_AND_RECEIVE','APPROVED',?,now())",
                auth,
                f.beneficiary,
                buyerId,
                authDoc,
                pharmacistId);
        sql("UPDATE purchase SET family_authorization_id=? WHERE id=?", auth, f.purchase);
        sql(
                "UPDATE fulfillment SET proof_code_hash=?,proof_expires_at=now()+interval '10"
                        + " minutes' WHERE id=?",
                IdempotencyService.hash(f.fulfillment + ":123456"),
                f.fulfillment);
        var body = receipt(f);
        body.put("authorizationDocumentId", authDoc);
        body.put("recipientIdentity", "99999999");
        post(pharmacist, "/api/orders/" + f.order + "/pickup-complete", body, null, 409);
        body.put("recipientIdentity", "28456781");
        body.put("packageMatches", false);
        post(pharmacist, "/api/orders/" + f.order + "/pickup-complete", body, null, 409);
        body.put("packageMatches", true);
        sql("UPDATE family_authorization SET status='REVOKED' WHERE id=?", auth);
        post(pharmacist, "/api/orders/" + f.order + "/pickup-complete", body, null, 409);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("READY");
        sql("UPDATE family_authorization SET status='APPROVED' WHERE id=?", auth);
        post(pharmacist, "/api/orders/" + f.order + "/pickup-complete", body, null, 200);
        assertThat(value("SELECT custody_holder FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("RECIPIENT");
    }

    @Test
    void pickupExpiryOpensIncidentWithoutRestockingOrInventingDelivery() throws Exception {
        Fixture f = fixture("DISPENSED", "READY", "PICKUP", false);
        sql(
                "UPDATE fulfillment SET pickup_expires_at=now()-interval '1 minute' WHERE id=?",
                f.fulfillment);
        fulfillment.expire(f.fulfillment);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("INCIDENT");
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("DISPENSED");
        assertThat(count("SELECT count(*) FROM stock_movement WHERE branch_id=?", f.branch))
                .isZero();
    }

    @Test
    void partialRefundDoesNotInvalidateCompletedBranchAndRequiresLinkedCreditNote()
            throws Exception {
        Fixture f = fixture("COMPLETED", "COLLECTED", "PICKUP", false);
        UUID proof = evidence(f.branch, "PAYMENT_RECEIPT");
        var request =
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "250.00",
                        "currency",
                        "ARS",
                        "reason",
                        "Compensación parcial acordada y documentada",
                        "evidenceDocumentId",
                        proof);
        post(pharmacist, "/api/orders/" + f.order + "/refund-request", request, null, 200);
        UUID refund =
                uuid(
                        "SELECT r.id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE"
                                + " p.order_id=?",
                        f.order);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund",
                refund(f, refund, "250", proof),
                null,
                409);
        UUID credit = evidence(f.branch, "INVOICE");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/fiscal-adjustment",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "kind",
                        "CREDIT",
                        "reference",
                        "NC-TEST-" + f.order,
                        "amount",
                        "250.00",
                        "currency",
                        "ARS",
                        "evidenceDocumentId",
                        credit,
                        "reason",
                        "Nota externa vinculada a compensación"),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund",
                refund(f, refund, "250", proof),
                null,
                200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("COMPLETED");
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("COLLECTED");
        assertThat(value("SELECT status FROM payment WHERE order_id=?", f.order))
                .isEqualTo("PARTIALLY_REFUNDED");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund-request",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "751",
                        "currency",
                        "ARS",
                        "reason",
                        "No puede superar cobro disponible",
                        "evidenceDocumentId",
                        proof),
                null,
                409);
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment p ON p.id=r.payment_id"
                                        + " WHERE p.order_id=?",
                                f.order))
                .isEqualTo(1);
    }

    @Test
    void highValuePhysicalDispatchRequiresTwoProfessionalsAndCurrentFingerprint() throws Exception {
        Fixture f = fixture("DISPENSED", "ACCEPTED", "DELIVERY", true);
        var pickup =
                Map.of(
                        "expectedVersion",
                        fulfillmentVersion(f),
                        "custodyNote",
                        "Paquete cerrado comprobado");
        post(driver, "/api/deliveries/" + f.fulfillment + "/pickup", pickup, null, 409);
        UUID proof = evidence(f.branch, "DELIVERY_PROOF");
        approve(f, "DISPATCH", proof);
        post(
                driver,
                "/api/deliveries/" + f.fulfillment + "/pickup",
                Map.of(
                        "expectedVersion",
                        fulfillmentVersion(f),
                        "custodyNote",
                        "Paquete cerrado comprobado"),
                null,
                200);
        assertThat(value("SELECT custody_holder FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("DRIVER");
    }

    @Test
    void cancellingOneSplitOrderPreservesOtherCompletedOrder() throws Exception {
        Fixture f = fixture("PAID", "PENDING", "PICKUP", false);
        UUID otherBranch = UUID.randomUUID(),
                otherOrder = UUID.randomUUID(),
                otherFulfillment = UUID.randomUUID();
        sql(
                "INSERT INTO branch(id,name,address,latitude,longitude) VALUES(?,'Otra farmacia"
                        + " aislada','Dirección de prueba',-32.8,-68.8)",
                otherBranch);
        sql(
                "INSERT INTO child_order(id,purchase_id,branch_id,status,total)"
                        + " VALUES(?,?,?,'COMPLETED',900)",
                otherOrder,
                f.purchase,
                otherBranch);
        sql(
                "INSERT INTO fulfillment(id,order_id,branch_id,mode,status,custody_holder)"
                        + " VALUES(?,?,?,'PICKUP','COLLECTED','RECIPIENT')",
                otherFulfillment,
                otherOrder,
                otherBranch);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/cancel",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "La sucursal afectada resuelve sólo su pedido"),
                null,
                200);
        assertThat(value("SELECT status FROM child_order WHERE id=?", otherOrder))
                .isEqualTo("COMPLETED");
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", otherFulfillment))
                .isEqualTo("COLLECTED");
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment p ON p.id=r.payment_id"
                                        + " WHERE p.order_id=?",
                                otherOrder))
                .isZero();
    }

    @Test
    void partialRefundByLinesCannotExceedSelectedLinesAndIsIdempotent() throws Exception {
        Fixture f = fixture("COMPLETED", "COLLECTED", "PICKUP", false);
        UUID line = addLine(f, 250), proof = evidence(f.branch, "PAYMENT_RECEIPT");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund-request",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "251",
                        "currency",
                        "ARS",
                        "reason",
                        "No puede superar la línea seleccionada",
                        "evidenceDocumentId",
                        proof,
                        "lineIds",
                        List.of(line)),
                null,
                409);
        var request =
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "250",
                        "currency",
                        "ARS",
                        "reason",
                        "Devolución sólo de la línea individual documentada",
                        "evidenceDocumentId",
                        proof,
                        "lineIds",
                        List.of(line));
        String key = UUID.randomUUID().toString();
        post(pharmacist, "/api/orders/" + f.order + "/refund-request", request, key, 200);
        post(pharmacist, "/api/orders/" + f.order + "/refund-request", request, key, 200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund-request",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "1",
                        "currency",
                        "ARS",
                        "reason",
                        "No puede repetir el saldo agotado de la línea",
                        "evidenceDocumentId",
                        proof,
                        "lineIds",
                        List.of(line)),
                null,
                409);
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment p ON p.id=r.payment_id"
                                        + " WHERE p.order_id=?",
                                f.order))
                .isEqualTo(1);
    }

    @Test
    void addingRefundInvalidatesOldHighValueApprovalWithoutChangingDelivery() throws Exception {
        Fixture f = fixture("COMPLETED", "COLLECTED", "PICKUP", true);
        UUID proof = evidence(f.branch, "PAYMENT_RECEIPT");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund-request",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "250",
                        "currency",
                        "ARS",
                        "reason",
                        "Primer importe autorizado",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        UUID first =
                uuid(
                        "SELECT r.id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE"
                                + " p.order_id=?",
                        f.order);
        approve(f, "REFUND", proof);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund-request",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "amount",
                        "250",
                        "currency",
                        "ARS",
                        "reason",
                        "Un importe adicional necesita otra revisión",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/refund",
                refund(f, first, "250", proof),
                null,
                409);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order))
                .isEqualTo("COMPLETED");
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment p ON p.id=r.payment_id"
                                        + " WHERE p.order_id=? AND r.status='VERIFIED'",
                                f.order))
                .isZero();
    }

    @Test
    void rejectedTaskAutomaticallySelectsDifferentEligibleOwnDriver() throws Exception {
        Fixture f = fixture("DISPENSED", "ASSIGNED", "DELIVERY", false);
        UUID replacement = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " app_user(id,email,name,password_hash,birth_date,email_verified,email_verified_at)"
                    + " SELECT ?,?,'Cadete aislado de"
                    + " reemplazo',password_hash,'1990-03-10',true,now() FROM app_user WHERE id=?",
                replacement,
                "replacement-" + replacement + "@demo.invalid",
                driverId);
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'DRIVER')", replacement);
        sql(
                "INSERT INTO membership(id,user_id,branch_id,role,capabilities)"
                        + " VALUES(?,?,?,'DRIVER','{\"capacity\":1,\"available\":true}')",
                UUID.randomUUID(),
                replacement,
                f.branch);
        post(
                driver,
                "/api/deliveries/" + f.fulfillment + "/reject",
                Map.of(
                        "expectedVersion",
                        fulfillmentVersion(f),
                        "reason",
                        "Rechazo previo a la recepción física"),
                null,
                200);
        assertThat(uuid("SELECT driver_user_id FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo(replacement);
        assertThat(value("SELECT status FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("ASSIGNED");
        assertThat(value("SELECT custody_holder FROM fulfillment WHERE id=?", f.fulfillment))
                .isEqualTo("PHARMACY");
    }

    @Test
    void fiscalFailureStaysPrioritaryAcrossManualRetryUntilValidInvoiceIsAttached()
            throws Exception {
        Fixture f = fixture("PAID", "PENDING", "PICKUP", false);
        UUID proof = evidence(f.branch, "OTHER");
        var failure =
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "La emisión externa no devolvió comprobante; fallo documentado por la"
                            + " farmacia",
                        "evidenceDocumentId",
                        proof);
        String key = UUID.randomUUID().toString();
        post(operator, "/api/orders/" + f.order + "/invoice-issue", failure, key, 200);
        post(operator, "/api/orders/" + f.order + "/invoice-issue", failure, key, 200);
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'active' FROM child_order WHERE"
                                    + " id=?",
                                f.order))
                .isEqualTo("true");
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'priority' FROM child_order"
                                    + " WHERE id=?",
                                f.order))
                .isEqualTo("HIGH");
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'attemptCount' FROM child_order"
                                    + " WHERE id=?",
                                f.order))
                .isEqualTo("1");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/invoice-issue",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "Reintento manual también rechazado por el emisor externo; conserva alerta",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'attemptCount' FROM child_order"
                                    + " WHERE id=?",
                                f.order))
                .isEqualTo("2");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/invoice",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reference",
                        "FACTURA-" + f.order,
                        "evidenceDocumentId",
                        proof),
                null,
                403);
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'active' FROM child_order WHERE"
                                    + " id=?",
                                f.order))
                .isEqualTo("true");
        UUID invoice = evidence(f.branch, "INVOICE");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/invoice",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reference",
                        "FACTURA-" + f.order,
                        "evidenceDocumentId",
                        invoice),
                null,
                200);
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'active' FROM child_order WHERE"
                                    + " id=?",
                                f.order))
                .isEqualTo("false");
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'status' FROM child_order WHERE"
                                    + " id=?",
                                f.order))
                .isEqualTo("RESOLVED");
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'manualRetryRequired' FROM"
                                    + " child_order WHERE id=?",
                                f.order))
                .isEqualTo("false");
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'invoiceDocumentId' FROM"
                                    + " child_order WHERE id=?",
                                f.order))
                .isEqualTo(invoice.toString());
        assertThat(
                        value(
                                "SELECT snapshot->'invoiceIssue'->>'evidenceDocumentId' FROM"
                                    + " child_order WHERE id=?",
                                f.order))
                .isEqualTo(proof.toString());
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                    + " action='EXTERNAL_INVOICE_ISSUE_REPORTED'",
                                f.order))
                .isEqualTo(2);
        assertThat(value("SELECT status FROM child_order WHERE id=?", f.order)).isEqualTo("PAID");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/invoice-issue",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reason",
                        "Una alerta no puede invalidar la factura ya existente",
                        "evidenceDocumentId",
                        proof),
                null,
                409);
        assertThat(uuid("SELECT invoice_document_id FROM child_order WHERE id=?", f.order))
                .isEqualTo(invoice);
    }

    @Test
    void fiscalFailureRequiresOwnOperationalEvidenceAndCannotReissueReplacementInvoice()
            throws Exception {
        Fixture f = fixture("PAID", "PENDING", "PICKUP", false);
        UUID proof = evidence(f.branch, "OTHER");
        var failure =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "expectedVersion",
                                version(f.order),
                                "reason",
                                "Falla de emisión externa comprobada",
                                "evidenceDocumentId",
                                proof));
        post(buyer, "/api/orders/" + f.order + "/invoice-issue", failure, null, 403);
        post(driver, "/api/orders/" + f.order + "/invoice-issue", failure, null, 403);
        Fixture unrelated = fixture("PAID", "PENDING", "PICKUP", false);
        failure.put("evidenceDocumentId", evidence(unrelated.branch, "OTHER"));
        post(pharmacist, "/api/orders/" + f.order + "/invoice-issue", failure, null, 403);
        failure.put("evidenceDocumentId", evidence(f.branch, "PAYMENT_RECEIPT"));
        post(pharmacist, "/api/orders/" + f.order + "/invoice-issue", failure, null, 403);
        sql(
                "INSERT INTO"
                    + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id)"
                    + " VALUES(?,?,?,'COVERAGE','APPROVED',?,'Autorización clínica histórica',?)",
                UUID.randomUUID(),
                f.order,
                f.branch,
                pharmacistId,
                proof);
        failure.put("evidenceDocumentId", proof);
        post(pharmacist, "/api/orders/" + f.order + "/invoice-issue", failure, null, 403);
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE id=? AND"
                                    + " snapshot->'invoiceIssue' IS NOT NULL",
                                f.order))
                .isZero();
        Fixture original = fixture("DISPENSED", "INCIDENT", "PICKUP", false);
        UUID request = UUID.randomUUID(),
                purchase = UUID.randomUUID(),
                order = UUID.randomUUID(),
                operational = evidence(original.branch, "OTHER");
        sql(
                "INSERT INTO"
                    + " purchase(id,buyer_user_id,beneficiary_id,mode,latitude,longitude,status,total,snapshot)"
                    + " VALUES(?,?,?,'PICKUP',-32.889,-68.845,'PAID',0,cast(? as jsonb))",
                purchase,
                buyerId,
                original.beneficiary,
                json.writeValueAsString(Map.of("replacementRequestId", request)));
        UUID invoice =
                uuid("SELECT invoice_document_id FROM child_order WHERE id=?", original.order);
        sql(
                "INSERT INTO"
                    + " child_order(id,purchase_id,branch_id,status,total,invoice_document_id,snapshot)"
                    + " VALUES(?,?,?,'PAID',0,?,cast(? as jsonb))",
                order,
                purchase,
                original.branch,
                invoice,
                json.writeValueAsString(Map.of("replacementRequestId", request)));
        sql(
                "INSERT INTO"
                    + " replacement_request(id,original_order_id,original_fulfillment_id,buyer_user_id,branch_id,replacement_purchase_id,replacement_order_id,status,original_disposition,reason,disposition_note,evidence_document_id,proposed_by,expires_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?,?,'ACCEPTED','NO_RETURN_DOCUMENTED','Reposición sin"
                    + " cargo','Paquete original documentado',?,?,now()+interval '1 hour','{}')",
                request,
                original.order,
                original.fulfillment,
                buyerId,
                original.branch,
                purchase,
                order,
                operational,
                pharmacistId);
        assertThat(
                        post(
                                        pharmacist,
                                        "/api/orders/" + order + "/invoice-issue",
                                        Map.of(
                                                "expectedVersion",
                                                version(order),
                                                "reason",
                                                "No puede iniciar otra emisión sobre la reposición",
                                                "evidenceDocumentId",
                                                operational),
                                        null,
                                        409)
                                .get("code")
                                .asText())
                .isEqualTo("REPLACEMENT_ORIGINAL_INVOICE");
        post(
                pharmacist,
                "/api/orders/" + order + "/invoice",
                Map.of(
                        "expectedVersion",
                        version(order),
                        "reference",
                        "REEMISION-INVALIDA",
                        "evidenceDocumentId",
                        evidence(original.branch, "INVOICE")),
                null,
                409);
        assertThat(uuid("SELECT invoice_document_id FROM child_order WHERE id=?", order))
                .isEqualTo(invoice);
        Fixture completed = fixture("COMPLETED", "COLLECTED", "PICKUP", false);
        post(
                pharmacist,
                "/api/orders/" + completed.order + "/invoice-issue",
                Map.of(
                        "expectedVersion",
                        version(completed.order),
                        "reason",
                        "Fuera de la emisión previa a entrega",
                        "evidenceDocumentId",
                        evidence(completed.branch, "OTHER")),
                null,
                409);
    }

    @Test
    void reconciliationPreservesActualMethodAndRejectsAnUnknownMethodBeforeRecordingFunds()
            throws Exception {
        Fixture f = fixture("ACCEPTED", "PENDING", "PICKUP", false);
        UUID report = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " document(id,owner_user_id,kind,original_name,content_type,size_bytes,storage_key,sha256)"
                    + " VALUES(?,?,'PAYMENT_RECEIPT','pago-informado-test.pdf','application/pdf',10,?,?)",
                report,
                buyerId,
                "method-test:" + report,
                IdempotencyService.hash(report.toString()));
        post(
                buyer,
                "/api/orders/" + f.order + "/payment-report",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reference",
                        "INGRESO-PENDIENTE-" + f.order,
                        "evidenceDocumentId",
                        report),
                null,
                200);
        post(
                buyer,
                "/api/purchases/" + f.purchase + "/cancel",
                Map.of(
                        "expectedVersion",
                        count("SELECT version FROM purchase WHERE id=?", f.purchase),
                        "reason",
                        "Cancelación anterior a confirmación bancaria"),
                null,
                200);
        var reconciliation =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "expectedVersion",
                                version(f.order),
                                "decision",
                                "RECEIVED",
                                "reference",
                                "INGRESO-CONCILIADO-" + f.order,
                                "evidenceDocumentId",
                                evidence(f.branch, "PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true,
                                "reason",
                                "Ingreso real comprobado después de cancelación",
                                "amount",
                                "600.00",
                                "currency",
                                "ARS",
                                "method",
                                "SIMULATED"));
        assertThat(
                        post(
                                        pharmacist,
                                        "/api/orders/" + f.order + "/reconcile-payment",
                                        reconciliation,
                                        null,
                                        400)
                                .get("code")
                                .asText())
                .isEqualTo("PAYMENT_METHOD_INVALID");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", f.order)).isZero();
        reconciliation.put("method", "card");
        post(
                pharmacist,
                "/api/orders/" + f.order + "/reconcile-payment",
                reconciliation,
                null,
                200);
        assertThat(value("SELECT method FROM payment WHERE order_id=?", f.order)).isEqualTo("CARD");
        assertThat(value("SELECT amount::text FROM payment WHERE order_id=?", f.order))
                .isEqualTo("600.00");
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                    + " action='CANCELLED_PAYMENT_RECONCILED' AND"
                                    + " after_data->>'method'='CARD'",
                                f.order))
                .isEqualTo(1);
        assertThat(
                        value(
                                "SELECT r.amount::text FROM refund r JOIN payment p ON"
                                    + " p.id=r.payment_id WHERE p.order_id=?",
                                f.order))
                .isEqualTo("600.00");
    }

    private UUID addLine(Fixture f, int price) {
        UUID product = UUID.randomUUID(),
                item = UUID.randomUUID(),
                line = UUID.randomUUID(),
                orderItem = UUID.randomUUID();
        sql(
                "INSERT INTO product(id,code,name,presentation) VALUES(?,?, 'Producto aislado para"
                        + " distribución de reintegro','Envase aislado')",
                product,
                "TEST-" + product);
        sql(
                "INSERT INTO purchase_item(id,purchase_id,product_id,quantity) VALUES(?,?,?,1)",
                item,
                f.purchase,
                product);
        UUID quote = uuid("SELECT accepted_quote_id FROM purchase WHERE id=?", f.purchase);
        sql(
                "INSERT INTO"
                    + " quote_line(id,quote_id,order_id,purchase_item_id,product_id,quantity,unit_price,total)"
                    + " VALUES(?,?,?,?,?,1,?,?)",
                line,
                quote,
                f.order,
                item,
                product,
                price,
                price);
        sql(
                "INSERT INTO"
                    + " order_item(id,order_id,purchase_item_id,product_id,quantity,quote_line_id,snapshot)"
                    + " VALUES(?,?,?,?,1,?,cast(? as jsonb))",
                orderItem,
                f.order,
                item,
                product,
                line,
                json.writeValueAsString(Map.of("unitPrice", price, "coverageAmount", 0)));
        return orderItem;
    }

    private Fixture fixture(String orderState, String fulfillmentState, String mode, boolean high) {
        UUID branch = UUID.randomUUID(),
                beneficiary = UUID.randomUUID(),
                purchase = UUID.randomUUID(),
                order = UUID.randomUUID(),
                f = UUID.randomUUID(),
                quote = UUID.randomUUID();
        String name = value("SELECT name FROM app_user WHERE id=?", buyerId);
        sql(
                "INSERT INTO"
                    + " branch(id,name,address,latitude,longitude,verified,agreement_accepted,driver_available)"
                    + " VALUES(?,'Farmacia aislada para prueba transaccional','Dirección de"
                    + " prueba',-32.889,-68.845,true,true,true)",
                branch);
        for (var entry :
                Map.of(
                                pharmacistId,
                                "PHARMACIST",
                                secondId,
                                "PHARMACIST",
                                driverId,
                                "DRIVER",
                                operatorId,
                                "PHARMACY")
                        .entrySet())
            sql(
                    "INSERT INTO membership(id,user_id,branch_id,role,capabilities)"
                            + " VALUES(?,?,?,?,'{\"capacity\":20,\"available\":true}')",
                    UUID.randomUUID(),
                    entry.getKey(),
                    branch,
                    entry.getValue());
        sql(
                "INSERT INTO beneficiary(id,owner_user_id,name,birth_date,identity_reference)"
                        + " VALUES(?,?,?,'1985-04-15','28456781')",
                beneficiary,
                buyerId,
                name);
        String parent =
                "ACCEPTED".equals(orderState)
                        ? "ACCEPTED"
                        : "PROPOSED".equals(orderState)
                                ? "QUOTED"
                                : "COMPLETED".equals(orderState) ? "COMPLETED" : "FULFILLING";
        sql(
                "INSERT INTO"
                    + " purchase(id,buyer_user_id,beneficiary_id,mode,latitude,longitude,status,total,snapshot)"
                    + " VALUES(?,?,?,?,-32.889,-68.845,?,1000,cast(? as jsonb))",
                purchase,
                buyerId,
                beneficiary,
                mode,
                parent,
                json.writeValueAsString(
                        Map.of("receiverName", name, "receiverIdentity", "28456781")));
        sql(
                "INSERT INTO"
                    + " quote(id,purchase_id,version,status,subtotal,total,expires_at,accepted_by,accepted_at)"
                    + " VALUES(?,?,1,'ACCEPTED',1000,1000,now()+interval '1 hour',?,now())",
                quote,
                purchase,
                buyerId);
        sql("UPDATE purchase SET accepted_quote_id=? WHERE id=?", quote, purchase);
        sql(
                "INSERT INTO"
                    + " child_order(id,purchase_id,branch_id,status,total,accepted_quote_id,snapshot)"
                    + " VALUES(?,?,?,?,1000,?,cast(? as jsonb))",
                order,
                purchase,
                branch,
                orderState,
                quote,
                json.writeValueAsString(Map.of("reviewApproved", true, "highValueRequired", high)));
        String holder =
                "PICKED_UP".equals(fulfillmentState)
                        ? "DRIVER"
                        : Set.of("DELIVERED", "COLLECTED").contains(fulfillmentState)
                                ? "RECIPIENT"
                                : "PHARMACY";
        UUID assigned =
                Set.of("ASSIGNED", "ACCEPTED", "PICKED_UP").contains(fulfillmentState)
                        ? driverId
                        : null;
        sql(
                "INSERT INTO"
                    + " fulfillment(id,order_id,branch_id,mode,status,driver_user_id,receiver_name,receiver_identity,custody_holder,task_expires_at,pickup_expires_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?,?,'28456781',?,now()+interval '15"
                    + " minutes',now()+interval '48"
                    + " hours','{\"config\":{\"taskAcceptanceMinutes\":15,\"pickupDeadlineHours\":48,\"receiptCodeMinutes\":10}}')",
                f,
                order,
                branch,
                mode,
                fulfillmentState,
                assigned,
                name,
                holder);
        if (!Set.of("ACCEPTED", "PROPOSED").contains(orderState))
            sql(
                    "INSERT INTO"
                        + " payment(id,order_id,branch_id,amount,currency,status,external_reference,verified_by,verified_at)"
                        + " VALUES(?,?,?,1000,'ARS','VERIFIED',?,?,now())",
                    UUID.randomUUID(),
                    order,
                    branch,
                    "TEST-PAID-" + order,
                    pharmacistId);
        if (Set.of("DISPENSED", "DISPATCHED", "COMPLETED").contains(orderState))
            sql(
                    "UPDATE child_order SET invoice_document_id=? WHERE id=?",
                    evidence(branch, "INVOICE"),
                    order);
        fixtureFulfillments.add(f);
        return new Fixture(branch, beneficiary, purchase, order, f);
    }

    private Map<String, Object> confirm(Fixture f, String amount, String currency, UUID proof) {
        return new LinkedHashMap<>(
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "reference",
                        "TEST-TRANSFER-" + UUID.randomUUID(),
                        "evidenceDocumentId",
                        proof,
                        "verifiedExternally",
                        true,
                        "amount",
                        amount,
                        "currency",
                        currency));
    }

    private Map<String, Object> refund(Fixture f, UUID refund, String amount, UUID proof) {
        return Map.of(
                "expectedVersion",
                version(f.order),
                "refundId",
                refund,
                "reference",
                "TEST-REFUND-" + UUID.randomUUID(),
                "evidenceDocumentId",
                proof,
                "verifiedExternally",
                true,
                "amount",
                amount,
                "currency",
                "ARS");
    }

    private Map<String, Object> retry(Fixture f, UUID proof) {
        return Map.of(
                "expectedVersion",
                version(f.order),
                "decision",
                "RETRY",
                "reason",
                "Paquete apto bajo revisión farmacéutica",
                "evidenceDocumentId",
                proof,
                "retryScheduledAt",
                java.time.Instant.now().plusSeconds(3600).toString(),
                "coordinationNote",
                "Receptor confirmó un único nuevo intento y franja horaria");
    }

    private Map<String, Object> receipt(Fixture f) {
        return new LinkedHashMap<>(
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "code",
                        "123456",
                        "recipientVerified",
                        true,
                        "packageMatches",
                        true,
                        "note",
                        "Identidad presencial y paquete coinciden con la compra",
                        "recipientName",
                        value("SELECT receiver_name FROM fulfillment WHERE id=?", f.fulfillment),
                        "recipientIdentity",
                        "28456781"));
    }

    private void incident(Fixture f, String kind) throws Exception {
        post(
                driver,
                "/api/orders/" + f.order + "/incident",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "kind",
                        kind,
                        "reason",
                        "No se encontró al receptor en el domicilio coordinado"),
                null,
                200);
    }

    private void approve(Fixture f, String operation, UUID proof) throws Exception {
        post(
                pharmacist,
                "/api/orders/" + f.order + "/financial-control",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "operation",
                        operation,
                        "reason",
                        "Primera revisión profesional de hechos y comprobantes",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
        post(
                pharmacist,
                "/api/orders/" + f.order + "/financial-control",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "operation",
                        operation,
                        "reason",
                        "La misma cuenta no puede aprobar dos veces",
                        "evidenceDocumentId",
                        proof),
                null,
                409);
        post(
                second,
                "/api/orders/" + f.order + "/financial-control",
                Map.of(
                        "expectedVersion",
                        version(f.order),
                        "operation",
                        operation,
                        "reason",
                        "Segunda revisión independiente de hechos y comprobantes",
                        "evidenceDocumentId",
                        proof),
                null,
                200);
    }

    private UUID evidence(UUID branch, String kind) {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " document(id,owner_user_id,branch_id,kind,original_name,content_type,size_bytes,storage_key,sha256)"
                    + " VALUES(?,?,?,?,?,'application/pdf',10,?,?)",
                id,
                pharmacistId,
                branch,
                kind,
                "evidencia-aislada-test.pdf",
                "test:" + id,
                "a".repeat(64));
        return id;
    }

    private long version(UUID order) {
        return count("SELECT version FROM child_order WHERE id=?", order);
    }

    private long fulfillmentVersion(Fixture f) {
        return count("SELECT version FROM fulfillment WHERE id=?", f.fulfillment);
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
                                                    "127.3."
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

    record Fixture(UUID branch, UUID beneficiary, UUID purchase, UUID order, UUID fulfillment) {}
}
