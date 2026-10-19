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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class ChainLogoIntegrationTest {
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
    void storesPreservesReplacesAndRemovesLogoWithBranchesInheritingVersionedUrl()
            throws Exception {
        var admin = login("admin@demo.invalid");
        String firstImage = image(Color.RED);
        var created =
                mvc.perform(
                                post("/api/admin/chains")
                                        .session(admin.session())
                                        .header(admin.header(), admin.token())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Cadena Logo QA",
                                                                "active",
                                                                true,
                                                                "version",
                                                                0,
                                                                "logoDataUrl",
                                                                firstImage))))
                        .andExpect(status().isOk())
                        .andReturn();
        var chain = json.readTree(created.getResponse().getContentAsString());
        var id = UUID.fromString(chain.get("id").asText());
        String url = "/api/catalog/chains/" + id + "/logo";
        assertThat(chain.get("logoUrl").asText()).isEqualTo(url + "?v=1");
        byte[] original = download(admin, url);
        assertThat(original).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(download(login("comprador@demo.invalid"), url)).isEqualTo(original);
        UUID branch = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO"
                            + " branch(id,chain_id,name,address,latitude,longitude,verified,agreement_accepted)"
                            + " VALUES(:id,:chain,'Sucursal logo QA','Domicilio"
                            + " sintético',-32.89,-68.85,true,true)")
                .param("id", branch)
                .param("chain", id)
                .update();
        mvc.perform(get("/api/catalog/branches").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id == '" + branch + "')].chainLogoUrl")
                                .value(org.hamcrest.Matchers.contains(url + "?v=1")));
        update(admin, id, Map.of("name", "Nombre actualizado", "active", true, "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(url + "?v=2"));
        assertThat(download(admin, url)).isEqualTo(original);
        update(
                        admin,
                        id,
                        Map.of(
                                "name",
                                "Cadena reemplazada",
                                "active",
                                true,
                                "version",
                                2,
                                "logoDataUrl",
                                image(Color.BLUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(url + "?v=3"));
        byte[] replacement = download(admin, url);
        assertThat(replacement).isNotEqualTo(original);
        mvc.perform(get("/api/catalog/branches").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id == '" + branch + "')].chainLogoUrl")
                                .value(org.hamcrest.Matchers.contains(url + "?v=3")));
        update(
                        admin,
                        id,
                        Map.of(
                                "name",
                                "Cambio viejo",
                                "active",
                                true,
                                "version",
                                2,
                                "removeLogo",
                                true))
                .andExpect(status().isConflict());
        assertThat(download(admin, url)).isEqualTo(replacement);
        update(
                        admin,
                        id,
                        Map.of(
                                "name",
                                "Cadena sin logo",
                                "active",
                                true,
                                "version",
                                3,
                                "removeLogo",
                                true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").doesNotExist());
        mvc.perform(get(url).session(admin.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/branches").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id == '" + branch + "')].chainLogoUrl")
                                .value(
                                        org.hamcrest.Matchers.contains(
                                                org.hamcrest.Matchers.nullValue())));
        String audits =
                jdbc.sql(
                                "SELECT string_agg(after_data::text,' ') FROM audit_event WHERE"
                                        + " entity_type='chain' AND entity_id=:id")
                        .param("id", id)
                        .query(String.class)
                        .single();
        assertThat(audits).contains("logoAction").doesNotContain(firstImage, "base64");
    }

    @Test
    void protectsLogoAccessAndRejectsInvalidOrConflictingChangesAtomically() throws Exception {
        var admin = login("admin@demo.invalid");
        var buyer = login("comprador@demo.invalid");
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO chain(id,name,active,logo_png) VALUES(:id,'Logo privado"
                                + " QA',false,:logo)")
                .param("id", id)
                .param("logo", ChainLogoImage.normalize(image(Color.GREEN)), java.sql.Types.BINARY)
                .update();
        String url = "/api/catalog/chains/" + id + "/logo";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).session(buyer.session())).andExpect(status().isNotFound());
        download(admin, url);
        mvc.perform(
                        get("/api/catalog/chains/" + UUID.randomUUID() + "/logo")
                                .session(admin.session()))
                .andExpect(status().isNotFound());
        update(
                        buyer,
                        id,
                        Map.of(
                                "name",
                                "Sin permiso",
                                "active",
                                true,
                                "version",
                                1,
                                "removeLogo",
                                true))
                .andExpect(status().isForbidden());
        update(
                        admin,
                        id,
                        Map.of(
                                "name",
                                "No guardar",
                                "active",
                                true,
                                "version",
                                1,
                                "logoDataUrl",
                                "data:image/png;base64,PHN2Zz4="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAIN_LOGO_INVALID"));
        update(
                        admin,
                        id,
                        Map.of(
                                "name",
                                "No guardar",
                                "active",
                                true,
                                "version",
                                1,
                                "logoDataUrl",
                                image(Color.BLUE),
                                "removeLogo",
                                true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAIN_LOGO_CONFLICT"));
        var stored =
                jdbc.sql("SELECT name,active,version,logo_png FROM chain WHERE id=:id")
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(stored.get("name")).isEqualTo("Logo privado QA");
        assertThat(stored.get("active")).isEqualTo(false);
        assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1);
        assertThat((byte[]) stored.get("logo_png")).isNotEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions update(
            Session session, UUID id, Map<String, Object> input) throws Exception {
        return mvc.perform(
                put("/api/admin/chains/" + id)
                        .session(session.session())
                        .header(session.header(), session.token())
                        .contentType("application/json")
                        .content(json.writeValueAsString(input)));
    }

    private byte[] download(Session session, String url) throws Exception {
        return mvc.perform(get(url).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
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
