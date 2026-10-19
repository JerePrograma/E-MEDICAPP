package ar.emedicapp.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class SubstitutionIntegrationTest {
    private static final Path DOCUMENTS =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "emedicapp-substitution-" + UUID.randomUUID());
    private static final UUID BUYER = DemoIds.id("user:comprador@demo.invalid");
    private static final UUID PHARMACIST = DemoIds.id("user:farmaceutico@demo.invalid");
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    private MockMvc mvc;
    private Session buyer, pharmacist;
    private UUID branch, original, replacement;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        values.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        values.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
        values.add("emedicapp.documents.storage-dir", DOCUMENTS::toString);
    }

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        buyer = login("comprador@demo.invalid");
        pharmacist = login("farmaceutico@demo.invalid");
        branch = branch();
        original = product("Original", false, 1500);
        replacement = product("Alternativa", false, 1700);
        membership(PHARMACIST, branch, "PHARMACIST");
        UUID plan = UUID.randomUUID(), contract = UUID.randomUUID();
        sql(
                "INSERT INTO plan(id,name,min_branches,monthly_price,currency) VALUES(?,'Plan"
                        + " sustitución test',1,1,'ARS')",
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
    void pendingProposalReleasesQuoteStockPreservesRequestAndBlocksOtherTransitions()
            throws Exception {
        var quoted = review(create(null), original, false);
        UUID purchase = uuid(quoted, "id"),
                item = activeItem(purchase),
                quote = issuedQuote(purchase);
        UUID order = orderId(quoted);
        assertThat(reserved(original)).isEqualTo(1);
        var pending = propose(quoted, replacement, 1, false, null, 200);
        assertThat(pending.get("status").asText()).isEqualTo("ROUTING");
        assertThat(reserved(original)).isZero();
        assertThat(reserved(replacement)).isZero();
        assertThat(value("SELECT status FROM quote WHERE id=?", quote)).isEqualTo("SUPERSEDED");
        assertThat(
                        count(
                                "SELECT count(*) FROM reservation WHERE order_id=? AND"
                                        + " status='RELEASED'",
                                order))
                .isEqualTo(1);
        assertThat(activeItem(purchase)).isEqualTo(item);
        assertThat(count("SELECT count(*) FROM purchase_item WHERE purchase_id=?", purchase))
                .isEqualTo(1);
        assertCode(
                post(
                        pharmacist,
                        "/api/orders/" + order + "/review",
                        reviewRequest(pending, original, false),
                        409),
                "SUBSTITUTION_PENDING");
        assertCode(
                post(buyer, "/api/purchases/" + purchase + "/accept", version(pending), 409),
                "SUBSTITUTION_PENDING");
        assertCode(
                post(buyer, "/api/purchases/" + purchase + "/reroute", version(pending), 409),
                "SUBSTITUTION_PENDING");
        assertCode(propose(pending, replacement, 1, false, null, 409), "SUBSTITUTION_PENDING");
        assertThat(
                        count(
                                "SELECT count(*) FROM substitution_proposal WHERE purchase_id=?",
                                purchase))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM payment p JOIN child_order o ON"
                                        + " o.id=p.order_id WHERE o.purchase_id=?",
                                purchase))
                .isZero();
    }

    @Test
    void rejectingProposalKeepsOriginalItemAndNeedsFreshQuote() throws Exception {
        var quoted = review(create(null), original, false);
        UUID purchase = uuid(quoted, "id"),
                originalItem = activeItem(purchase),
                oldQuote = issuedQuote(purchase);
        var pending = propose(quoted, replacement, 1, false, null, 200);
        UUID proposal = pendingProposal(purchase);
        var refused = respond(pending, proposal, "REJECT", 200);
        assertThat(refused.get("status").asText()).isEqualTo("ROUTING");
        assertThat(activeItem(purchase)).isEqualTo(originalItem);
        assertThat(count("SELECT count(*) FROM purchase_item WHERE purchase_id=?", purchase))
                .isEqualTo(1);
        assertThat(value("SELECT status FROM substitution_proposal WHERE id=?", proposal))
                .isEqualTo("REJECTED");
        assertThat(value("SELECT status FROM quote WHERE id=?", oldQuote)).isEqualTo("SUPERSEDED");
        assertCode(
                post(buyer, "/api/purchases/" + purchase + "/accept", version(refused), 409),
                "QUOTE_REQUIRED");
        var fresh = review(refused, original, false);
        assertThat(issuedQuote(purchase)).isNotEqualTo(oldQuote);
        assertThat(fresh.get("orders").get(0).get("lines").get(0).get("productId").asText())
                .isEqualTo(original.toString());
        assertThat(reserved(original)).isEqualTo(1);
        assertThat(reserved(replacement)).isZero();
    }

    @Test
    void acceptingAlternativePreservesOldLinesAndRequiresNewReviewQuoteAndConsent()
            throws Exception {
        var quoted = review(create(null), original, false);
        UUID purchase = uuid(quoted, "id"),
                oldItem = activeItem(purchase),
                oldQuote = issuedQuote(purchase);
        UUID oldLine =
                db.sql("SELECT id FROM order_item WHERE order_id=? AND active")
                        .params(orderId(quoted))
                        .query(UUID.class)
                        .single();
        String oldSnapshot = value("SELECT snapshot::text FROM order_item WHERE id=?", oldLine);
        String oldQuoteSnapshot =
                value("SELECT snapshot::text FROM quote_line WHERE quote_id=?", oldQuote);
        var pending = propose(quoted, replacement, 1, false, null, 200);
        UUID proposal = pendingProposal(purchase);
        var acceptedChange = respond(pending, proposal, "ACCEPT", 200);
        UUID newItem = activeItem(purchase);
        assertThat(newItem).isNotEqualTo(oldItem);
        assertThat(
                        db.sql("SELECT supersedes_id FROM purchase_item WHERE id=?")
                                .params(newItem)
                                .query(UUID.class)
                                .single())
                .isEqualTo(oldItem);
        assertThat(count("SELECT count(*) FROM purchase_item WHERE id=? AND NOT active", oldItem))
                .isEqualTo(1);
        assertThat(value("SELECT snapshot::text FROM order_item WHERE id=?", oldLine))
                .isEqualTo(oldSnapshot);
        assertThat(value("SELECT snapshot::text FROM quote_line WHERE quote_id=?", oldQuote))
                .isEqualTo(oldQuoteSnapshot);
        assertThat(count("SELECT count(*) FROM order_item WHERE id=? AND NOT active", oldLine))
                .isEqualTo(1);
        assertThat(acceptedChange.get("status").asText()).isEqualTo("ROUTING");
        assertCode(
                post(buyer, "/api/purchases/" + purchase + "/accept", version(acceptedChange), 409),
                "QUOTE_REQUIRED");
        var newQuote = review(acceptedChange, replacement, false);
        assertThat(new BigDecimal(newQuote.get("total").asText())).isEqualByComparingTo("1700.00");
        UUID newQuoteId = issuedQuote(purchase);
        assertThat(newQuoteId).isNotEqualTo(oldQuote);
        assertThat(value("SELECT product_id::text FROM quote_line WHERE quote_id=?", oldQuote))
                .isEqualTo(original.toString());
        assertThat(value("SELECT product_id::text FROM quote_line WHERE quote_id=?", newQuoteId))
                .isEqualTo(replacement.toString());
        assertThat(reserved(original)).isZero();
        assertThat(reserved(replacement)).isEqualTo(1);
        var confirmed =
                post(buyer, "/api/purchases/" + purchase + "/accept", version(newQuote), 200);
        assertThat(confirmed.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(
                        db.sql("SELECT accepted_quote_id FROM purchase WHERE id=?")
                                .params(purchase)
                                .query(UUID.class)
                                .single())
                .isEqualTo(newQuoteId);
        assertThat(value("SELECT status FROM quote WHERE id=?", oldQuote)).isEqualTo("SUPERSEDED");
        assertThat(reserved(replacement)).isEqualTo(1);
        assertCode(respond(confirmed, proposal, "ACCEPT", 409), "SUBSTITUTION_CLOSED");
    }

    @Test
    void prescriptionAlternativeCannotReuseAnOriginalWithoutPrescription() throws Exception {
        sql("UPDATE product SET requires_prescription=true WHERE id=?", replacement);
        var purchase = create(null);
        assertCode(propose(purchase, replacement, 1, true, null, 409), "NEW_PRESCRIPTION_REQUIRED");
        assertThat(
                        count(
                                "SELECT count(*) FROM substitution_proposal WHERE purchase_id=?",
                                uuid(purchase, "id")))
                .isZero();
        assertThat(
                        value(
                                "SELECT product_id::text FROM purchase_item WHERE purchase_id=? AND"
                                        + " active",
                                uuid(purchase, "id")))
                .isEqualTo(original.toString());
    }

    @Test
    void clinicalProposalRequiresUnchangedQuantityAttestationOwnedEvidenceAndExactPharmacistScope()
            throws Exception {
        sql(
                "UPDATE product SET requires_prescription=true WHERE id IN (?,?)",
                original,
                replacement);
        UUID rx = upload(buyer, "Receta original"),
                evidence = upload(pharmacist, "Justificación clínica de sustitución");
        var purchase = create(rx);
        assertCode(
                propose(purchase, replacement, 1, false, evidence, 400),
                "SUBSTITUTION_CLINICAL_REQUIRED");
        assertCode(
                propose(purchase, replacement, 2, true, evidence, 400),
                "SUBSTITUTION_CLINICAL_REQUIRED");
        assertCode(propose(purchase, replacement, 1, true, null, 400), "EVIDENCE_REQUIRED");
        assertCode(propose(purchase, replacement, 1, true, rx, 403), "DOCUMENT_SCOPE_INVALID");
        assertThat(
                        count(
                                "SELECT count(*) FROM substitution_proposal WHERE purchase_id=?",
                                uuid(purchase, "id")))
                .isZero();
        var pending = propose(purchase, replacement, 1, true, evidence, 200);
        assertThat(
                        count(
                                "SELECT count(*) FROM document WHERE id=? AND branch_id IS NULL",
                                evidence))
                .isEqualTo(1);
        var exactProfessional = professional(branch, "PHARMACIST");
        var wrongRole = professional(branch, "PHARMACY");
        var otherBranchProfessional = professional(branch(), "PHARMACIST");
        mvc.perform(
                        get("/api/documents/" + evidence + "/metadata")
                                .session(exactProfessional.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/" + evidence + "/metadata").session(wrongRole.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/documents/" + evidence + "/metadata")
                                .session(otherBranchProfessional.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/" + evidence + "/metadata").session(buyer.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/documents/" + evidence + "/metadata")
                                .session(login("admin@demo.invalid").session()))
                .andExpect(status().isForbidden());
        var buyerProjection =
                mvc.perform(
                                get("/api/purchases/" + pending.get("id").asText())
                                        .session(buyer.session()))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(
                        json.readTree(buyerProjection.getResponse().getContentAsString())
                                .get("substitutions")
                                .get(0)
                                .has("evidenceDocumentId"))
                .isFalse();
        var changed = respond(pending, pendingProposal(uuid(pending, "id")), "ACCEPT", 200);
        assertCode(
                post(
                        buyer,
                        "/api/purchases/" + changed.get("id").asText() + "/accept",
                        version(changed),
                        409),
                "QUOTE_REQUIRED");
        var newQuote = review(changed, replacement, true);
        assertThat(newQuote.get("status").asText()).isEqualTo("QUOTED");
        assertThat(
                        count(
                                "SELECT count(*) FROM review WHERE order_id=? AND"
                                        + " kind='PRESCRIPTION' AND status='APPROVED'",
                                orderId(newQuote)))
                .isEqualTo(1);
        assertThat(reserved(original)).isZero();
        assertThat(reserved(replacement)).isEqualTo(1);
    }

    @Test
    void clinicalRejectionCannotBeResurrectedBySubstitutionOrRerouting() throws Exception {
        sql(
                "UPDATE product SET requires_prescription=true WHERE id IN (?,?)",
                original,
                replacement);
        var purchase = create(upload(buyer, "Receta que será rechazada"));
        var rejected =
                post(
                        pharmacist,
                        "/api/orders/" + orderId(purchase) + "/review",
                        Map.of(
                                "expectedVersion",
                                orderVersion(purchase),
                                "decision",
                                "REJECT",
                                "reason",
                                "Incompatibilidad clínica verificada",
                                "rejectionKind",
                                "CLINICAL"),
                        200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertCode(
                propose(
                        rejected,
                        replacement,
                        1,
                        true,
                        upload(pharmacist, "Evidencia posterior al rechazo"),
                        409),
                "SUBSTITUTION_STATE_INVALID");
        assertCode(
                post(
                        buyer,
                        "/api/purchases/" + rejected.get("id").asText() + "/reroute",
                        version(rejected),
                        409),
                "REROUTE_STATE_INVALID");
        assertThat(
                        count(
                                "SELECT count(*) FROM substitution_proposal WHERE purchase_id=?",
                                uuid(rejected, "id")))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM prescription rx JOIN prescription_item ri ON"
                                    + " ri.prescription_id=rx.id JOIN purchase_item pi ON"
                                    + " pi.prescription_item_id=ri.id WHERE pi.purchase_id=? AND"
                                    + " rx.status='REJECTED'",
                                uuid(rejected, "id")))
                .isEqualTo(1);
    }

    private JsonNode create(UUID prescription) throws Exception {
        var profile =
                db.sql("SELECT name,birth_date::text birth_date FROM app_user WHERE id=?")
                        .params(BUYER)
                        .query()
                        .singleRow();
        var body = new LinkedHashMap<String, Object>();
        body.put("beneficiaryName", profile.get("name"));
        body.put("beneficiaryBirthDate", profile.get("birth_date"));
        body.put("mode", "PICKUP");
        body.put("destinationLat", -32.889);
        body.put("destinationLon", -68.845);
        body.put("destinationAddress", "Domicilio aislado test");
        body.put("preferredBranchId", branch);
        body.put("items", List.of(Map.of("productId", original, "quantity", 1)));
        if (prescription != null) body.put("prescriptionDocumentId", prescription);
        return post(buyer, "/api/purchases", body, 200);
    }

    private JsonNode review(JsonNode purchase, UUID product, boolean clinical) throws Exception {
        return post(
                pharmacist,
                "/api/orders/" + orderId(purchase) + "/review",
                reviewRequest(purchase, product, clinical),
                200);
    }

    private Map<String, Object> reviewRequest(JsonNode purchase, UUID product, boolean clinical) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", orderVersion(purchase));
        body.put("decision", "APPROVE");
        body.put("reason", "Identidad y condiciones verificadas manualmente en test");
        body.put("authorizationChecked", true);
        var line = new LinkedHashMap<String, Object>();
        line.put("productId", product);
        line.put("coverageAmount", "0.00");
        if (clinical) {
            line.put("authorizedQuantity", 1);
            body.put("originVerified", true);
            body.put("prescriptionReference", "RX-SUB-" + purchase.get("id").asText());
            body.put("prescriptionIssuer", "Profesional ficticio de prueba");
            body.put("prescriptionExpiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        }
        body.put("lines", List.of(line));
        return body;
    }

    private JsonNode propose(
            JsonNode purchase,
            UUID product,
            int quantity,
            boolean compatible,
            UUID evidence,
            int expected)
            throws Exception {
        var input = new LinkedHashMap<String, Object>();
        input.put("expectedVersion", orderVersion(purchase));
        input.put(
                "lineId",
                db.sql("SELECT id FROM order_item WHERE order_id=? AND active")
                        .params(orderId(purchase))
                        .query(UUID.class)
                        .single());
        input.put("productId", product);
        input.put("quantity", quantity);
        input.put("reason", "Presentación alternativa revisada por profesional");
        input.put("prescriptionCompatible", compatible);
        input.put("evidenceDocumentId", evidence);
        return post(
                pharmacist, "/api/orders/" + orderId(purchase) + "/substitutions", input, expected);
    }

    private JsonNode respond(JsonNode purchase, UUID proposal, String decision, int expected)
            throws Exception {
        return post(
                buyer,
                "/api/purchases/" + purchase.get("id").asText() + "/substitutions/" + proposal,
                Map.of("expectedVersion", purchase.get("version").asLong(), "decision", decision),
                expected);
    }

    private UUID upload(Session session, String content) throws Exception {
        var file =
                new MockMultipartFile(
                        "file",
                        "receta-test.pdf",
                        "application/pdf",
                        ("%PDF-1.4\n% Documento aislado de prueba "
                                        + content
                                        + " "
                                        + UUID.randomUUID()
                                        + "\n%%EOF")
                                .getBytes(StandardCharsets.UTF_8));
        var result =
                mvc.perform(
                                multipart("/api/documents")
                                        .file(file)
                                        .param("kind", "RECETA")
                                        .session(session.session())
                                        .header(session.header(), session.token()))
                        .andExpect(status().isCreated())
                        .andReturn();
        return uuid(json.readTree(result.getResponse().getContentAsString()), "id");
    }

    private Session professional(UUID scopedBranch, String role) throws Exception {
        UUID user = UUID.randomUUID();
        String email = "substitution-" + user + "@example.test";
        sql(
                "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                    + " VALUES(?,?, 'Personal aislado test',?,DATE"
                    + " '1990-01-01',true,lpad(floor(random()*1000000000000)::bigint::text,12,'0'))",
                user,
                email,
                context.getBean(PasswordEncoder.class).encode("DemoMedicapp2026!"));
        sql("INSERT INTO user_role(user_id,role) VALUES(?,?)", user, role);
        membership(user, scopedBranch, role);
        return login(email);
    }

    private UUID branch() {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO"
                    + " branch(id,name,address,latitude,longitude,verified,agreement_accepted,pickup_enabled)"
                    + " VALUES(?,'Sucursal sustitución aislada','Domicilio ficticio"
                    + " test',-32.889,-68.845,true,true,true)",
                id);
        return id;
    }

    private UUID product(String name, boolean prescription, int price) {
        UUID id = UUID.randomUUID();
        sql(
                "INSERT INTO product(id,code,name,presentation,requires_prescription)"
                        + " VALUES(?,?,?,'Caja aislada de prueba',?)",
                id,
                "TEST-SUB-" + id,
                name + " sustitución test",
                prescription);
        sql(
                "INSERT INTO branch_stock(branch_id,product_id,quantity,price) VALUES(?,?,3,?)",
                branch,
                id,
                price);
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

    private UUID activeItem(UUID purchase) {
        return db.sql("SELECT id FROM purchase_item WHERE purchase_id=? AND active")
                .params(purchase)
                .query(UUID.class)
                .single();
    }

    private UUID issuedQuote(UUID purchase) {
        return db.sql("SELECT id FROM quote WHERE purchase_id=? AND status='ISSUED'")
                .params(purchase)
                .query(UUID.class)
                .single();
    }

    private UUID pendingProposal(UUID purchase) {
        return db.sql(
                        "SELECT id FROM substitution_proposal WHERE purchase_id=? AND"
                                + " status='PENDING'")
                .params(purchase)
                .query(UUID.class)
                .single();
    }

    private int reserved(UUID product) {
        return db.sql(
                        "SELECT reserved_quantity FROM branch_stock WHERE branch_id=? AND"
                                + " product_id=?")
                .params(branch, product)
                .query(Integer.class)
                .single();
    }

    private UUID orderId(JsonNode purchase) {
        return uuid(purchase.get("orders").get(0), "id");
    }

    private long orderVersion(JsonNode purchase) {
        return purchase.get("orders").get(0).get("version").asLong();
    }

    private UUID uuid(JsonNode node, String name) {
        return UUID.fromString(node.get(name).asText());
    }

    private Map<String, Object> version(JsonNode purchase) {
        return Map.of("expectedVersion", purchase.get("version").asLong());
    }

    private void assertCode(JsonNode result, String code) {
        assertThat(result.get("code").asText()).isEqualTo(code);
    }

    private void sql(String statement, Object... args) {
        db.sql(statement).params(args).update();
    }

    private long count(String statement, Object... args) {
        return db.sql(statement).params(args).query(Long.class).single();
    }

    private String value(String statement, Object... args) {
        return db.sql(statement).params(args).query(String.class).single();
    }

    private JsonNode post(Session session, String path, Object body, int expected)
            throws Exception {
        var result =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post(path)
                                        .session(session.session())
                                        .header(session.header(), session.token())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
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
        var csrf = csrf(null);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/auth/login")
                                .with(
                                        request -> {
                                            request.setRemoteAddr(
                                                    "127.3."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255)
                                                            + "."
                                                            + ThreadLocalRandom.current()
                                                                    .nextInt(1, 255));
                                            return request;
                                        })
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", email)
                                .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isOk());
        return csrf(csrf.session());
    }

    private Session csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsString());
        return new Session(
                (MockHttpSession) response.getRequest().getSession(),
                body.get("headerName").asText(),
                body.get("token").asText());
    }

    private record Session(MockHttpSession session, String header, String token) {}
}
