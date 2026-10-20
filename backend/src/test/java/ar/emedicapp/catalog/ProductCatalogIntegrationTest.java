package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.auth.UserRepository;
import ar.emedicapp.config.OperationalConfigService;

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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class ProductCatalogIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired UserRepository users;
    @Autowired OperationalConfigService config;
    private MockMvc mvc;
    private UserDetails buyer;
    private UserDetails admin;

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
        buyer = users.loadUserByUsername("comprador@demo.invalid");
        admin = users.loadUserByUsername("admin@demo.invalid");
    }

    @Test
    void buyersSeeExactChainsBranchesAndOnlyUnreservedQuantitiesWithoutPrivateStockData()
            throws Exception {
        UUID product = product(false, false, Map.of());
        UUID chain = chain("Cadena catálogo QA");
        UUID city = branch(chain), independent = branch(null);
        stock(city, product, 12, 5);
        stock(independent, product, 3, 0);
        var response =
                availability(product)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.productId").value(product.toString()))
                        .andExpect(jsonPath("$.offers.length()").value(2))
                        .andReturn();
        var result = json.readTree(response.getResponse().getContentAsString());
        var offers = result.get("offers");
        for (var offer : offers) {
            var branch = offer.get("branch");
            assertThat(branch.get("address").asText()).isEqualTo("Dirección catálogo QA");
            assertThat(branch.get("latitude").asText()).isEqualTo("-32.890000");
            assertThat(branch.get("longitude").asText()).isEqualTo("-68.850000");
            assertThat(branch.get("pickupEnabled").asBoolean()).isTrue();
            assertThat(branch.get("paymentInstructions").asText()).isEmpty();
            assertThat(branch.get("capabilities").isEmpty()).isTrue();
            assertThat(offer.get("price").asText()).isEqualTo("1234.56");
            assertThat(offer.get("currency").asText()).isEqualTo(config.current().currency());
            assertThat(offer.has("observedAt")).isTrue();
            assertThat(offer.has("quantity")).isFalse();
            assertThat(offer.has("reservedQuantity")).isFalse();
            assertThat(offer.has("source")).isFalse();
            if (branch.get("id").asText().equals(city.toString())) {
                assertThat(offer.get("chainName").asText()).isEqualTo("Cadena catálogo QA");
                assertThat(offer.get("availableQuantity").asInt()).isEqualTo(7);
                assertThat(branch.get("chainId").asText()).isEqualTo(chain.toString());
            } else {
                assertThat(offer.get("chainName").asText()).isEqualTo("Farmacia independiente catálogo QA");
                assertThat(offer.get("availableQuantity").asInt()).isEqualTo(3);
            }
        }
        assertThat(response.getResponse().getContentAsString()).doesNotContain("Cuenta privada QA");
        // Public availability does not grant access to the protected stock-management API.
        mvc.perform(get("/api/branches/" + city + "/stock").with(user(buyer)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT reserved_quantity FROM branch_stock WHERE branch_id=:branch AND product_id=:product")
                        .param("branch", city).param("product", product)
                        .query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void unavailableStockAndBranchesAreExcludedAndAvailabilityRequiresAnActiveProduct()
            throws Exception {
        UUID product = product(false, false, Map.of());
        UUID chain = chain("Cadena elegibilidad QA"), branch = branch(chain);
        stock(branch, product, 5, 0);
        availability(product).andExpect(jsonPath("$.offers.length()").value(1));
        for (String flag : List.of("active", "verified", "agreement_accepted")) {
            jdbc.sql("UPDATE branch SET " + flag + "=false WHERE id=:id").param("id", branch).update();
            availability(product).andExpect(jsonPath("$.offers").isEmpty());
            jdbc.sql("UPDATE branch SET " + flag + "=true WHERE id=:id").param("id", branch).update();
        }
        jdbc.sql("UPDATE chain SET active=false WHERE id=:id").param("id", chain).update();
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE chain SET active=true WHERE id=:id").param("id", chain).update();
        jdbc.sql("UPDATE branch_stock SET active=false WHERE branch_id=:id").param("id", branch).update();
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE branch_stock SET active=true,reserved_quantity=quantity WHERE branch_id=:id")
                .param("id", branch).update();
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE branch_stock SET reserved_quantity=0,currency=:currency WHERE branch_id=:id")
                .param("currency", "ARS".equals(config.current().currency()) ? "USD" : "ARS")
                .param("id", branch).update();
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE branch_stock SET currency=:currency WHERE branch_id=:id")
                .param("currency", config.current().currency()).param("id", branch).update();
        suspend(branch);
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE product SET active=false WHERE id=:id").param("id", product).update();
        availability(product).andExpect(status().isNotFound());
        availability(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/products/" + product + "/availability"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void specialProductsRequireTheSameAccreditedBranchCapabilityAsRouting() throws Exception {
        UUID product = product(true, true, Map.of());
        UUID branch = branch(null);
        stock(branch, product, 6, 2);
        availability(product).andExpect(status().isOk()).andExpect(jsonPath("$.offers").isEmpty());
        jdbc.sql("UPDATE branch SET cold_chain_capable=true,restricted_capable=true WHERE id=:id")
                .param("id", branch).update();
        availability(product).andExpect(jsonPath("$.offers").isEmpty());
        UUID document = UUID.randomUUID();
        jdbc.sql("INSERT INTO document(id,owner_user_id,branch_id,kind,original_name,content_type,size_bytes,storage_key)"
                        + " VALUES(:id,:owner,:branch,'CAPABILITY','Evidencia privada QA','application/pdf',5,:storage)")
                .param("id", document).param("owner", ar.emedicapp.appbootstrap.DemoIds.id("user:admin@demo.invalid"))
                .param("branch", branch).param("storage", "catalog-qa-" + document).update();
        jdbc.sql("UPDATE branch SET capabilities=cast(:capabilities AS jsonb) WHERE id=:id")
                .param("id", branch)
                .param("capabilities", json.writeValueAsString(Map.of(
                        "capabilityEvidenceDocumentId", document.toString(),
                        "verifiedBy", "QA", "verifiedAt", "2026-10-20T00:00:00Z")))
                .update();
        var response = availability(product).andExpect(jsonPath("$.offers.length()").value(1))
                .andExpect(jsonPath("$.offers[0].availableQuantity").value(4)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(document.toString());
    }

    @Test
    void categoryFallbackDoesNotMutateLegacyRowsAndAdminChangesPreservePrescriptionFlags()
            throws Exception {
        UUID rx = product(true, false, Map.of("other", "preserved"));
        UUID regular = product(false, false, Map.of());
        var listed = mvc.perform(get("/api/catalog/products").with(user(buyer)))
                .andExpect(status().isOk()).andReturn();
        var products = json.readTree(listed.getResponse().getContentAsString());
        for (var product : products) {
            if (product.get("id").asText().equals(rx.toString()))
                assertThat(product.get("metadata").get("catalogCategory").asText()).isEqualTo("PRESCRIPTION");
            if (product.get("id").asText().equals(regular.toString()))
                assertThat(product.get("metadata").get("catalogCategory").asText()).isEqualTo("NON_PRESCRIPTION");
        }
        assertThat(jdbc.sql("SELECT jsonb_exists(metadata,'catalogCategory') FROM product WHERE id=:id")
                .param("id", rx).query(Boolean.class).single()).isFalse();
        for (String category : List.of(
                "NON_PRESCRIPTION", "SEXUAL_HEALTH", "SKIN_CARE", "BEAUTY", "VITAMINS_SUPPLEMENTS",
                "FIRST_AID", "BABY", "PERSONAL_CARE", "MEDICAL_SUPPLIES", "FOOD_DRINK", "HOUSEHOLD")) {
            var body = productInput(false, Map.of("catalogCategory", category, "other", "preserved"));
            mvc.perform(post("/api/admin/products").with(user(admin)).with(csrf())
                            .contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .withFailMessage("Category %s: %s", category, result.getResponse().getContentAsString())
                            .isEqualTo(200))
                    .andExpect(jsonPath("$.requiresPrescription").value(false))
                    .andExpect(jsonPath("$.metadata.catalogCategory").value(category))
                    .andExpect(jsonPath("$.metadata.other").value("preserved"));
        }
        for (Map<String, Object> body : List.of(
                productInput(false, Map.of("catalogCategory", "UNKNOWN")),
                productInput(false, Map.of("catalogCategory", "MEDICINES")),
                productInput(false, Map.of("catalogCategory", "PRESCRIPTION")),
                productInput(true, Map.of("catalogCategory", "SEXUAL_HEALTH")),
                productInput(true, Map.of("catalogCategory", "NON_PRESCRIPTION"))))
            mvc.perform(post("/api/admin/products").with(user(admin)).with(csrf())
                            .contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/products").with(user(buyer)).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(productInput(false, Map.of("catalogCategory", "BEAUTY")))))
                .andExpect(status().isForbidden());
    }

    private ResultActions availability(UUID product) throws Exception {
        return mvc.perform(get("/api/catalog/products/" + product + "/availability").with(user(buyer)));
    }

    private UUID product(boolean prescription, boolean special, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO product(id,code,name,presentation,requires_prescription,cold_chain,restricted,metadata)"
                        + " VALUES(:id,:code,'Producto catálogo QA','Presentación QA',:rx,:special,:special,cast(:metadata AS jsonb))")
                .param("id", id).param("code", "catalog-qa-" + id).param("rx", prescription)
                .param("special", special).param("metadata", json.writeValueAsString(metadata)).update();
        return id;
    }

    private Map<String, Object> productInput(boolean prescription, Map<String, Object> metadata) {
        return new HashMap<>(Map.of(
                "code", "catalog-qa-" + UUID.randomUUID(),
                "name", "Producto catálogo QA", "presentation", "Presentación QA",
                "requiresPrescription", prescription, "coldChain", false, "restricted", false, "active", true,
                "version", 0, "metadata", metadata));
    }

    private UUID chain(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO chain(id,name) VALUES(:id,:name)").param("id", id).param("name", name).update();
        return id;
    }

    private UUID branch(UUID chain) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO branch(id,chain_id,name,address,latitude,longitude,verified,agreement_accepted,merchant_name,payment_instructions)"
                        + " VALUES(:id,:chain,'Sucursal catálogo QA','Dirección catálogo QA',-32.89,-68.85,true,true,"
                        + " 'Farmacia independiente catálogo QA','Cuenta privada QA')")
                .param("id", id).param("chain", chain).update();
        return id;
    }

    private void stock(UUID branch, UUID product, int quantity, int reserved) {
        jdbc.sql("INSERT INTO branch_stock(branch_id,product_id,quantity,reserved_quantity,price,currency)"
                        + " VALUES(:branch,:product,:quantity,:reserved,:price,:currency)")
                .param("branch", branch).param("product", product).param("quantity", quantity)
                .param("reserved", reserved).param("price", new BigDecimal("1234.56"))
                .param("currency", config.current().currency()).update();
    }

    private void suspend(UUID branch) {
        UUID plan = UUID.randomUUID(), contract = UUID.randomUUID();
        jdbc.sql("INSERT INTO plan(id,name,min_branches) VALUES(:id,'Plan catálogo QA',1)").param("id", plan).update();
        jdbc.sql("INSERT INTO subscription_contract(id,plan_id,branch_id,status) VALUES(:id,:plan,:branch,'SUSPENDED')")
                .param("id", contract).param("plan", plan).param("branch", branch).update();
        jdbc.sql("INSERT INTO branch_subscription(contract_id,branch_id) VALUES(:contract,:branch)")
                .param("contract", contract).param("branch", branch).update();
    }
}
