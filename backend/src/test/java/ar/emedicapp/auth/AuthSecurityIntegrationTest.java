package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(properties = {"emedicapp.mode=DEMO"})
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class AuthSecurityIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;
    @Autowired JdbcClient jdbc;
    @MockitoBean EmailSender mail;
    @MockitoBean LoginRateLimiter limiter;
    @Autowired AuthTokenRepository tokens;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
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
        when(limiter.allow(anyString())).thenReturn(true);
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
    void registrationRejectsMinorAndRemainsPendingWithoutSmtp() throws Exception {
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
                                                                "1990-01-01",
                                                                "dni",
                                                                randomDni(),
                                                                "phone",
                                                                "2615550123",
                                                                "address",
                                                                "San Martín 123, Mendoza"))))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("EMAIL_CONFIGURATION_PENDING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("\"id\"", "token", "password");
        assertThat(
                        jdbc.sql("SELECT email_verified FROM app_user WHERE email=:email")
                                .param("email", email)
                                .query(Boolean.class)
                                .single())
                .isFalse();
        mvc.perform(
                        post("/api/auth/login")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", email)
                                .param("password", "ValidPassword2026!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        verify(mail, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void verificationAndResetAreSingleUseAndRecoveryRevokesExistingSessions() throws Exception {
        when(mail.configured()).thenReturn(true);
        String email = "verified-" + UUID.randomUUID() + "@example.test", dni = randomDni();
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
                                                        email,
                                                        "password",
                                                        "ValidPassword2026!",
                                                        "name",
                                                        "Persona Test",
                                                        "birthDate",
                                                        "1990-01-01",
                                                        "dni",
                                                        dni,
                                                        "phone",
                                                        "+54 261 555-0123",
                                                        "address",
                                                        "San Martín 123, Mendoza"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("EMAIL_VERIFICATION_PENDING"));
        var verification = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), eq("VERIFY_EMAIL"), verification.capture(), eq(30));
        mvc.perform(
                        post("/api/auth/verification/confirm")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("token", verification.getValue()))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/auth/login")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .param("email", email)
                                .param("password", "WrongPassword2026!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(
                        post("/api/auth/verification/confirm")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("token", verification.getValue()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMAIL_VERIFIED"));
        mvc.perform(
                        post("/api/auth/verification/confirm")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("token", verification.getValue()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        var loggedIn = login(email, "ValidPassword2026!");
        mvc.perform(get("/api/auth/me").session(loggedIn.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.dni").value(dni))
                .andExpect(jsonPath("$.phone").value("+542615550123"))
                .andExpect(jsonPath("$.address").value("San Martín 123, Mendoza"));
        mvc.perform(
                        post("/api/auth/password/request-reset")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
        var reset = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), eq("RESET_PASSWORD"), reset.capture(), eq(15));
        mvc.perform(
                        post("/api/auth/password/reset")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "token",
                                                        reset.getValue(),
                                                        "password",
                                                        "NewValidPassword2026!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_RESET"));
        mvc.perform(get("/api/auth/me").session(loggedIn.session()))
                .andExpect(status().isUnauthorized());
        assertThat(loggedIn.session().isInvalid()).isTrue();
        mvc.perform(
                        post("/api/auth/password/reset")
                                .session(csrf.session())
                                .header(csrf.header(), csrf.token())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "token",
                                                        reset.getValue(),
                                                        "password",
                                                        "NewValidPassword2026!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
        login(email, "NewValidPassword2026!");
    }

    @Test
    void deactivationRevokesSession() throws Exception {
        String email = "inactive-" + UUID.randomUUID() + "@example.test";
        UUID userId = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO"
                                + " app_user(id,email,name,password_hash,birth_date,email_verified)"
                                + " VALUES(:id,:email,'Persona Test',:hash,DATE '1990-01-01',true)")
                .param("id", userId)
                .param("email", email)
                .param(
                        "hash",
                        context.getBean(
                                        org.springframework.security.crypto.password.PasswordEncoder
                                                .class)
                                .encode("ValidPassword2026!"))
                .update();
        jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,'BUYER')")
                .param("id", userId)
                .update();
        var loggedIn = login(email, "ValidPassword2026!");
        jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id", userId).update();
        mvc.perform(get("/api/auth/me").session(loggedIn.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        assertThat(loggedIn.session().isInvalid()).isTrue();
    }

    @Test
    void tokenExpiryReplacementAndConcurrentConsumptionAreEnforcedByDatabase() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date)"
                                + " VALUES(:id,:email,'Persona Token Test','not-a-login-hash',DATE"
                                + " '1990-01-01')")
                .param("id", user)
                .param("email", "token-" + user + "@example.test")
                .update();
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        var now = java.time.Instant.now();
        String expired = EmailVerificationService.hash("expired"),
                old = EmailVerificationService.hash("old"),
                fresh = EmailVerificationService.hash("fresh");
        transaction.executeWithoutResult(
                status ->
                        tokens.replace(
                                user,
                                "VERIFY_EMAIL",
                                expired,
                                now.minusSeconds(120),
                                now.minusSeconds(60)));
        var expiredResult =
                transaction.execute(status -> tokens.consume(expired, "VERIFY_EMAIL", now));
        assertThat(expiredResult).isEmpty();
        transaction.executeWithoutResult(
                status -> tokens.replace(user, "VERIFY_EMAIL", old, now, now.plusSeconds(60)));
        transaction.executeWithoutResult(
                status -> tokens.replace(user, "VERIFY_EMAIL", fresh, now, now.plusSeconds(60)));
        var replacedResult =
                transaction.execute(status -> tokens.consume(old, "VERIFY_EMAIL", now));
        assertThat(replacedResult).isEmpty();
        var wrongPurpose =
                transaction.execute(status -> tokens.consume(fresh, "RESET_PASSWORD", now));
        assertThat(wrongPurpose).isEmpty();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> consume =
                    () -> {
                        start.await();
                        return transaction.execute(
                                status -> tokens.consume(fresh, "VERIFY_EMAIL", now).isPresent());
                    };
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            start.countDown();
            assertThat(
                            java.util.List.of(
                                    first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
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
                        "INSERT INTO beneficiary(id,owner_user_id,name,birth_date)"
                                + " VALUES(:id,:owner,'Adulto Test',DATE '1990-01-01')")
                .param("id", beneficiary)
                .param("owner", buyerId)
                .update();
        jdbc.sql(
                        "INSERT INTO prescription(id,owner_user_id,beneficiary_id,document_id)"
                                + " VALUES(:id,:owner,:beneficiary,:document)")
                .param("id", prescription)
                .param("owner", buyerId)
                .param("beneficiary", beneficiary)
                .param("document", document)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " prescription_item(id,prescription_id,product_id,authorized_quantity)"
                            + " VALUES(:id,:rx,:product,1)")
                .param("id", item)
                .param("rx", prescription)
                .param("product", product)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " purchase(id,buyer_user_id,beneficiary_id,mode,latitude,longitude,status)"
                            + " VALUES(:id,:buyer,:beneficiary,'PICKUP',-32.889,-68.845,'QUOTED')")
                .param("id", purchase)
                .param("buyer", buyerId)
                .param("beneficiary", beneficiary)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " purchase_item(id,purchase_id,product_id,quantity,prescription_item_id)"
                            + " VALUES(:id,:purchase,:product,1,:rxItem)")
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
                        "INSERT INTO"
                            + " app_user(id,email,name,password_hash,birth_date,email_verified)"
                            + " VALUES(:id,:email,'Profesional Test',:hash,DATE '1990-01-01',true)")
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
                        "INSERT INTO membership(id,user_id,branch_id,role)"
                                + " VALUES(:id,:user,:branch,:role)")
                .param("id", UUID.randomUUID())
                .param("user", user)
                .param("branch", branch)
                .param("role", role)
                .update();
    }

    private String randomDni() {
        return Integer.toString(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
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
