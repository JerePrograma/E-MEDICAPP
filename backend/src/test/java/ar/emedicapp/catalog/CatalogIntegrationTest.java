package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class CatalogIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        values.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        values.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void concurrentCountsWithSameVersionHaveExactlyOneWinner() throws Exception {
        UUID branch = branch(), product = product();
        stock(branch, product, 1, 0);
        var first = login("admin@demo.invalid");
        var second = login("admin@demo.invalid");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var one =
                CompletableFuture.supplyAsync(
                        () -> countUpdate(first, branch, product, 5, ready, start));
        var two =
                CompletableFuture.supplyAsync(
                        () -> countUpdate(second, branch, product, 6, ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        var state =
                jdbc.sql(
                                "SELECT quantity,version FROM branch_stock WHERE branch_id=:b AND product_id=:p")
                        .param("b", branch)
                        .param("p", product)
                        .query()
                        .singleRow();
        assertThat(((Number) state.get("quantity")).intValue()).isIn(5, 6);
        assertThat(((Number) state.get("version")).longValue()).isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM stock_movement WHERE branch_id=:b AND product_id=:p")
                                .param("b", branch)
                                .param("p", product)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void reservedUnitsCannotBeRemovedByPhysicalCount() throws Exception {
        UUID branch = branch(), product = product();
        stock(branch, product, 3, 2);
        var admin = login("admin@demo.invalid");
        mvc.perform(
                        put(path(branch, product))
                                .session(admin.session())
                                .header(admin.header(), admin.token())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "quantity",
                                                        1,
                                                        "price",
                                                        "100.00",
                                                        "expectedVersion",
                                                        1,
                                                        "reason",
                                                        "Conteo test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_RESERVED"));
        assertThat(
                        jdbc.sql(
                                        "SELECT quantity FROM branch_stock WHERE branch_id=:b AND product_id=:p")
                                .param("b", branch)
                                .param("p", product)
                                .query(Integer.class)
                                .single())
                .isEqualTo(3);
    }

    @Test
    void staffRoleInOneBranchDoesNotAuthorizeDriverBranchStock() throws Exception {
        UUID a = branch(), b = branch(), user = UUID.randomUUID();
        String email = "catalog-" + user + "@example.test";
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date) VALUES(:id,:email,'Personal Test',:password,DATE '1990-01-01')")
                .param("id", user)
                .param("email", email)
                .param(
                        "password",
                        context.getBean(PasswordEncoder.class).encode("DemoMedicapp2026!"))
                .update();
        for (var role : List.of("PHARMACIST", "DRIVER"))
            jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,:role)")
                    .param("id", user)
                    .param("role", role)
                    .update();
        member(user, a, "PHARMACIST");
        member(user, b, "DRIVER");
        var session = login(email);
        mvc.perform(get("/api/branches/" + a + "/stock").session(session.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/branches/" + b + "/stock").session(session.session()))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET active=false WHERE user_id=:id AND branch_id=:branch")
                .param("id", user)
                .param("branch", a)
                .update();
        mvc.perform(get("/api/branches/" + a + "/stock").session(session.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void lotCountAndQuarantineProtectReservationsAndAggregate() throws Exception {
        UUID branch = branch(), product = product();
        stock(branch, product, 10, 0);
        var admin = login("admin@demo.invalid");
        var created =
                mvc.perform(
                                post(path(branch, product) + "/lots")
                                        .session(admin.session())
                                        .header(admin.header(), admin.token())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "lotCode",
                                                                "LOT-TEST",
                                                                "quantity",
                                                                10,
                                                                "status",
                                                                "AVAILABLE",
                                                                "expectedVersion",
                                                                0,
                                                                "stockVersion",
                                                                1,
                                                                "reason",
                                                                "Lote test"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var result = json.readTree(created);
        var lot = UUID.fromString(result.get("lots").get(0).get("id").asText());
        jdbc.sql("UPDATE branch_stock SET reserved_quantity=4 WHERE branch_id=:b AND product_id=:p")
                .param("b", branch)
                .param("p", product)
                .update();
        jdbc.sql("UPDATE stock_lot SET reserved_quantity=4 WHERE id=:id").param("id", lot).update();
        mvc.perform(
                        put(path(branch, product) + "/lots/" + lot)
                                .session(admin.session())
                                .header(admin.header(), admin.token())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "lotCode",
                                                        "LOT-TEST",
                                                        "quantity",
                                                        10,
                                                        "status",
                                                        "QUARANTINED",
                                                        "expectedVersion",
                                                        1,
                                                        "stockVersion",
                                                        2,
                                                        "reason",
                                                        "Aislamiento test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOT_RESERVED"));
        assertThat(
                        jdbc.sql("SELECT status FROM stock_lot WHERE id=:id")
                                .param("id", lot)
                                .query(String.class)
                                .single())
                .isEqualTo("AVAILABLE");
        assertThat(
                        jdbc.sql(
                                        "SELECT quantity FROM branch_stock WHERE branch_id=:b AND product_id=:p")
                                .param("b", branch)
                                .param("p", product)
                                .query(Integer.class)
                                .single())
                .isEqualTo(10);
    }

    @Test
    void driverSpecialCapabilitiesRequireExactBranchEvidenceAndAuditedVerification()
            throws Exception {
        UUID branch = branch(), otherBranch = branch(), user = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date) VALUES(:id,:email,'Cadete Test','unused',DATE '1990-01-01')")
                .param("id", user)
                .param("email", "capability-" + user + "@example.test")
                .update();
        UUID wrongEvidence = capabilityDocument(user, otherBranch),
                rightEvidence = capabilityDocument(user, branch);
        var admin = login("admin@demo.invalid");
        var payload =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "userId",
                                user,
                                "branchId",
                                branch,
                                "role",
                                "DRIVER",
                                "active",
                                true,
                                "coldChainCapable",
                                true,
                                "restrictedCapable",
                                false,
                                "version",
                                0,
                                "capabilityEvidenceDocumentId",
                                wrongEvidence));
        mvc.perform(
                        post("/api/admin/memberships")
                                .session(admin.session())
                                .header(admin.header(), admin.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRIVER_CAPABILITY_EVIDENCE"));
        assertThat(
                        jdbc.sql("SELECT count(*) FROM membership WHERE user_id=:id")
                                .param("id", user)
                                .query(Long.class)
                                .single())
                .isZero();
        payload.put("capabilityEvidenceDocumentId", rightEvidence);
        var saved =
                mvc.perform(
                                post("/api/admin/memberships")
                                        .session(admin.session())
                                        .header(admin.header(), admin.token())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(payload)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.coldChainCapable").value(true))
                        .andExpect(jsonPath("$.capabilityVerifiedAt").isNotEmpty())
                        .andExpect(jsonPath("$.capabilityVerifiedBy").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID membership = UUID.fromString(json.readTree(saved).get("id").asText());
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_event WHERE entity_id=:id AND action='MEMBERSHIP_CREATED'")
                                .param("id", membership)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        payload.put("version", 1);
        payload.put("coldChainCapable", false);
        payload.remove("capabilityEvidenceDocumentId");
        mvc.perform(
                        put("/api/admin/memberships/" + membership)
                                .session(admin.session())
                                .header(admin.header(), admin.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coldChainCapable").value(false))
                .andExpect(jsonPath("$.capabilityVerifiedAt").doesNotExist());
    }

    private UUID capabilityDocument(UUID owner, UUID branch) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO document(id,owner_user_id,kind,branch_id,original_name,content_type,size_bytes,storage_key) VALUES(:id,:owner,'CAPABILITY',:branch,'synthetic.pdf','application/pdf',10,:key)")
                .param("id", id)
                .param("owner", owner)
                .param("branch", branch)
                .param("key", "synthetic-test/" + id)
                .update();
        return id;
    }

    private int countUpdate(
            Session session,
            UUID branch,
            UUID product,
            int quantity,
            CountDownLatch ready,
            CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("No inició concurrencia");
            return mvc.perform(
                            put(path(branch, product))
                                    .session(session.session())
                                    .header(session.header(), session.token())
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "quantity",
                                                            quantity,
                                                            "price",
                                                            "100.00",
                                                            "expectedVersion",
                                                            1,
                                                            "reason",
                                                            "Conteo concurrente test"))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UUID branch() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO branch(id,name,address,latitude,longitude,verified,agreement_accepted) VALUES(:id,'Sucursal Test','Domicilio sintético',-32.89,-68.85,true,true)")
                .param("id", id)
                .update();
        return id;
    }

    private UUID product() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO product(id,code,name,presentation) VALUES(:id,:code,'Producto Test','Caja test')")
                .param("id", id)
                .param("code", "TEST-" + id)
                .update();
        return id;
    }

    private void stock(UUID branch, UUID product, int quantity, int reserved) {
        jdbc.sql(
                        "INSERT INTO branch_stock(branch_id,product_id,quantity,reserved_quantity,price) VALUES(:b,:p,:q,:r,100.00)")
                .param("b", branch)
                .param("p", product)
                .param("q", quantity)
                .param("r", reserved)
                .update();
    }

    private void member(UUID user, UUID branch, String role) {
        jdbc.sql("INSERT INTO membership(id,user_id,branch_id,role) VALUES(:id,:u,:b,:r)")
                .param("id", UUID.randomUUID())
                .param("u", user)
                .param("b", branch)
                .param("r", role)
                .update();
    }

    private String path(UUID branch, UUID product) {
        return "/api/branches/" + branch + "/stock/" + product;
    }

    private Session login(String email) throws Exception {
        var csrf = csrf(null);
        mvc.perform(
                        post("/api/auth/login")
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
        var value = json.readTree(response.getResponse().getContentAsString());
        return new Session(
                (MockHttpSession) response.getRequest().getSession(),
                value.get("headerName").asText(),
                value.get("token").asText());
    }

    private record Session(MockHttpSession session, String header, String token) {}
}
