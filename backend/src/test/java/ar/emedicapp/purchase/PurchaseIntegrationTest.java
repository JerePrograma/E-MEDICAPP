package ar.emedicapp.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;
import ar.emedicapp.payments.PaymentService;

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

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class PurchaseIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired PaymentService payments;
    @Autowired PurchaseService purchases;
    private MockMvc mvc;
    private Session buyer, pharmacist;
    private UUID branch, product;

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
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        buyer = login("comprador@demo.invalid");
        pharmacist = login("farmaceutico@demo.invalid");
        branch = UUID.randomUUID();
        product = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " branch(id,name,address,latitude,longitude,verified,agreement_accepted,pickup_enabled)"
                    + " VALUES(?,'Sucursal aislada test','Dirección ficticia"
                    + " test',-32.889,-68.845,true,true,true)",
                branch);
        sql(
                "INSERT INTO product(id,code,name,presentation) VALUES(?,?, 'Producto aislado"
                        + " test','Envase test')",
                product,
                "TEST-" + product);
        sql(
                "INSERT INTO branch_stock(branch_id,product_id,quantity,price) VALUES(?,?,1,1500)",
                branch,
                product);
        membership(DemoIds.id("user:farmaceutico@demo.invalid"), branch, "PHARMACIST");
        UUID plan = UUID.randomUUID(), contract = UUID.randomUUID();
        sql(
                "INSERT INTO plan(id,name,min_branches,monthly_price,currency) VALUES(?,'Plan"
                        + " aislado test',1,1,'ARS')",
                plan);
        sql(
                "INSERT INTO"
                    + " subscription_contract(id,plan_id,branch_id,monthly_price,currency,status)"
                    + " VALUES(?,?,?,1,'ARS','ACTIVE')",
                contract,
                plan,
                branch);
        sql("INSERT INTO branch_subscription(contract_id,branch_id) VALUES(?,?)", contract, branch);
        sql(
                "INSERT INTO"
                    + " subscription_period(id,contract_id,starts_at,ends_at,amount,currency,status,verified_by,verified_at,payment_reference)"
                    + " VALUES(?,?,now()-interval '1 day',now()+interval '1"
                    + " day',1,'ARS','PAID',?,now(),?)",
                UUID.randomUUID(),
                contract,
                DemoIds.id("user:admin@demo.invalid"),
                "TEST-" + contract);
    }

    @Test
    void acceptIsDurableIdempotentAndWrongConsentCannotReserve() throws Exception {
        JsonNode p = quoted();
        UUID orderId = UUID.fromString(p.get("orders").get(0).get("id").asText());
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                        + " status='HELD'",
                                orderId))
                .isEqualTo(1);
        UUID reservation =
                db.sql("SELECT id FROM reservation WHERE order_id=? AND status='HELD'")
                        .params(orderId)
                        .query(UUID.class)
                        .single();
        String id = p.get("id").asText(), key = UUID.randomUUID().toString();
        var request = Map.of("expectedVersion", p.get("version").asLong());
        var first = post(buyer, "/api/purchases/" + id + "/accept", request, key, 200);
        var second = post(buyer, "/api/purchases/" + id + "/accept", request, key, 200);
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(
                        db.sql("SELECT id FROM reservation WHERE order_id=? AND status='HELD'")
                                .params(orderId)
                                .query(UUID.class)
                                .single())
                .isEqualTo(reservation);
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=?",
                                UUID.fromString(first.get("orders").get(0).get("id").asText())))
                .isEqualTo(1);
        post(buyer, "/api/purchases/" + id + "/accept", Map.of("expectedVersion", 999), key, 409);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
        sql(
                "UPDATE product SET name='Nombre nuevo del catálogo',presentation='Presentación"
                        + " nueva' WHERE id=?",
                product);
        sql("UPDATE branch SET name='Nombre nuevo sucursal' WHERE id=?", branch);
        var history = get(buyer, "/api/purchases/" + id);
        assertThat(history.get("orders").get(0).get("lines").get(0).get("name").asText())
                .isEqualTo("Producto aislado test");
        assertThat(history.get("orders").get(0).get("branchName").asText())
                .isEqualTo("Sucursal aislada test");
    }

    @Test
    void changedCatalogPriceRequiresNewExplicitConsent() throws Exception {
        JsonNode p = quoted();
        sql(
                "UPDATE branch_stock SET price=1700,version=version+1 WHERE branch_id=? AND"
                        + " product_id=?",
                branch,
                product);
        var body =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        409);
        assertThat(body.get("code").asText()).isEqualTo("QUOTE_CHANGED");
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void unpaidExpiryReleasesStockAndRecordsSystemWithoutInventingActor() throws Exception {
        var p = quoted();
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        UUID purchase = UUID.fromString(p.get("id").asText());
        sql(
                "UPDATE reservation SET expires_at=now()-interval '1 minute' WHERE branch_id=? AND"
                        + " product_id=?",
                branch,
                product);
        assertThat(payments.expirePurchase(purchase)).isTrue();
        assertThat(payments.expirePurchase(purchase)).isFalse();
        assertThat(get(buyer, "/api/purchases/" + purchase).get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                        + " action='SYSTEM_RESERVATION_EXPIRY' AND actor_user_id IS"
                                        + " NULL",
                                purchase))
                .isEqualTo(1);
    }

    @Test
    void expiredQuoteReleasesOnceAndRequiresNewQuoteWithoutDuplicatingHeldStock() throws Exception {
        var p = quoted();
        UUID purchase = UUID.fromString(p.get("id").asText());
        sql(
                "UPDATE quote SET expires_at=now()-interval '1 minute' WHERE purchase_id=? AND"
                        + " status='ISSUED'",
                purchase);
        assertThat(purchases.expireQuote(purchase)).isTrue();
        assertThat(purchases.expireQuote(purchase)).isFalse();
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                        + " action='SYSTEM_QUOTE_EXPIRY' AND actor_user_id IS NULL",
                                purchase))
                .isEqualTo(1);
        p = get(buyer, "/api/purchases/" + purchase);
        assertThat(p.get("status").asText()).isEqualTo("UNAVAILABLE");
        p =
                post(
                        buyer,
                        "/api/purchases/" + purchase + "/reroute",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        var order = p.get("orders").get(0);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order.get("id").asText() + "/review",
                        review(order.get("version").asLong()),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("QUOTED");
        assertThat(
                        count(
                                "SELECT count(*) FROM quote WHERE purchase_id=? AND"
                                        + " status='ISSUED'",
                                purchase))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation r JOIN child_order o ON"
                                    + " o.id=r.order_id WHERE o.purchase_id=? AND r.status='HELD'",
                                purchase))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void clinicalRejectionDoesNotSeekAnotherPharmacyAndSamePrescriptionHashStaysBlocked()
            throws Exception {
        sql("UPDATE product SET requires_prescription=true WHERE id=?", product);
        UUID alternate = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " branch(id,name,address,latitude,longitude,verified,agreement_accepted,pickup_enabled)"
                    + " VALUES(?,'Alternativa sanitaria"
                    + " test','Ficticia',-32.89,-68.85,true,true,true)",
                alternate);
        sql(
                "INSERT INTO branch_stock(branch_id,product_id,quantity,price) VALUES(?,?,1,1500)",
                alternate,
                product);
        UUID original = buyerEvidence("PRESCRIPTION");
        var request =
                new HashMap<>(createRequest(List.of(Map.of("productId", product, "quantity", 1))));
        request.put("prescriptionDocumentId", original);
        var p = post(buyer, "/api/purchases", request, null, 200);
        UUID purchase = UUID.fromString(p.get("id").asText());
        var order = p.get("orders").get(0);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order.get("id").asText() + "/review",
                        Map.of(
                                "expectedVersion",
                                order.get("version").asLong(),
                                "decision",
                                "REJECT",
                                "rejectionKind",
                                "CLINICAL",
                                "reason",
                                "La versión de receta no acredita una indicación utilizable;"
                                        + " solicitar original corregido"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM child_order WHERE purchase_id=?", purchase))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM routing_attempt WHERE purchase_id=?", purchase))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation r JOIN child_order o ON"
                                    + " o.id=r.order_id WHERE o.purchase_id=? AND r.status='HELD'",
                                purchase))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM prescription WHERE document_id=? AND"
                                        + " status='REJECTED'",
                                original))
                .isEqualTo(1);
        UUID duplicate = buyerEvidence("PRESCRIPTION");
        sql(
                "UPDATE document SET sha256=(SELECT sha256 FROM document WHERE id=?) WHERE id=?",
                original,
                duplicate);
        request.put("prescriptionDocumentId", duplicate);
        var rejected = post(buyer, "/api/purchases", request, null, 409);
        assertThat(rejected.get("code").asText()).isEqualTo("PRESCRIPTION_BLOCKED");
        assertThat(count("SELECT count(*) FROM prescription WHERE document_id=?", duplicate))
                .isZero();
    }

    @Test
    void receiverWithSameNameAndDifferentIdentityStillNeedsAnAuthorization() throws Exception {
        var request =
                new HashMap<>(createRequest(List.of(Map.of("productId", product, "quantity", 1))));
        request.put("receiverIdentity", "88776655");
        assertThat(post(buyer, "/api/purchases", request, null, 400).get("code").asText())
                .isEqualTo("RECEIVER_NAME_REQUIRED");
        request.put("receiverName", request.get("beneficiaryName"));
        assertThat(post(buyer, "/api/purchases", request, null, 400).get("code").asText())
                .isEqualTo("AUTHORIZATION_REQUIRED");
    }

    @Test
    void familyAuthorizationIsPerPurchaseAndRevocationBlocksAcceptance() throws Exception {
        UUID note = buyerEvidence("FAMILY_AUTHORIZATION");
        var request =
                new HashMap<>(createRequest(List.of(Map.of("productId", product, "quantity", 1))));
        request.put("thirdParty", true);
        request.put("beneficiaryName", "María Torres");
        request.put("beneficiaryBirthDate", "1960-06-15");
        request.put("beneficiaryIdentity", "14222333");
        request.put("authorizationDocumentId", note);
        var p = post(buyer, "/api/purchases", request, null, 200);
        UUID purchase = UUID.fromString(p.get("id").asText());
        assertThat(
                        count(
                                "SELECT count(*) FROM family_authorization fa JOIN purchase p ON"
                                        + " p.family_authorization_id=fa.id WHERE p.id=? AND"
                                        + " fa.scope='BUY_AND_RECEIVE' AND"
                                        + " fa.receiver_identity='14222333'",
                                purchase))
                .isEqualTo(1);
        assertThat(post(buyer, "/api/purchases", request, null, 409).get("code").asText())
                .isEqualTo("AUTHORIZATION_ALREADY_USED");
        UUID sameContent = buyerEvidence("FAMILY_AUTHORIZATION");
        sql(
                "UPDATE document SET sha256=(SELECT sha256 FROM document WHERE id=?) WHERE id=?",
                note,
                sameContent);
        request.put("authorizationDocumentId", sameContent);
        assertThat(post(buyer, "/api/purchases", request, null, 409).get("code").asText())
                .isEqualTo("AUTHORIZATION_ALREADY_USED");
        var order = p.get("orders").get(0);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order.get("id").asText() + "/review",
                        review(order.get("version").asLong()),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("QUOTED");
        p =
                post(
                        buyer,
                        "/api/purchases/" + purchase + "/authorization/revoke",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        assertThat(
                        post(
                                        buyer,
                                        "/api/purchases/" + purchase + "/accept",
                                        Map.of("expectedVersion", p.get("version").asLong()),
                                        null,
                                        409)
                                .get("code")
                                .asText())
                .isEqualTo("AUTHORIZATION_INVALID");
        assertThat(
                        count(
                                "SELECT count(*) FROM quote WHERE purchase_id=? AND"
                                        + " status='ACCEPTED'",
                                purchase))
                .isZero();
    }

    @Test
    void replacedFamilyNoteRemainsUsedIncludingReuploadsWithTheSameHash() throws Exception {
        UUID originalNote = buyerEvidence("FAMILY_AUTHORIZATION");
        UUID replacementNote = buyerEvidence("FAMILY_AUTHORIZATION");
        var request =
                new HashMap<>(createRequest(List.of(Map.of("productId", product, "quantity", 1))));
        request.put("thirdParty", true);
        request.put("beneficiaryName", "María Torres");
        request.put("beneficiaryBirthDate", "1960-06-15");
        request.put("beneficiaryIdentity", "14222333");
        request.put("authorizationDocumentId", originalNote);
        var first = post(buyer, "/api/purchases", request, null, 200);
        UUID purchase = UUID.fromString(first.get("id").asText());
        var changed =
                post(
                        buyer,
                        "/api/purchases/" + purchase + "/authorization",
                        Map.of(
                                "expectedVersion",
                                first.get("version").asLong(),
                                "documentId",
                                replacementNote),
                        null,
                        200);
        assertThat(changed.get("authorizationDocumentId").asText())
                .isEqualTo(replacementNote.toString());
        assertThat(
                        count(
                                "SELECT count(*) FROM family_authorization fa JOIN purchase p ON"
                                        + " p.family_authorization_id=fa.id WHERE p.id=? AND"
                                        + " fa.document_id=? AND fa.status='PENDING'",
                                purchase,
                                replacementNote))
                .isEqualTo(1);
        assertThat(post(buyer, "/api/purchases", request, null, 409).get("code").asText())
                .isEqualTo("AUTHORIZATION_ALREADY_USED");
        UUID sameContent = buyerEvidence("FAMILY_AUTHORIZATION");
        sql(
                "UPDATE document SET sha256=(SELECT sha256 FROM document WHERE id=?) WHERE id=?",
                originalNote,
                sameContent);
        request.put("authorizationDocumentId", sameContent);
        assertThat(post(buyer, "/api/purchases", request, null, 409).get("code").asText())
                .isEqualTo("AUTHORIZATION_ALREADY_USED");
        assertThat(
                        post(
                                        buyer,
                                        "/api/purchases/" + purchase + "/authorization",
                                        Map.of(
                                                "expectedVersion",
                                                changed.get("version").asLong(),
                                                "documentId",
                                                sameContent),
                                        null,
                                        409)
                                .get("code")
                                .asText())
                .isEqualTo("AUTHORIZATION_ALREADY_USED");
        assertThat(
                        count(
                                "SELECT count(*) FROM family_authorization WHERE document_id IN"
                                        + " (?,?)",
                                originalNote,
                                sameContent))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM family_authorization fa JOIN purchase p ON"
                                        + " p.family_authorization_id=fa.id WHERE p.id=? AND"
                                        + " fa.document_id=?",
                                purchase,
                                replacementNote))
                .isEqualTo(1);
    }

    @Test
    void reroutingAnExpensivePurchaseRequiresTwoNewProfessionalReviews() throws Exception {
        BigDecimal expensivePrice =
                context.getBean(ar.emedicapp.config.OperationalConfigService.class)
                        .current()
                        .highValueAmount()
                        .add(new BigDecimal("100.00"));
        sql(
                "UPDATE branch_stock SET price=? WHERE branch_id=? AND product_id=?",
                expensivePrice,
                branch,
                product);
        var secondProfessional = scopedProfessional();
        var first = create();
        first =
                post(
                        pharmacist,
                        "/api/orders/" + first.get("orders").get(0).get("id").asText() + "/review",
                        review(orderVersion(first)),
                        null,
                        200);
        assertThat(first.get("status").asText()).isEqualTo("ROUTING");
        first =
                post(
                        secondProfessional.session(),
                        "/api/orders/" + first.get("orders").get(0).get("id").asText() + "/review",
                        review(orderVersion(first)),
                        null,
                        200);
        assertThat(first.get("status").asText()).isEqualTo("QUOTED");
        UUID purchase = UUID.fromString(first.get("id").asText());
        UUID previousQuote =
                db.sql("SELECT id FROM quote WHERE purchase_id=? AND status='ISSUED'")
                        .params(purchase)
                        .query(UUID.class)
                        .single();
        var rerouted =
                post(
                        buyer,
                        "/api/purchases/" + purchase + "/reroute",
                        Map.of("expectedVersion", first.get("version").asLong()),
                        null,
                        200);
        var once =
                post(
                        pharmacist,
                        "/api/orders/"
                                + rerouted.get("orders").get(0).get("id").asText()
                                + "/review",
                        review(orderVersion(rerouted)),
                        null,
                        200);
        assertThat(once.get("status").asText()).isEqualTo("ROUTING");
        assertThat(once.get("orders").get(0).get("reviewCount").asLong()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM quote WHERE purchase_id=? AND"
                                        + " status='ISSUED'",
                                purchase))
                .isZero();
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        var twice =
                post(
                        secondProfessional.session(),
                        "/api/orders/" + once.get("orders").get(0).get("id").asText() + "/review",
                        review(orderVersion(once)),
                        null,
                        200);
        assertThat(twice.get("status").asText()).isEqualTo("QUOTED");
        assertThat(
                        db.sql("SELECT id FROM quote WHERE purchase_id=? AND status='ISSUED'")
                                .params(purchase)
                                .query(UUID.class)
                                .single())
                .isNotEqualTo(previousQuote);
        assertThat(
                        count(
                                "SELECT count(*) FROM quote WHERE id=? AND status='SUPERSEDED'",
                                previousQuote))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void expensiveQuoteCannotBeAcceptedAfterSecondReviewersBranchPermissionIsRevoked()
            throws Exception {
        BigDecimal expensivePrice =
                context.getBean(ar.emedicapp.config.OperationalConfigService.class)
                        .current()
                        .highValueAmount()
                        .add(new BigDecimal("100.00"));
        sql(
                "UPDATE branch_stock SET price=? WHERE branch_id=? AND product_id=?",
                expensivePrice,
                branch,
                product);
        var secondProfessional = scopedProfessional();
        var first = create();
        first =
                post(
                        pharmacist,
                        "/api/orders/" + first.get("orders").get(0).get("id").asText() + "/review",
                        review(orderVersion(first)),
                        null,
                        200);
        first =
                post(
                        secondProfessional.session(),
                        "/api/orders/" + first.get("orders").get(0).get("id").asText() + "/review",
                        review(orderVersion(first)),
                        null,
                        200);
        assertThat(first.get("status").asText()).isEqualTo("QUOTED");
        UUID purchase = UUID.fromString(first.get("id").asText());
        sql(
                "UPDATE membership SET active=false WHERE user_id=? AND branch_id=? AND"
                        + " role='PHARMACIST'",
                secondProfessional.id(),
                branch);
        assertThat(
                        post(
                                        buyer,
                                        "/api/purchases/" + purchase + "/accept",
                                        Map.of("expectedVersion", first.get("version").asLong()),
                                        null,
                                        409)
                                .get("code")
                                .asText())
                .isEqualTo("QUOTE_REVIEW_REVOKED");
        assertThat(
                        count(
                                "SELECT count(*) FROM quote WHERE purchase_id=? AND"
                                        + " status='ACCEPTED'",
                                purchase))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE purchase_id=? AND"
                                        + " status='ACCEPTED'",
                                purchase))
                .isZero();
        assertThat(count("SELECT count(*) FROM purchase WHERE id=? AND status='QUOTED'", purchase))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void creatingWithSameKeyDoesNotDuplicatePersistentIntent() throws Exception {
        String key = UUID.randomUUID().toString();
        Object body = createRequest(List.of(Map.of("productId", product, "quantity", 1)));
        var first = post(buyer, "/api/purchases", body, key, 200);
        var again = post(buyer, "/api/purchases", body, key, 200);
        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE purchase_id=?",
                                UUID.fromString(first.get("id").asText())))
                .isEqualTo(1);
    }

    @Test
    void twoBuyersCompeteForLastUnitWhenQuotesAreIssued() throws Exception {
        JsonNode first = create();
        Session secondBuyer = unrelatedBuyer();
        var secondProfile = get(secondBuyer, "/api/auth/me");
        var secondRequest =
                new HashMap<>(createRequest(List.of(Map.of("productId", product, "quantity", 1))));
        secondRequest.put("beneficiaryName", secondProfile.get("name").asText());
        secondRequest.put("beneficiaryBirthDate", secondProfile.get("birthDate").asText());
        JsonNode second = post(secondBuyer, "/api/purchases", secondRequest, null, 200);
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return quoteStatus(first);
                            });
            Future<Integer> b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return quoteStatus(second);
                            });
            gate.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE branch_id=? AND"
                                        + " product_id=? AND status='HELD'",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void pharmacistFromOtherBranchCannotReviewThroughDriverMembership() throws Exception {
        var p = create();
        UUID user = UUID.randomUUID(), other = UUID.randomUUID();
        String email = "scope-" + user + "@test.invalid";
        String hash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("DemoMedicapp2026!");
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?,'Profesional scope',?,DATE"
                    + " '1990-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                user,
                email,
                hash);
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'PHARMACIST'),(?,'DRIVER')", user, user);
        sql(
                "INSERT INTO branch(id,name,address,latitude,longitude) VALUES(?,'Otra"
                        + " test','Ficticia',0,0)",
                other);
        membership(user, other, "PHARMACIST");
        membership(user, branch, "DRIVER");
        Session scoped = login(email);
        var denied =
                post(
                        scoped,
                        "/api/orders/" + p.get("orders").get(0).get("id").asText() + "/review",
                        review(p.get("orders").get(0).get("version").asLong()),
                        null,
                        403);
        assertThat(denied.get("code").asText()).isEqualTo("BRANCH_ROLE_REQUIRED");
        membership(user, branch, "PHARMACIST");
        assertThat(
                        get(scoped, "/api/purchases/" + p.get("id").asText())
                                .get("orders")
                                .get(0)
                                .has("beneficiary"))
                .isTrue();
        sql("DELETE FROM user_role WHERE user_id=? AND role='PHARMACIST'", user);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/purchases/" + p.get("id").asText())
                                .session(scoped.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void pickupCompletesPersistedFlowAndPinReplayIsRejected() throws Exception {
        var p = quoted();
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        String purchase = p.get("id").asText(), order = p.get("orders").get(0).get("id").asText();
        UUID receipt = evidence("PAYMENT_RECEIPT"), invoice = evidence("INVOICE");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-PAY-" + order,
                                "evidenceDocumentId",
                                receipt,
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/invoice",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-INVOICE",
                                "evidenceDocumentId",
                                invoice),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/prepare",
                        Map.of("expectedVersion", orderVersion(p)),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/dispense",
                        Map.of("expectedVersion", orderVersion(p)),
                        null,
                        200);
        String code = get(buyer, "/api/orders/" + order + "/receipt-code").get("code").asText();
        long version = orderVersion(p);
        String wrong = code.equals("000000") ? "111111" : "000000";
        post(
                pharmacist,
                "/api/orders/" + order + "/pickup-complete",
                receiptRequest(order, version, wrong, "Comprobación física test"),
                null,
                409);
        assertThat(
                        count(
                                "SELECT proof_failed_attempts FROM fulfillment WHERE order_id=?",
                                UUID.fromString(order)))
                .isEqualTo(1);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/pickup-complete",
                        receiptRequest(order, version, code, "Comprobación física test"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        post(
                pharmacist,
                "/api/orders/" + order + "/pickup-complete",
                receiptRequest(order, orderVersion(p), code, "Repetido test"),
                null,
                409);
        assertThat(get(buyer, "/api/purchases/" + purchase).get("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void paymentReplayProjectsCurrentCommercialPermissionsAndDoesNotExposeOldClinicalView()
            throws Exception {
        UUID user = UUID.randomUUID();
        String email = "replay-" + user + "@test.invalid";
        String hash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("DemoMedicapp2026!");
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?,'Operador mixto test',?,DATE"
                    + " '1990-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                user,
                email,
                hash);
        sql(
                "INSERT INTO user_role(user_id,role) VALUES(?,'PHARMACIST'),(?,'PHARMACY')",
                user,
                user);
        membership(user, branch, "PHARMACIST");
        membership(user, branch, "PHARMACY");
        Session operator = login(email);
        var p = quoted();
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        String order = p.get("orders").get(0).get("id").asText();
        Object request =
                Map.of(
                        "expectedVersion",
                        orderVersion(p),
                        "reference",
                        "TEST-REPLAY-" + order,
                        "evidenceDocumentId",
                        evidence("PAYMENT_RECEIPT"),
                        "verifiedExternally",
                        true,
                        "amount",
                        orderTotal(order),
                        "currency",
                        "ARS");
        String key = UUID.randomUUID().toString();
        p = post(operator, "/api/orders/" + order + "/payment", request, key, 200);
        assertThat(p.get("orders").get(0).has("beneficiary")).isTrue();
        sql("DELETE FROM user_role WHERE user_id=? AND role='PHARMACIST'", user);
        var replay = post(operator, "/api/orders/" + order + "/payment", request, key, 200);
        assertThat(replay.get("orders").get(0).has("beneficiary")).isFalse();
        assertThat(replay.get("orders").get(0).has("prescriptionDocumentId")).isFalse();
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", UUID.fromString(order)))
                .isEqualTo(1);
    }

    @Test
    void cancellationCreatesFullRefundAndDoesNotClaimMoneyReturnedBeforeEvidence()
            throws Exception {
        var p = quoted();
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        String order = p.get("orders").get(0).get("id").asText();
        UUID receipt = evidence("PAYMENT_RECEIPT"),
                invoice = evidence("INVOICE"),
                credit = evidence("INVOICE"),
                refundReceipt = evidence("PAYMENT_RECEIPT");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-PAY-" + order,
                                "evidenceDocumentId",
                                receipt,
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/invoice",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "FACTURA-" + order,
                                "evidenceDocumentId",
                                invoice),
                        null,
                        200);
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/cancel",
                        Map.of(
                                "expectedVersion",
                                p.get("version").asLong(),
                                "reason",
                                "Cancelación test antes de dispensa"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("COMPENSATING");
        assertThat(p.get("orders").get(0).get("refunds").get(0).get("amount").isTextual()).isTrue();
        assertThat(
                        new BigDecimal(
                                p.get("orders")
                                        .get(0)
                                        .get("refunds")
                                        .get(0)
                                        .get("amount")
                                        .asText()))
                .isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        var refund =
                Map.of(
                        "expectedVersion",
                        orderVersion(p),
                        "reference",
                        "TEST-REFUND-" + order,
                        "refundId",
                        p.get("orders").get(0).get("refunds").get(0).get("id").asText(),
                        "evidenceDocumentId",
                        refundReceipt,
                        "verifiedExternally",
                        true,
                        "amount",
                        orderTotal(order),
                        "currency",
                        "ARS");
        assertThat(
                        post(pharmacist, "/api/orders/" + order + "/refund", refund, null, 409)
                                .get("code")
                                .asText())
                .isEqualTo("CREDIT_NOTE_REQUIRED");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/fiscal-adjustment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "kind",
                                "CREDIT",
                                "reference",
                                "NC-" + order,
                                "amount",
                                "1500.00",
                                "currency",
                                "ARS",
                                "evidenceDocumentId",
                                credit,
                                "reason",
                                "Nota de crédito emitida por cancelación total de la factura"),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/refund",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-REFUND-" + order,
                                "refundId",
                                p.get("orders").get(0).get("refunds").get(0).get("id").asText(),
                                "evidenceDocumentId",
                                refundReceipt,
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(p.get("orders").get(0).get("refunds").get(0).get("status").asText())
                .isEqualTo("VERIFIED");
        for (UUID published : List.of(invoice, credit, refundReceipt)) {
            assertThat(get(buyer, "/api/documents/" + published + "/metadata").get("id").asText())
                    .isEqualTo(published.toString());
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + published + "/metadata")
                                    .session(unrelatedBuyer().session()))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + published + "/metadata")
                                    .session(login("cadete@demo.invalid").session()))
                    .andExpect(status().isForbidden());
        }
        for (UUID privateEvidence :
                List.of(receipt, evidence("OTHER"), evidence("HEALTH_COVERAGE")))
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + privateEvidence + "/metadata")
                                    .session(buyer.session()))
                    .andExpect(status().isForbidden());
    }

    @Test
    void postDispenseIncidentClosesAsResolvedWithoutFabricatingDeliveryOrRestocking()
            throws Exception {
        var p = quoted();
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        String order = p.get("orders").get(0).get("id").asText();
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-PAY-" + order,
                                "evidenceDocumentId",
                                evidence("PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/invoice",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "FACTURA-INCIDENTE-" + order,
                                "evidenceDocumentId",
                                evidence("INVOICE")),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/prepare",
                        Map.of("expectedVersion", orderVersion(p)),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/dispense",
                        Map.of("expectedVersion", orderVersion(p)),
                        null,
                        200);
        UUID proof = evidence("OTHER");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/incident",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reason",
                                "Incidencia física test",
                                "kind",
                                "OTHER",
                                "evidenceDocumentId",
                                proof),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/resolve-incident",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "decision",
                                "CLOSE_WITHOUT_REFUND",
                                "reason",
                                "Dictamen test con evidencia",
                                "evidenceDocumentId",
                                proof),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(p.get("orders").get(0).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(p.get("orders").get(0).get("fulfillment").get("status").asText())
                .isEqualTo("RESOLVED");
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM custody_event WHERE fulfillment_id=(SELECT id"
                                        + " FROM fulfillment WHERE order_id=?) AND kind IN"
                                        + " ('COLLECTED','DELIVERED','RETURNED')",
                                UUID.fromString(order)))
                .isZero();
    }

    @Test
    void partialPaymentCannotStartAnyChildFulfillment() throws Exception {
        UUID secondBranch = UUID.randomUUID(), secondProduct = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " branch(id,name,address,latitude,longitude,verified,agreement_accepted,pickup_enabled)"
                    + " VALUES(?,'Fallback aislado test','Ficticia',-32.9,-68.85,true,true,true)",
                secondBranch);
        sql(
                "INSERT INTO product(id,code,name,presentation) VALUES(?,?,'Segundo producto"
                        + " test','Envase test')",
                secondProduct,
                "TEST-" + secondProduct);
        sql(
                "INSERT INTO branch_stock(branch_id,product_id,quantity,price) VALUES(?,?,1,2200)",
                secondBranch,
                secondProduct);
        membership(DemoIds.id("user:farmaceutico@demo.invalid"), secondBranch, "PHARMACIST");
        var p =
                post(
                        buyer,
                        "/api/purchases",
                        createRequest(
                                List.of(
                                        Map.of("productId", product, "quantity", 1),
                                        Map.of("productId", secondProduct, "quantity", 1))),
                        null,
                        200);
        assertThat(p.get("orders").size()).isEqualTo(2);
        for (JsonNode o : p.get("orders")) {
            UUID item = UUID.fromString(o.get("lines").get(0).get("productId").asText());
            post(
                    pharmacist,
                    "/api/orders/" + o.get("id").asText() + "/review",
                    Map.of(
                            "expectedVersion",
                            o.get("version").asLong(),
                            "decision",
                            "APPROVE",
                            "reason",
                            "Disponibilidad test",
                            "authorizationChecked",
                            true,
                            "lines",
                            List.of(Map.of("productId", item, "coverageAmount", "0.00"))),
                    null,
                    200);
        }
        p = get(buyer, "/api/purchases/" + p.get("id").asText());
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        JsonNode own = null;
        for (JsonNode o : p.get("orders"))
            if (o.get("branchId").asText().equals(branch.toString())) own = o;
        assertThat(own).isNotNull();
        String order = own.get("id").asText();
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                own.get("version").asLong(),
                                "reference",
                                "TEST-PAY-" + order,
                                "evidenceDocumentId",
                                evidence("PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        for (JsonNode o : p.get("orders")) if (o.get("id").asText().equals(order)) own = o;
        var denied =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/prepare",
                        Map.of("expectedVersion", own.get("version").asLong()),
                        null,
                        409);
        assertThat(denied.get("code").asText()).isEqualTo("GROUP_NOT_READY");
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE purchase_id=? AND"
                                        + " status='PREPARING'",
                                UUID.fromString(p.get("id").asText())))
                .isZero();
        UUID purchase = UUID.fromString(p.get("id").asText());
        sql(
                "UPDATE reservation SET expires_at=now()-interval '1 minute' WHERE order_id"
                        + " IN(SELECT id FROM child_order WHERE purchase_id=?)",
                purchase);
        assertThat(payments.expirePurchase(purchase)).isTrue();
        p = get(buyer, "/api/purchases/" + purchase);
        assertThat(p.get("status").asText()).isEqualTo("COMPENSATING");
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE purchase_id=? AND"
                                        + " status<>'CANCELLED'",
                                purchase))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment pay ON"
                                    + " pay.id=r.payment_id WHERE pay.order_id=? AND r.requested_by"
                                    + " IS NULL AND r.amount=1500 AND r.status='REQUESTED'",
                                UUID.fromString(order)))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
    }

    @Test
    void expensiveMedicationRequiresTwoDistinctReviewersAndRefundHasNoArbitraryCap()
            throws Exception {
        sql(
                "UPDATE branch_stock SET price=2000000 WHERE branch_id=? AND product_id=?",
                branch,
                product);
        var p = create();
        var o = p.get("orders").get(0);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + o.get("id").asText() + "/review",
                        review(o.get("version").asLong()),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("ROUTING");
        assertThat(p.get("orders").get(0).get("reviewCount").asInt()).isEqualTo(1);
        o = p.get("orders").get(0);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + o.get("id").asText() + "/review",
                        review(o.get("version").asLong()),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("ROUTING");
        assertThat(p.get("orders").get(0).get("reviewCount").asInt()).isEqualTo(1);
        UUID professional = UUID.randomUUID();
        String email = "second-" + professional + "@test.invalid";
        String hash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("DemoMedicapp2026!");
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?,'Segundo profesional test',?,DATE"
                    + " '1990-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                professional,
                email,
                hash);
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'PHARMACIST')", professional);
        membership(professional, branch, "PHARMACIST");
        Session secondReviewer = login(email);
        o = p.get("orders").get(0);
        p =
                post(
                        secondReviewer,
                        "/api/orders/" + o.get("id").asText() + "/review",
                        review(o.get("version").asLong()),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("QUOTED");
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/accept",
                        Map.of("expectedVersion", p.get("version").asLong()),
                        null,
                        200);
        String order = p.get("orders").get(0).get("id").asText();
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TEST-HIGH-PAY-" + order,
                                "evidenceDocumentId",
                                evidence("PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/cancel",
                        Map.of(
                                "expectedVersion",
                                p.get("version").asLong(),
                                "reason",
                                "Cancelación alto valor test"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("COMPENSATING");
        assertThat(p.get("orders").get(0).get("cancellationPending").asBoolean()).isTrue();
        assertThat(p.get("orders").get(0).get("refunds").size()).isZero();
        UUID cancellationEvidence = evidence("OTHER");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/financial-control",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "operation",
                                "CANCEL",
                                "reason",
                                "Primer farmacéutico verificó la cancelación de alto valor",
                                "evidenceDocumentId",
                                cancellationEvidence),
                        null,
                        200);
        p =
                post(
                        secondReviewer,
                        "/api/orders/" + order + "/financial-control",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "operation",
                                "CANCEL",
                                "reason",
                                "Segundo farmacéutico verificó las mismas condiciones de"
                                        + " cancelación",
                                "evidenceDocumentId",
                                cancellationEvidence),
                        null,
                        200);
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/cancel",
                        Map.of(
                                "expectedVersion",
                                p.get("version").asLong(),
                                "reason",
                                "Confirmación de la cancelación de alto valor controlada"),
                        null,
                        200);
        assertThat(p.get("orders").get(0).get("refunds").get(0).get("amount").isTextual()).isTrue();
        assertThat(
                        new BigDecimal(
                                p.get("orders")
                                        .get(0)
                                        .get("refunds")
                                        .get(0)
                                        .get("amount")
                                        .asText()))
                .isEqualByComparingTo(new BigDecimal("2000000.00"));
    }

    @Test
    void buyerPaymentReportIsIdempotentAndRequiresSeparatePharmacyVerification() throws Exception {
        JsonNode p = accepted();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        UUID receipt = buyerEvidence("PAYMENT_RECEIPT");
        String reference = "TRANSFERENCIA-" + order, key = UUID.randomUUID().toString();
        Object request =
                Map.of(
                        "expectedVersion",
                        orderVersion(p),
                        "reference",
                        reference,
                        "evidenceDocumentId",
                        receipt);

        JsonNode reported =
                post(buyer, "/api/orders/" + order + "/payment-report", request, key, 200);
        JsonNode replay =
                post(buyer, "/api/orders/" + order + "/payment-report", request, key, 200);
        assertThat(reported.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(reported.get("orders").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(replay.get("version").asLong()).isEqualTo(reported.get("version").asLong());
        assertThat(orderVersion(replay)).isEqualTo(orderVersion(reported));
        assertThat(reported.get("orders").get(0).get("paymentSubmission").get("reference").asText())
                .isEqualTo(reference);
        assertThat(
                        reported.get("orders")
                                .get(0)
                                .get("paymentSubmission")
                                .get("evidenceDocumentId")
                                .asText())
                .isEqualTo(receipt.toString());
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", order)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                        + " action='CUSTOMER_PAYMENT_REPORTED'",
                                order))
                .isEqualTo(1);
        assertThat(get(pharmacist, "/api/documents/" + receipt + "/metadata").get("id").asText())
                .isEqualTo(receipt.toString());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/documents/" + receipt + "/metadata")
                                .session(login("admin@demo.invalid").session()))
                .andExpect(status().isForbidden());
        Session unrelatedBuyer = unrelatedBuyer();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/documents/" + receipt + "/metadata")
                                .session(unrelatedBuyer.session()))
                .andExpect(status().isForbidden());

        post(
                pharmacist,
                "/api/orders/" + order + "/payment",
                Map.of(
                        "expectedVersion",
                        orderVersion(reported),
                        "reference",
                        reference,
                        "evidenceDocumentId",
                        receipt,
                        "verifiedExternally",
                        false,
                        "amount",
                        orderTotal(order),
                        "currency",
                        "ARS"),
                null,
                400);
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", order)).isZero();
        JsonNode verified =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(reported),
                                "reference",
                                reference,
                                "evidenceDocumentId",
                                receipt,
                                "verifiedExternally",
                                true,
                                "amount",
                                orderTotal(order),
                                "currency",
                                "ARS"),
                        null,
                        200);
        assertThat(verified.get("status").asText()).isEqualTo("PAID");
        assertThat(
                        count(
                                "SELECT count(*) FROM payment WHERE order_id=? AND"
                                        + " status='VERIFIED' AND evidence_document_id=?",
                                order,
                                receipt))
                .isEqualTo(1);
    }

    @Test
    void bankConfirmationRequiresRealAmountCurrencyAndDoesNotInventFullSettlement()
            throws Exception {
        var p = accepted();
        String order = p.get("orders").get(0).get("id").asText();
        var request =
                new HashMap<String, Object>(
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "PARCIAL-" + order,
                                "evidenceDocumentId",
                                evidence("PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true));
        assertThat(
                        post(pharmacist, "/api/orders/" + order + "/payment", request, null, 400)
                                .get("code")
                                .asText())
                .isEqualTo("AMOUNT_INVALID");
        request.put("amount", "500.00");
        request.put("currency", "USD");
        assertThat(
                        post(pharmacist, "/api/orders/" + order + "/payment", request, null, 400)
                                .get("code")
                                .asText())
                .isEqualTo("CURRENCY_MISMATCH");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", UUID.fromString(order)))
                .isZero();
        request.put("currency", "ARS");
        p = post(pharmacist, "/api/orders/" + order + "/payment", request, null, 200);
        assertThat(p.get("orders").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(
                        db.sql("SELECT sum(amount) FROM payment WHERE order_id=?")
                                .params(UUID.fromString(order))
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("500.00");
        request.put("expectedVersion", orderVersion(p));
        request.put("reference", "SALDO-" + order);
        request.put("amount", "1100.00");
        assertThat(
                        post(pharmacist, "/api/orders/" + order + "/payment", request, null, 409)
                                .get("code")
                                .asText())
                .isEqualTo("PAYMENT_EXCEEDS_BALANCE");
        request.put("amount", "1000.00");
        p = post(pharmacist, "/api/orders/" + order + "/payment", request, null, 200);
        assertThat(p.get("orders").get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", UUID.fromString(order)))
                .isEqualTo(2);
    }

    @Test
    void paymentReportRejectsAnotherOwnersReceiptWrongDocumentTypeAndPharmacyActor()
            throws Exception {
        JsonNode p = accepted();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        UUID anotherOwnersReceipt = evidence("PAYMENT_RECEIPT"), wrongType = buyerEvidence("OTHER");
        for (UUID document : List.of(anotherOwnersReceipt, wrongType)) {
            JsonNode denied =
                    post(
                            buyer,
                            "/api/orders/" + order + "/payment-report",
                            Map.of(
                                    "expectedVersion",
                                    orderVersion(p),
                                    "reference",
                                    "COMPROBANTE-" + document,
                                    "evidenceDocumentId",
                                    document),
                            null,
                            403);
            assertThat(denied.get("code").asText()).isEqualTo("DOCUMENT_SCOPE_INVALID");
        }
        post(
                pharmacist,
                "/api/orders/" + order + "/payment-report",
                Map.of(
                        "expectedVersion",
                        orderVersion(p),
                        "reference",
                        "REPORTE-NO-AUTORIZADO",
                        "evidenceDocumentId",
                        buyerEvidence("PAYMENT_RECEIPT")),
                null,
                403);
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE id=? AND"
                                        + " snapshot->'paymentSubmission' IS NOT NULL",
                                order))
                .isZero();
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", order)).isZero();
        assertThat(get(buyer, "/api/purchases/" + p.get("id").asText()).get("version").asLong())
                .isEqualTo(p.get("version").asLong());
    }

    @Test
    void expiredReservationCannotAcceptPaymentReportOrCreateVerifiedPayment() throws Exception {
        JsonNode p = accepted();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        UUID receipt = buyerEvidence("PAYMENT_RECEIPT");
        sql(
                "UPDATE reservation SET expires_at=now()-interval '1 minute' WHERE order_id=? AND"
                        + " status='HELD'",
                order);
        JsonNode denied =
                post(
                        buyer,
                        "/api/orders/" + order + "/payment-report",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "RESERVA-VENCIDA-" + order,
                                "evidenceDocumentId",
                                receipt),
                        null,
                        409);
        assertThat(denied.get("code").asText())
                .isIn("RESERVATION_EXPIRED", "PAYMENT_REPORT_STATE_INVALID", "VERSION_MISMATCH");
        assertThat(
                        count(
                                "SELECT count(*) FROM child_order WHERE id=? AND"
                                        + " snapshot->'paymentSubmission' IS NOT NULL",
                                order))
                .isZero();
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", order)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM audit_event WHERE entity_id=? AND"
                                        + " action='CUSTOMER_PAYMENT_REPORTED'",
                                order))
                .isZero();
    }

    @Test
    void reportedPaymentReceivedAfterCancellationRequestsFullRefundWithoutReactivatingStock()
            throws Exception {
        JsonNode p = accepted();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        p =
                post(
                        buyer,
                        "/api/orders/" + order + "/payment-report",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TRANSFERENCIA-PENDIENTE-" + order,
                                "evidenceDocumentId",
                                buyerEvidence("PAYMENT_RECEIPT")),
                        null,
                        200);
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/cancel",
                        Map.of(
                                "expectedVersion",
                                p.get("version").asLong(),
                                "reason",
                                "El comprador canceló mientras se comprobaba la transferencia"),
                        null,
                        200);
        UUID evidence = evidence("PAYMENT_RECEIPT");
        Object reconciliation =
                Map.of(
                        "expectedVersion",
                        orderVersion(p),
                        "decision",
                        "RECEIVED",
                        "reference",
                        "ACREDITACION-CONCILIADA-" + order,
                        "evidenceDocumentId",
                        evidence,
                        "verifiedExternally",
                        true,
                        "amount",
                        orderTotal(order),
                        "currency",
                        "ARS",
                        "reason",
                        "Se comprobó el ingreso completo en la cuenta de la farmacia después de la"
                                + " cancelación");
        String key = UUID.randomUUID().toString();
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/reconcile-payment",
                        reconciliation,
                        key,
                        200);
        JsonNode replay =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/reconcile-payment",
                        reconciliation,
                        key,
                        200);
        assertThat(replay.get("version").asLong()).isEqualTo(p.get("version").asLong());
        assertThat(p.get("status").asText()).isEqualTo("COMPENSATING");
        assertThat(p.get("orders").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(p.get("orders").get(0).get("paymentSubmission").get("status").asText())
                .isEqualTo("REFUND_REQUESTED");
        assertThat(
                        count(
                                "SELECT count(*) FROM payment WHERE order_id=? AND amount=1500 AND"
                                        + " status='VERIFIED'",
                                order))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment pay ON"
                                        + " pay.id=r.payment_id WHERE pay.order_id=? AND"
                                        + " r.amount=pay.amount AND r.status='REQUESTED'",
                                order))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                        + " status='HELD'",
                                order))
                .isZero();
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void reportedPaymentNotReceivedAfterCancellationClosesReportWithoutInventingFunds()
            throws Exception {
        JsonNode p = accepted();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        p =
                post(
                        buyer,
                        "/api/orders/" + order + "/payment-report",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "reference",
                                "TRANSFERENCIA-NO-ACREDITADA-" + order,
                                "evidenceDocumentId",
                                buyerEvidence("PAYMENT_RECEIPT")),
                        null,
                        200);
        p =
                post(
                        buyer,
                        "/api/purchases/" + p.get("id").asText() + "/cancel",
                        Map.of(
                                "expectedVersion",
                                p.get("version").asLong(),
                                "reason",
                                "El comprador canceló antes de la comprobación bancaria"),
                        null,
                        200);
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/reconcile-payment",
                        Map.of(
                                "expectedVersion",
                                orderVersion(p),
                                "decision",
                                "NOT_RECEIVED",
                                "reference",
                                "EXTRACTO-REVISADO-" + order,
                                "evidenceDocumentId",
                                evidence("PAYMENT_RECEIPT"),
                                "verifiedExternally",
                                true,
                                "reason",
                                "Se revisó el extracto y el banco confirmó que la farmacia no"
                                        + " recibió esa transferencia"),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(p.get("orders").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(p.get("orders").get(0).get("paymentSubmission").get("status").asText())
                .isEqualTo("NOT_RECEIVED");
        assertThat(count("SELECT count(*) FROM payment WHERE order_id=?", order)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM refund r JOIN payment pay ON"
                                        + " pay.id=r.payment_id WHERE pay.order_id=?",
                                order))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                        + " status='HELD'",
                                order))
                .isZero();
        assertThat(
                        count(
                                "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isZero();
        assertThat(
                        count(
                                "SELECT quantity FROM branch_stock WHERE branch_id=? AND"
                                        + " product_id=?",
                                branch,
                                product))
                .isEqualTo(1);
    }

    @Test
    void coverageApprovalEvidenceIsClinicalAndCommercialRolesCannotUploadOrReadIt()
            throws Exception {
        JsonNode p = create();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText());
        Session operator = login("farmacia@demo.invalid"),
                admin = login("admin@demo.invalid"),
                driver = login("cadete@demo.invalid");
        membership(DemoIds.id("user:farmacia@demo.invalid"), branch, "PHARMACY");
        membership(DemoIds.id("user:cadete@demo.invalid"), branch, "DRIVER");
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "autorizacion-cobertura.pdf",
                        "application/pdf",
                        "%PDF-1.4\nAutorizacion clinica de prueba"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (Session commercial : List.of(operator, admin, driver)) {
            mvc.perform(
                            multipart("/api/documents")
                                    .file(file)
                                    .param("kind", "COBERTURA")
                                    .param("branchId", branch.toString())
                                    .session(commercial.session())
                                    .header(commercial.header(), commercial.token()))
                    .andExpect(status().isForbidden());
        }
        var uploaded =
                mvc.perform(
                                multipart("/api/documents")
                                        .file(file)
                                        .param("kind", "COBERTURA")
                                        .param("branchId", branch.toString())
                                        .session(pharmacist.session())
                                        .header(pharmacist.header(), pharmacist.token()))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID evidence =
                UUID.fromString(
                        json.readTree(uploaded.getResponse().getContentAsString())
                                .get("id")
                                .asText());
        TestProfessional professional = scopedProfessional();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/documents/" + evidence + "/metadata")
                                .session(professional.session().session()))
                .andExpect(status().isForbidden());
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/review",
                        coverageReview(orderVersion(p), evidence),
                        null,
                        200);
        assertThat(new BigDecimal(p.get("orders").get(0).get("total").asText()))
                .isEqualByComparingTo("1000.00");
        assertThat(
                        get(professional.session(), "/api/documents/" + evidence + "/metadata")
                                .get("documentType")
                                .asText())
                .isEqualTo("HEALTH_COVERAGE");
        for (Session commercial : List.of(operator, admin, driver)) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + evidence + "/metadata")
                                    .session(commercial.session()))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + evidence)
                                    .session(commercial.session()))
                    .andExpect(status().isForbidden());
        }
        UUID commercialReceipt = evidence("PAYMENT_RECEIPT");
        assertThat(
                        get(
                                        professional.session(),
                                        "/api/documents/" + commercialReceipt + "/metadata")
                                .get("id")
                                .asText())
                .isEqualTo(commercialReceipt.toString());
        sql("DELETE FROM user_role WHERE user_id=? AND role='PHARMACIST'", professional.id());
        for (UUID document : List.of(evidence, commercialReceipt)) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + document + "/metadata")
                                    .session(professional.session().session()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void historicalOtherCoverageEvidenceRemainsPrivateAndCanOnlyBeReusedForItsOriginalOrder()
            throws Exception {
        sql(
                "UPDATE branch_stock SET price=2000000,quantity=2 WHERE branch_id=? AND"
                        + " product_id=?",
                branch,
                product);
        JsonNode p = create();
        UUID order = UUID.fromString(p.get("orders").get(0).get("id").asText()),
                legacy = evidence("OTHER");
        sql(
                "INSERT INTO"
                    + " review(id,order_id,branch_id,kind,status,reviewed_by,reason,evidence_document_id)"
                    + " VALUES(?,?,?,'COVERAGE','APPROVED',?,'Autorizacion historica"
                    + " comprobada',?)",
                UUID.randomUUID(),
                order,
                branch,
                DemoIds.id("user:farmaceutico@demo.invalid"),
                legacy);
        Session admin = login("admin@demo.invalid"), operator = login("farmacia@demo.invalid");
        membership(DemoIds.id("user:farmacia@demo.invalid"), branch, "PHARMACY");
        for (Session commercial : List.of(admin, operator)) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/documents/" + legacy + "/metadata")
                                    .session(commercial.session()))
                    .andExpect(status().isForbidden());
        }
        TestProfessional professional = scopedProfessional();
        assertThat(
                        get(professional.session(), "/api/documents/" + legacy + "/metadata")
                                .get("documentType")
                                .asText())
                .isEqualTo("OTHER");
        p =
                post(
                        pharmacist,
                        "/api/orders/" + order + "/review",
                        coverageReview(orderVersion(p), legacy),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("ROUTING");
        p =
                post(
                        professional.session(),
                        "/api/orders/" + order + "/review",
                        coverageReview(orderVersion(p), legacy),
                        null,
                        200);
        assertThat(p.get("status").asText()).isEqualTo("QUOTED");
        assertThat(p.get("orders").get(0).get("reviewCount").asInt()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM document WHERE id=? AND kind='OTHER'", legacy))
                .isEqualTo(1);
        JsonNode other = create();
        String otherOrder = other.get("orders").get(0).get("id").asText();
        for (UUID invalid : List.of(legacy, evidence("OTHER"))) {
            JsonNode denied =
                    post(
                            pharmacist,
                            "/api/orders/" + otherOrder + "/review",
                            coverageReview(orderVersion(other), invalid),
                            null,
                            403);
            assertThat(denied.get("code").asText()).isEqualTo("DOCUMENT_SCOPE_INVALID");
        }
        assertThat(
                        count(
                                "SELECT count(*) FROM review WHERE order_id=? AND kind='COVERAGE'",
                                UUID.fromString(otherOrder)))
                .isZero();
    }

    private Map<String, Object> coverageReview(long version, UUID evidence) {
        return Map.of(
                "expectedVersion",
                version,
                "decision",
                "APPROVE",
                "reason",
                "Stock, precio, identidad y autorizacion de cobertura comprobados",
                "authorizationChecked",
                true,
                "lines",
                List.of(
                        Map.of(
                                "productId",
                                product,
                                "coverageAmount",
                                "500.00",
                                "coverageReference",
                                "AUTORIZACION-" + evidence,
                                "coverageEvidenceDocumentId",
                                evidence)));
    }

    private TestProfessional scopedProfessional() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "coverage-review-" + user + "@test.invalid";
        String hash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("DemoMedicapp2026!");
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?,'Profesional cobertura test',?,DATE"
                    + " '1980-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                user,
                email,
                hash);
        sql(
                "INSERT INTO user_role(user_id,role) VALUES(?,'PHARMACIST'),(?,'PHARMACY')",
                user,
                user);
        membership(user, branch, "PHARMACIST");
        return new TestProfessional(user, login(email));
    }

    private record TestProfessional(UUID id, Session session) {}

    private JsonNode accepted() throws Exception {
        JsonNode p = quoted();
        return post(
                buyer,
                "/api/purchases/" + p.get("id").asText() + "/accept",
                Map.of("expectedVersion", p.get("version").asLong()),
                null,
                200);
    }

    private Session unrelatedBuyer() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "buyer-access-" + user + "@test.invalid";
        String hash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("DemoMedicapp2026!");
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?,'Comprador ajeno test',?,DATE"
                    + " '1990-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                user,
                email,
                hash);
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'BUYER')", user);
        return login(email);
    }

    private UUID buyerEvidence(String kind) {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " document(id,owner_user_id,kind,original_name,content_type,size_bytes,storage_key,sha256)"
                    + " VALUES(?,?,?,'comprobante-comprador-test.pdf','application/pdf',10,?,?)",
                id,
                DemoIds.id("user:comprador@demo.invalid"),
                kind,
                id.toString(),
                id.toString().replace("-", "").repeat(2));
        return id;
    }

    private int quoteStatus(JsonNode p) throws Exception {
        var order = p.get("orders").get(0);
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/orders/" + order.get("id").asText() + "/review")
                                .session(pharmacist.session())
                                .header(pharmacist.header(), pharmacist.token())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                review(order.get("version").asLong()))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private JsonNode quoted() throws Exception {
        var p = create();
        var o = p.get("orders").get(0);
        return post(
                pharmacist,
                "/api/orders/" + o.get("id").asText() + "/review",
                review(o.get("version").asLong()),
                null,
                200);
    }

    private Map<String, Object> review(long version) {
        return Map.of(
                "expectedVersion",
                version,
                "decision",
                "APPROVE",
                "reason",
                "Stock físico comprobado test",
                "authorizationChecked",
                true,
                "lines",
                List.of(Map.of("productId", product, "coverageAmount", "0.00")));
    }

    private JsonNode create() throws Exception {
        return post(
                buyer,
                "/api/purchases",
                createRequest(List.of(Map.of("productId", product, "quantity", 1))),
                null,
                200);
    }

    private Map<String, Object> createRequest(List<Map<String, Object>> items) {
        var profile =
                db.sql("SELECT name,birth_date::text AS birth_date FROM app_user WHERE id=:id")
                        .param("id", DemoIds.id("user:comprador@demo.invalid"))
                        .query()
                        .singleRow();
        return Map.of(
                "beneficiaryName",
                profile.get("name"),
                "beneficiaryBirthDate",
                profile.get("birth_date"),
                "mode",
                "PICKUP",
                "destinationLat",
                -32.889,
                "destinationLon",
                -68.845,
                "destinationAddress",
                "Dirección ficticia test",
                "preferredBranchId",
                branch,
                "items",
                items);
    }

    private long orderVersion(JsonNode p) {
        return p.get("orders").get(0).get("version").asLong();
    }

    private BigDecimal orderTotal(Object order) {
        return db.sql("SELECT total FROM child_order WHERE id=?")
                .params(UUID.fromString(order.toString()))
                .query(BigDecimal.class)
                .single();
    }

    private Map<String, Object> receiptRequest(
            String order, long version, String code, String note) {
        var fulfillment =
                db.sql("SELECT receiver_name,receiver_identity FROM fulfillment WHERE order_id=?")
                        .params(UUID.fromString(order))
                        .query()
                        .singleRow();
        return Map.of(
                "expectedVersion",
                version,
                "code",
                code,
                "recipientVerified",
                true,
                "note",
                note,
                "recipientName",
                fulfillment.get("receiver_name"),
                "recipientIdentity",
                fulfillment.get("receiver_identity"),
                "packageMatches",
                true);
    }

    private UUID evidence(String kind) {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " document(id,owner_user_id,kind,branch_id,original_name,content_type,size_bytes,storage_key,sha256)"
                    + " VALUES(?,?,?,?,?,'application/pdf',10,?,?)",
                id,
                DemoIds.id("user:farmaceutico@demo.invalid"),
                kind,
                branch,
                "evidencia-test.pdf",
                "test:" + id,
                "a".repeat(64));
        return id;
    }

    private void membership(UUID user, UUID branch, String role) {
        sql(
                "INSERT INTO membership(id,user_id,branch_id,role) VALUES(?,?,?,?)",
                UUID.randomUUID(),
                user,
                branch,
                role);
    }

    private void sql(String statement, Object... params) {
        db.sql(statement).params(params).update();
    }

    private long count(String statement, Object... params) {
        return db.sql(statement).params(params).query(Long.class).single();
    }

    private JsonNode post(Session s, String path, Object body, String key, int expected)
            throws Exception {
        var req =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .session(s.session())
                        .header(s.header(), s.token())
                        .header("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body));
        var result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(
                        "%s: %s; cause=%s",
                        path,
                        result.getResponse().getContentAsString(),
                        result.getResolvedException())
                .isEqualTo(expected);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode get(Session s, String path) throws Exception {
        return json.readTree(
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(path)
                                        .session(s.session()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private Session login(String email) throws Exception {
        var csrf = csrf(null);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/auth/login")
                                .with(
                                        r -> {
                                            r.setRemoteAddr(
                                                    "127.2."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255)
                                                            + "."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255));
                                            return r;
                                        })
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", email)
                                .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isOk());
        return csrf(csrf.session());
    }

    private Session csrf(MockHttpSession session) throws Exception {
        var req =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/auth/csrf");
        if (session != null) req.session(session);
        var result = mvc.perform(req).andExpect(status().isOk()).andReturn();
        var p = json.readTree(result.getResponse().getContentAsString());
        return new Session(
                (MockHttpSession) result.getRequest().getSession(),
                p.get("token").asText(),
                p.get("headerName").asText());
    }

    private record Session(MockHttpSession session, String token, String header) {}
}
