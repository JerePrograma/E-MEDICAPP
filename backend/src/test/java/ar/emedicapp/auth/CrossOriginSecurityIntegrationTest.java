package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "emedicapp.mode=DEMO", "emedicapp.security.allowed-origins=https://jereprograma.github.io"})
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class CrossOriginSecurityIntegrationTest {
    private static final String ORIGIN = "https://jereprograma.github.io";
    private static final String SESSION = CrossOriginAccess.SESSION_HEADER;
    // Deliberately has no CookieHandler: third-party cookies are unavailable on GitHub Pages.
    private final HttpClient browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @LocalServerPort int port;
    @Autowired JsonMapper mapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        properties.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        properties.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @Test
    void pagesCanAuthenticateMutateAndLogoutWithoutAnyCookies() throws Exception {
        var preflight = send(request("/api/auth/login", null)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-auth-session,x-csrf-token,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        var csrf = send(request("/api/auth/csrf", null).GET());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String anonymous = csrf.headers().firstValue(SESSION).orElseThrow();
        String csrfToken = mapper.readTree(csrf.body()).get("token").asText();
        assertThat(csrf.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains(SESSION);
        assertThat(csrf.headers().firstValue("Set-Cookie")).isEmpty();
        var login = send(request("/api/auth/login", anonymous)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("email=admin%40demo.invalid&password=DemoMedicapp2026%21")));
        assertThat(login.statusCode()).describedAs(login.body()).isEqualTo(200);
        String authenticated = login.headers().firstValue(SESSION).orElseThrow();
        assertThat(authenticated).isNotEqualTo(anonymous);
        assertThat(send(request("/api/auth/me", anonymous).GET()).statusCode()).isEqualTo(401);
        var me = send(request("/api/auth/me", authenticated).GET());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).get("email").asText()).isEqualTo("admin@demo.invalid");

        csrf = send(request("/api/auth/csrf", authenticated).GET());
        csrfToken = mapper.readTree(csrf.body()).get("token").asText();
        String product = "{\"code\":\"CORS-" + UUID.randomUUID() + "\",\"name\":\"Producto CORS QA\","
                + "\"presentation\":\"Caja QA\",\"requiresPrescription\":false,\"coldChain\":false,"
                + "\"restricted\":false,\"active\":true,\"version\":0,\"metadata\":{}}";
        assertThat(send(request("/api/admin/products", authenticated)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(product))).statusCode()).isEqualTo(403);
        var created = send(request("/api/admin/products", authenticated)
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(product)));
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(200);
        assertThat(mapper.readTree(created.body()).get("id").asText()).isNotBlank();

        var logout = send(request("/api/auth/logout", authenticated).header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(logout.headers().firstValue(SESSION)).contains("");
        assertThat(send(request("/api/auth/me", authenticated).GET()).statusCode()).isEqualTo(401);
    }

    @Test
    void unrelatedOriginsAreRejectedBeforeReadingSessionOrCsrf() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .header("Origin", "https://untrusted.example").GET();
        var result = send(request);
        assertThat(result.statusCode()).isEqualTo(403);
        assertThat(result.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(result.headers().firstValue(SESSION)).isEmpty();
    }

    private HttpRequest.Builder request(String path, String session) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Origin", ORIGIN);
        if (session != null) request.header(SESSION, session);
        return request;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
