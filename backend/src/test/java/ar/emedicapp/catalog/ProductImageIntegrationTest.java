package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class ProductImageIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        values.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        values.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createsPreservesReplacesAndRemovesVersionedProductPhotoWithoutExposingPayloads()
            throws Exception {
        var admin = login("admin@demo.invalid");
        var buyer = login("comprador@demo.invalid");
        String code = "PHOTO-QA-" + UUID.randomUUID();
        String firstImage = image(Color.RED);
        UUID id = create(admin, input(code, 0, true, Map.of("imageDataUrl", firstImage)));
        String url = "/api/catalog/products/" + id + "/image";
        byte[] original = download(buyer, url);
        mvc.perform(get("/api/catalog/products").session(buyer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].imageUrl")
                        .value(org.hamcrest.Matchers.contains(url + "?v=1")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("base64"))));
        update(admin, id, input(code, 1, true, Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(url + "?v=2"));
        assertThat(download(buyer, url)).isEqualTo(original);
        update(admin, id, input(code, 2, true, Map.of("imageDataUrl", image(Color.BLUE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(url + "?v=3"));
        byte[] replacement = download(buyer, url);
        assertThat(replacement).isNotEqualTo(original);
        update(admin, id, input(code, 2, true, Map.of("removeImage", true)))
                .andExpect(status().isConflict());
        assertThat(download(buyer, url)).isEqualTo(replacement);
        update(admin, id, input(code, 3, true, Map.of("removeImage", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
        mvc.perform(get(url).session(buyer.session())).andExpect(status().isNotFound());
        String audits = jdbc.sql("SELECT string_agg(after_data::text,' ') FROM audit_event"
                        + " WHERE entity_type='product' AND entity_id=:id")
                .param("id", id).query(String.class).single();
        assertThat(audits).contains("imageAction", "REPLACED", "UNCHANGED", "REMOVED")
                .doesNotContain(firstImage, "base64", "image_png");
    }

    @Test
    void protectsInactivePhotosAndRejectsUnauthorizedInvalidAndConflictingChangesAtomically()
            throws Exception {
        var admin = login("admin@demo.invalid");
        var buyer = login("comprador@demo.invalid");
        String code = "PHOTO-QA-" + UUID.randomUUID();
        UUID id = create(admin, input(code, 0, false, Map.of("imageDataUrl", image(Color.GREEN))));
        String url = "/api/catalog/products/" + id + "/image";
        byte[] original = download(admin, url);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).session(buyer.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/products/" + UUID.randomUUID() + "/image")
                .session(admin.session())).andExpect(status().isNotFound());
        update(buyer, id, input(code, 1, true, Map.of("removeImage", true)))
                .andExpect(status().isForbidden());
        update(login("adminfarmacia@demo.invalid"), id,
                input(code, 1, true, Map.of("removeImage", true)))
                .andExpect(status().isForbidden());
        update(admin, id, input(code, 1, true,
                Map.of("imageDataUrl", "data:image/png;base64,PHN2Zz4=")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_INVALID"));
        update(admin, id, input(code, 1, true,
                Map.of("imageDataUrl", image(Color.BLUE), "removeImage", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_CONFLICT"));
        var stored = jdbc.sql("SELECT active,version FROM product WHERE id=:id")
                .param("id", id).query().singleRow();
        assertThat(stored.get("active")).isEqualTo(false);
        assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1);
        assertThat(download(admin, url)).isEqualTo(original);
    }

    @Test
    void existingClientsCanCreateAndEditProductsWithoutPhotos() throws Exception {
        var admin = login("admin@demo.invalid");
        String code = "PHOTO-QA-" + UUID.randomUUID();
        UUID id = create(admin, input(code, 0, true, Map.of()));
        update(admin, id, input(code, 1, true, Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
        mvc.perform(get("/api/catalog/products/" + id + "/image").session(admin.session()))
                .andExpect(status().isNotFound());
    }

    private static Map<String, Object> input(String code, long version, boolean active,
            Map<String, Object> extra) {
        var input = new HashMap<String, Object>();
        input.put("code", code);
        input.put("name", "Producto foto QA");
        input.put("presentation", "Caja de muestra");
        input.put("requiresPrescription", false);
        input.put("coldChain", false);
        input.put("restricted", false);
        input.put("active", active);
        input.put("version", version);
        input.put("metadata", Map.of("catalogCategory", "NON_PRESCRIPTION"));
        input.putAll(extra);
        return input;
    }

    private UUID create(Session session, Map<String, Object> input) throws Exception {
        var response = mvc.perform(post("/api/admin/products").session(session.session())
                        .header(session.header(), session.token()).contentType("application/json")
                        .content(json.writeValueAsString(input)))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .withFailMessage("Product creation failed: %s",
                                result.getResponse().getContentAsString())
                        .isEqualTo(200))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private ResultActions update(Session session, UUID id, Map<String, Object> input)
            throws Exception {
        return mvc.perform(put("/api/admin/products/" + id).session(session.session())
                .header(session.header(), session.token()).contentType("application/json")
                .content(json.writeValueAsString(input)));
    }

    private byte[] download(Session session, String url) throws Exception {
        return mvc.perform(get(url).session(session.session()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String image(Color color) throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(4, 4, color.getRGB());
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private Session login(String email) throws Exception {
        var csrf = csrf(null);
        mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.header(), csrf.token()).param("email", email)
                        .param("password", "DemoMedicapp2026!"))
                .andExpect(status().isOk());
        return csrf(csrf.session());
    }

    private Session csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var value = json.readTree(response.getResponse().getContentAsString());
        return new Session((MockHttpSession) response.getRequest().getSession(),
                value.get("headerName").asText(), value.get("token").asText());
    }

    private record Session(MockHttpSession session, String header, String token) {}
}
