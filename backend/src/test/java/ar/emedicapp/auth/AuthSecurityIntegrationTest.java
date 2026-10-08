package ar.emedicapp.auth;

import ar.emedicapp.appbootstrap.DemoIds;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"emedicapp.mode=DEMO"})
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class AuthSecurityIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;
    @Autowired JdbcClient jdbc;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        properties.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        properties.add(
                "spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void publicBootstrapExposesModeAndCapabilitiesWithoutCredentials() throws Exception {
        String body =
                mvc.perform(get("/api/bootstrap"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("DEMO").doesNotContain("DemoMedicapp2026", "password", "jdbc:");
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresCsrfAndRotatesTheSession() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .param("email", "comprador@demo.invalid")
                                .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isForbidden());
        var csrf = csrf(null);
        String before = csrf.session().getId();
        mvc.perform(
                        post("/api/auth/login")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", "comprador@demo.invalid")
                                .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray());
        assertThat(csrf.session().getId()).isNotEqualTo(before);
        mvc.perform(get("/api/auth/me").session(csrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("comprador@demo.invalid"));
        var refreshed = csrf(csrf.session());
        mvc.perform(
                        post("/api/auth/logout")
                                .session(refreshed.session())
                                .header(refreshed.header(), refreshed.token()))
                .andExpect(status().isOk());
        assertThat(refreshed.session().isInvalid()).isTrue();
    }

    @Test
    void registrationRejectsMinorAndDeactivationRevokesSession() throws Exception {
        var csrf = csrf(null);
        mvc.perform(
                        post("/api/auth/register")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "minor@example.test",
                                                        "password",
                                                        "ValidPassword2026!",
                                                        "name",
                                                        "Persona",
                                                        "birthDate",
                                                        LocalDate.now()
                                                                .minusYears(17)
                                                                .toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADULT_REQUIRED"));
        String email = "auth-" + UUID.randomUUID() + "@example.test";
        String body =
                mvc.perform(
                                post("/api/auth/register")
                                        .session(csrf.session())
                                        .header(csrf.header(), csrf.token())
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                email,
                                                                "password",
                                                                "ValidPassword2026!",
                                                                "name",
                                                                "Persona Test",
                                                                "birthDate",
                                                                "1990-01-01"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID userId = UUID.fromString(mapper.readTree(body).get("id").asText());
        var loggedIn = login(email, "ValidPassword2026!");
        jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id", userId).update();
        mvc.perform(get("/api/auth/me").session(loggedIn.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        assertThat(loggedIn.session().isInvalid()).isTrue();
    }

    @Test
    void clinicalDocumentAccessRequiresPharmacistMembershipInTheActualOrderBranch()
            throws Exception {
        var buyer = login("comprador@demo.invalid", "DemoMedicapp2026!");
        var upload =
                mvc.perform(
                                multipart("/api/documents")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "receta-ficticia.pdf",
                                                        "application/pdf",
                                                        "%PDF-1.4\nSOLO-TEST".getBytes()))
                                        .param("kind", "RECETA")
                                        .session(buyer.session())
                                        .header(buyer.header(), buyer.token()))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID document =
                UUID.fromString(
                        mapper.readTree(upload.getResponse().getContentAsString())
                                .get("id")
                                .asText());
        var admin = login("admin@demo.invalid", "DemoMedicapp2026!");
        mvc.perform(get("/api/documents/" + document).session(admin.session()))
                .andExpect(status().isForbidden());

        UUID buyerId = DemoIds.id("user:comprador@demo.invalid");
        UUID beneficiary = UUID.randomUUID(),
                prescription = UUID.randomUUID(),
                item = UUID.randomUUID();
        UUID purchase = UUID.randomUUID(), order = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID center = DemoIds.id("branch:centro"),
                east = DemoIds.id("branch:este"),
                product = DemoIds.id("product:d");
        jdbc.sql(
                        "INSERT INTO beneficiary(id,owner_user_id,name,birth_date) VALUES(:id,:owner,'Adulto Test',DATE '1990-01-01')")
                .param("id", beneficiary)
                .param("owner", buyerId)
                .update();
        jdbc.sql(
                        "INSERT INTO prescription(id,owner_user_id,beneficiary_id,document_id) VALUES(:id,:owner,:beneficiary,:document)")
                .param("id", prescription)
                .param("owner", buyerId)
                .param("beneficiary", beneficiary)
                .param("document", document)
                .update();
        jdbc.sql(
                        "INSERT INTO prescription_item(id,prescription_id,product_id,authorized_quantity) VALUES(:id,:rx,:product,1)")
                .param("id", item)
                .param("rx", prescription)
                .param("product", product)
                .update();
        jdbc.sql(
                        "INSERT INTO purchase(id,buyer_user_id,beneficiary_id,mode,latitude,longitude,status) VALUES(:id,:buyer,:beneficiary,'PICKUP',-32.889,-68.845,'QUOTED')")
                .param("id", purchase)
                .param("buyer", buyerId)
                .param("beneficiary", beneficiary)
                .update();
        jdbc.sql(
                        "INSERT INTO purchase_item(id,purchase_id,product_id,quantity,prescription_item_id) VALUES(:id,:purchase,:product,1,:rxItem)")
                .param("id", UUID.randomUUID())
                .param("purchase", purchase)
                .param("product", product)
                .param("rxItem", item)
                .update();
        jdbc.sql("INSERT INTO child_order(id,purchase_id,branch_id) VALUES(:id,:purchase,:branch)")
                .param("id", order)
                .param("purchase", purchase)
                .param("branch", center)
                .update();
        String email = "clinical-" + actor + "@example.test";
        String passwordHash =
                context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                        .encode("ValidPassword2026!");
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date) VALUES(:id,:email,'Profesional Test',:hash,DATE '1990-01-01')")
                .param("id", actor)
                .param("email", email)
                .param("hash", passwordHash)
                .update();
        for (String role : new String[] {"PHARMACIST", "DRIVER"})
            jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,:role)")
                    .param("id", actor)
                    .param("role", role)
                    .update();
        membership(actor, center, "DRIVER");
        membership(actor, east, "PHARMACIST");
        var professional = login(email, "ValidPassword2026!");
        mvc.perform(get("/api/documents/" + document).session(professional.session()))
                .andExpect(status().isForbidden());
        membership(actor, center, "PHARMACIST");
        mvc.perform(get("/api/documents/" + document).session(professional.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
        jdbc.sql("UPDATE child_order SET status='CANCELLED' WHERE id=:id")
                .param("id", order)
                .update();
        mvc.perform(get("/api/documents/" + document).session(professional.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void evidenceTypesDistinguishManualAssessmentFromPaymentAndDelivery() throws Exception {
        UUID center = DemoIds.id("branch:centro");
        var pharmacy = login("farmacia@demo.invalid", "DemoMedicapp2026!");
        mvc.perform(
                        multipart("/api/documents")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "revision-manual.pdf",
                                                "application/pdf",
                                                "%PDF-1.4\nSOLO-TEST".getBytes()))
                                .param("kind", "OTRO")
                                .param("branchId", center.toString())
                                .session(pharmacy.session())
                                .header(pharmacy.header(), pharmacy.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("OTHER"));
        mvc.perform(
                        multipart("/api/documents")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "pago.pdf",
                                                "application/pdf",
                                                "%PDF-1.4\nSOLO-TEST".getBytes()))
                                .param("kind", "EVIDENCIA")
                                .param("branchId", center.toString())
                                .session(pharmacy.session())
                                .header(pharmacy.header(), pharmacy.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("PAYMENT_RECEIPT"));
        var driver = login("cadete@demo.invalid", "DemoMedicapp2026!");
        mvc.perform(
                        multipart("/api/documents")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "recepcion.pdf",
                                                "application/pdf",
                                                "%PDF-1.4\nSOLO-TEST".getBytes()))
                                .param("kind", "ENTREGA")
                                .param("branchId", center.toString())
                                .session(driver.session())
                                .header(driver.header(), driver.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("DELIVERY_PROOF"));
        mvc.perform(
                        multipart("/api/documents")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "pago.pdf",
                                                "application/pdf",
                                                "%PDF-1.4\nSOLO-TEST".getBytes()))
                                .param("kind", "EVIDENCIA")
                                .param("branchId", center.toString())
                                .session(driver.session())
                                .header(driver.header(), driver.token()))
                .andExpect(status().isForbidden());
    }

    private void membership(UUID user, UUID branch, String role) {
        jdbc.sql(
                        "INSERT INTO membership(id,user_id,branch_id,role) VALUES(:id,:user,:branch,:role)")
                .param("id", UUID.randomUUID())
                .param("user", user)
                .param("branch", branch)
                .param("role", role)
                .update();
    }

    private Session login(String email, String password) throws Exception {
        var csrf = csrf(null);
        mvc.perform(
                        post("/api/auth/login")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", email)
                                .param("password", password))
                .andExpect(status().isOk());
        return csrf(csrf.session());
    }

    private Session csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return new Session(
                (MockHttpSession) result.getRequest().getSession(),
                body.get("token").asText(),
                body.get("headerName").asText());
    }

    private record Session(MockHttpSession session, String token, String header) {}
}
