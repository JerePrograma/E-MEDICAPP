package ar.emedicapp.catalog;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class PharmacyAdministrationIntegrationTest {
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
    void demoPharmacyAdministratorCanEnterScopedOverviewWithoutPlatformPrivileges()
            throws Exception {
        var manager = login("adminfarmacia@demo.invalid");
        UUID center = DemoIds.id("branch:centro"), east = DemoIds.id("branch:este");
        mvc.perform(get("/api/auth/me").session(manager.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("PHARMACY_ADMIN")))
                .andExpect(
                        jsonPath("$.branchIds")
                                .value(
                                        org.hamcrest.Matchers.containsInAnyOrder(
                                                center.toString(), east.toString())));
        var overview = overview(manager);
        assertThat(overview.branches()).extracting(CatalogDtos.Branch::id)
                .containsExactlyInAnyOrder(center, east);
        assertThat(overview.memberships())
                .allMatch(m -> List.of(center, east).contains(m.branchId()));
        assertThat(overview.plans()).isEmpty();
        assertThat(overview.subscriptions()).isEmpty();
        mvc.perform(get("/api/admin/settings").session(manager.session()))
                .andExpect(status().isForbidden());
        assertThat(overview(login("admin@demo.invalid")).branches())
                .extracting(CatalogDtos.Branch::id)
                .contains(center, east, DemoIds.id("branch:sur"));
        mvc.perform(get("/api/admin/overview").session(login("farmacia@demo.invalid").session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorUpdatesOwnBranchAndAddsStaffWhileRevocationRemovesTheScopeImmediately()
            throws Exception {
        UUID own = branch(), other = branch();
        UUID managerId = user("PHARMACY_ADMIN"), staffId = user("BUYER");
        UUID membershipId = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO membership(id,user_id,branch_id,role)"
                                + " VALUES(:id,:user,:branch,'PHARMACY_ADMIN')")
                .param("id", membershipId)
                .param("user", managerId)
                .param("branch", own)
                .update();
        var manager = login(email(managerId));
        var original = overview(manager).branches().getFirst();
        assertThat(original.id()).isEqualTo(own);
        var update = branchInput(original);
        update.put("paymentInstructions", "Instrucciones actualizadas por responsable de sucursal");
        mvc.perform(
                        put("/api/admin/branches/" + own)
                                .session(manager.session())
                                .header(manager.header(), manager.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paymentInstructions")
                                .value("Instrucciones actualizadas por responsable de sucursal"));
        mvc.perform(
                        put("/api/admin/branches/" + other)
                                .session(manager.session())
                                .header(manager.header(), manager.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(update)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/admin/users/lookup")
                                .session(manager.session())
                                .param("email", email(staffId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(staffId.toString()));
        var permission =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "userId", staffId,
                                "branchId", own,
                                "role", "PHARMACY",
                                "active", true,
                                "coldChainCapable", false,
                                "restrictedCapable", false,
                                "version", 0));
        mvc.perform(
                        post("/api/admin/memberships")
                                .session(manager.session())
                                .header(manager.header(), manager.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(permission)))
                .andExpect(status().isOk());
        var staff = login(email(staffId));
        mvc.perform(get("/api/branches/" + own + "/stock").session(staff.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/branches/" + other + "/stock").session(staff.session()))
                .andExpect(status().isForbidden());
        permission.put("branchId", other);
        mvc.perform(
                        post("/api/admin/memberships")
                                .session(manager.session())
                                .header(manager.header(), manager.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(permission)))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET active=false WHERE id=:id")
                .param("id", membershipId)
                .update();
        assertThat(overview(manager).branches()).isEmpty();
        assertThat(overview(manager).memberships()).isEmpty();
        assertThat(overview(manager).users()).isEmpty();
        mvc.perform(
                        put("/api/admin/branches/" + own)
                                .session(manager.session())
                                .header(manager.header(), manager.token())
                                .contentType("application/json")
                                .content(json.writeValueAsString(update)))
                .andExpect(status().isForbidden());
    }

    private CatalogService.Overview overview(Session session) throws Exception {
        var response =
                mvc.perform(get("/api/admin/overview").session(session.session()))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readValue(response.getResponse().getContentAsString(), CatalogService.Overview.class);
    }

    private Map<String, Object> branchInput(CatalogDtos.Branch branch) {
        var input = new LinkedHashMap<String, Object>();
        input.put("chainId", branch.chainId());
        input.put("name", branch.name());
        input.put("address", branch.address());
        input.put("latitude", branch.latitude());
        input.put("longitude", branch.longitude());
        input.put("active", branch.active());
        input.put("verified", branch.verified());
        input.put("agreementAccepted", branch.agreementAccepted());
        input.put("deliveryEnabled", branch.deliveryEnabled());
        input.put("pickupEnabled", branch.pickupEnabled());
        input.put("coldChainCapable", branch.coldChainCapable());
        input.put("restrictedCapable", branch.restrictedCapable());
        input.put("driverAvailable", branch.driverAvailable());
        input.put("merchantName", branch.merchantName());
        input.put("paymentInstructions", branch.paymentInstructions());
        input.put("deliveryRadiusKm", branch.deliveryRadiusKm());
        input.put("opensAt", branch.opensAt());
        input.put("closesAt", branch.closesAt());
        input.put("capabilities", branch.capabilities());
        input.put("version", branch.version());
        return input;
    }

    private UUID branch() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO branch(id,name,address,latitude,longitude,verified,"
                                + " agreement_accepted,merchant_name) VALUES(:id,'Sucursal acceso QA',"
                                + " 'Domicilio sintético',-32.89,-68.85,true,true,'Farmacia QA')")
                .param("id", id)
                .update();
        return id;
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified,dni)"
                                + " VALUES(:id,:email,'Personal acceso QA',:hash,DATE '1990-01-01',true,"
                                + " lpad(floor(random()*1000000000000)::bigint::text,12,'0'))")
                .param("id", id)
                .param("email", email(id))
                .param("hash", context.getBean(PasswordEncoder.class).encode("DemoMedicapp2026!"))
                .update();
        jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,:role)")
                .param("id", id)
                .param("role", role)
                .update();
        return id;
    }

    private String email(UUID id) {
        return "pharmacy-access-" + id + "@example.test";
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
