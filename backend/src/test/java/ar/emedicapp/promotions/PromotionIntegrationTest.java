package ar.emedicapp.promotions;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.auth.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class PromotionIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired UserRepository users;
    @Autowired Clock clock;
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
    void promotionPublishesOnlyCurrentEligibleBranchesWithExactChainAddressAndCoordinates()
            throws Exception {
        UUID chain = chain(), first = branch(chain), second = branch(chain);
        var owner = person("PHARMACY_ADMIN");
        membership(owner, first, "PHARMACY_ADMIN");
        membership(owner, second, "PHARMACY_ADMIN");
        var response =
                create(owner, input(List.of(first, second)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.chainId").value(chain.toString()))
                        .andExpect(jsonPath("$.chainName").value("Cadena promociones QA"))
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.branches.length()").value(2))
                        .andReturn();
        UUID id = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        var buyer = users.loadUserByUsername("comprador@demo.invalid");
        mvc.perform(get("/api/catalog/promotions").with(user(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].branches[0].address")
                        .value(org.hamcrest.Matchers.contains("Domicilio promoción QA")))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].branches[0].latitude")
                        .value(org.hamcrest.Matchers.contains("-32.890000")));
        jdbc.sql("UPDATE branch SET verified=false WHERE id=:id").param("id", second).update();
        mvc.perform(get("/api/catalog/promotions").with(user(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].branches.length()")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].branchIds[0]")
                        .value(org.hamcrest.Matchers.contains(first.toString())));
        // Expiration is checked during each read, without a scheduled job.
        jdbc.sql("UPDATE promotion SET starts_at=now()-interval '2 days',ends_at=now()-interval '1 day' WHERE id=:id")
                .param("id", id).update();
        mvc.perform(get("/api/catalog/promotions").with(user(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE entity_id=:id AND action='PROMOTION_CREATED' AND after_data->>'reason'='Publicación QA'")
                        .param("id", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void branchRolesAndFullExistingScopeAreRequiredEvenWhenRemovingAForbiddenBranch()
            throws Exception {
        UUID chain = chain(), first = branch(chain), second = branch(chain);
        var owner = person("PHARMACY");
        membership(owner, first, "PHARMACY");
        membership(owner, second, "PHARMACY");
        UUID id = id(create(owner, input(List.of(first, second))));
        var limited = person("PHARMACY", "DRIVER");
        membership(limited, first, "PHARMACY");
        membership(limited, second, "DRIVER");
        mvc.perform(get("/api/pharmacy/promotion-branches").with(user(limited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first.toString()));
        mvc.perform(get("/api/pharmacy/promotions").with(user(limited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
        var change = input(List.of(first));
        change.put("version", 1);
        update(limited, id, change).andExpect(status().isForbidden());
        create(limited, input(List.of(first, second))).andExpect(status().isForbidden());
        var buyer = users.loadUserByUsername("comprador@demo.invalid");
        create(buyer, input(List.of(first))).andExpect(status().isForbidden());
        mvc.perform(get("/api/pharmacy/promotions").with(user(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/promotions")).andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE membership SET active=false WHERE user_id=:id AND role='PHARMACY'")
                .param("id", personId(owner)).update();
        update(owner, id, change).andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidPeriodsChainsAndDuplicatesAndPreventsStaleEdits() throws Exception {
        UUID chain = chain(), first = branch(chain), foreign = branch(chain());
        var manager = person("PHARMACIST");
        membership(manager, first, "PHARMACIST");
        membership(manager, foreign, "PHARMACIST");
        create(manager, input(List.of(first, first)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROMOTION_BRANCHES"));
        create(manager, input(List.of(first, foreign)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMOTION_CHAIN_MISMATCH"));
        var invalid = input(List.of(first));
        invalid.put("endsAt", invalid.get("startsAt"));
        create(manager, invalid).andExpect(status().isBadRequest());
        invalid = input(List.of(first));
        invalid.put("description", "  ");
        create(manager, invalid).andExpect(status().isBadRequest());
        UUID id = id(create(manager, input(List.of(first))));
        var edit = input(List.of(first));
        edit.put("version", 1);
        edit.put("title", "Promoción actualizada");
        update(manager, id, edit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        update(manager, id, edit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_CHANGED"));
        jdbc.sql("UPDATE branch SET active=false WHERE id=:id").param("id", first).update();
        edit.put("version", 2);
        update(manager, id, edit)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMOTION_BRANCH_INACTIVE"));
        // The owner can still pause a campaign after one of its branches closes.
        edit.put("active", false);
        update(manager, id, edit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.branches[0].active").value(false));
        mvc.perform(get("/api/pharmacy/promotion-branches").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + first + "')].active")
                        .value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void excludesScheduledPausedAndInactiveChainsAndSupportsIndependentPharmacies()
            throws Exception {
        var admin = users.loadUserByUsername("admin@demo.invalid");
        UUID chain = chain(), branch = branch(chain), independent = branch(null);
        var scheduled = input(List.of(branch));
        scheduled.put("startsAt", clock.instant().plusSeconds(3600));
        scheduled.put("endsAt", clock.instant().plusSeconds(7200));
        UUID future = id(create(admin, scheduled));
        var paused = input(List.of(branch));
        paused.put("active", false);
        UUID hidden = id(create(admin, paused));
        UUID closedChain = id(create(admin, input(List.of(branch))));
        jdbc.sql("UPDATE chain SET active=false WHERE id=:id").param("id", chain).update();
        UUID standalone = id(create(admin, input(List.of(independent))));
        mvc.perform(get("/api/catalog/promotions").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + future + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + hidden + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + closedChain + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + standalone + "')].chainName")
                        .value(org.hamcrest.Matchers.contains("Farmacia independiente QA")));
    }

    private Map<String, Object> input(List<UUID> branches) {
        return new HashMap<>(Map.of(
                "title", "Promoción de cuidado personal",
                "description", "10% en productos de cuidado personal seleccionados. Consultar condiciones.",
                "startsAt", clock.instant().minusSeconds(3600),
                "endsAt", clock.instant().plusSeconds(3600),
                "featured", true,
                "active", true,
                "branchIds", branches,
                "version", 0,
                "reason", "Publicación QA"));
    }

    private ResultActions create(UserDetails actor, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/pharmacy/promotions").with(user(actor)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions update(UserDetails actor, UUID id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/pharmacy/promotions/" + id).with(user(actor)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private UUID id(ResultActions action) throws Exception {
        return UUID.fromString(json.readTree(action.andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("id").asText());
    }

    private UUID chain() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO chain(id,name) VALUES(:id,'Cadena promociones QA')")
                .param("id", id).update();
        return id;
    }

    private UUID branch(UUID chain) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO branch(id,chain_id,name,address,latitude,longitude,verified,agreement_accepted,merchant_name)"
                        + " VALUES(:id,:chain,'Sucursal promociones QA','Domicilio promoción QA',-32.89,-68.85,true,true,'Farmacia independiente QA')")
                .param("id", id).param("chain", chain).update();
        return id;
    }

    private UserDetails person(String... roles) {
        UUID id = UUID.randomUUID();
        String email = "promotion-" + id + "@example.test";
        jdbc.sql("INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified)"
                        + " VALUES(:id,:email,'Personal promociones QA','unused',DATE '1990-01-01',true)")
                .param("id", id).param("email", email)
                .update();
        for (String role : roles)
            jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,:role)")
                    .param("id", id).param("role", role).update();
        return users.loadUserByUsername(email);
    }

    private UUID personId(UserDetails person) {
        return ((ar.emedicapp.auth.UserPrincipal) person).id();
    }

    private void membership(UserDetails person, UUID branch, String role) {
        jdbc.sql("INSERT INTO membership(id,user_id,branch_id,role) VALUES(:id,:user,:branch,:role)")
                .param("id", UUID.randomUUID()).param("user", personId(person))
                .param("branch", branch).param("role", role).update();
    }
}
