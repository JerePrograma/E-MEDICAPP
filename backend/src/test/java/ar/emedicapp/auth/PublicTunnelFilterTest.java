package ar.emedicapp.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class PublicTunnelFilterTest {
    private static final String PUBLIC_URL = "https://emedicapp.ngrok.app";

    @Test
    void spoofedFirstValuesCannotChangeClientOrPublicOriginSeenBySecurity() throws Exception {
        var request = tunnelRequest();
        request.addHeader("X-Forwarded-For", "127.0.0.1, 198.51.100.23");
        request.addHeader("X-Forwarded-Proto", "http, https");
        request.addHeader("Forwarded", "for=127.0.0.1;proto=http;host=attacker.example");
        request.addHeader("X-Forwarded-Host", "attacker.example");
        request.addHeader("X-Forwarded-Port", "81");
        request.addHeader("X-Forwarded-Prefix", "/attacker");
        request.addHeader("X-Real-IP", "127.0.0.1");
        request.addHeader("Origin", PUBLIC_URL);
        var result = filter(request);
        assertThat(result.response().getStatus()).isEqualTo(200);
        var wrapped = result.request();
        assertThat(wrapped).isNotSameAs(request);
        assertThat(wrapped.getRemoteAddr()).isEqualTo("198.51.100.23");
        assertThat(wrapped.getRemoteHost()).isEqualTo("198.51.100.23");
        assertThat(wrapped.isSecure()).isTrue();
        assertThat(wrapped.getScheme()).isEqualTo("https");
        assertThat(wrapped.getServerName()).isEqualTo("emedicapp.ngrok.app");
        assertThat(wrapped.getServerPort()).isEqualTo(443);
        assertThat(wrapped.getRequestURL().toString()).isEqualTo(PUBLIC_URL + "/api/auth/login");
        assertThat(wrapped.getHeader("HOST")).isEqualTo("emedicapp.ngrok.app");
        assertThat(wrapped.getHeader("X-Forwarded-For")).isEqualTo("198.51.100.23");
        assertThat(Collections.list(wrapped.getHeaders("X-Forwarded-For")))
                .containsExactly("198.51.100.23");
        assertThat(wrapped.getHeader("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(wrapped.getHeader("X-Forwarded-Host")).isEqualTo("emedicapp.ngrok.app");
        assertThat(wrapped.getIntHeader("X-Forwarded-Port")).isEqualTo(443);
        assertThat(wrapped.getHeader("X-Real-IP")).isEqualTo("198.51.100.23");
        assertThat(wrapped.getHeader("Forwarded")).isNull();
        assertThat(wrapped.getHeader("X-Forwarded-Prefix")).isNull();
        assertThat(Collections.list(wrapped.getHeaders("Forwarded"))).isEmpty();
        assertThat(Collections.list(wrapped.getHeaderNames()))
                .noneMatch(
                        name ->
                                name.equalsIgnoreCase("Forwarded")
                                        || name.equalsIgnoreCase("X-Forwarded-Prefix"));
        assertThat(wrapped.getHeader("Origin")).isEqualTo(PUBLIC_URL);
        assertThat(request.getRemoteAddr()).isEqualTo("127.0.0.1");
    }

    @Test
    void lastHeaderAcrossSeparateHeaderLinesWinsAndIpv6RequiresNoDns() throws Exception {
        var request = tunnelRequest();
        request.setRemoteAddr("::1");
        request.addHeader("X-Forwarded-For", "attacker.example, 127.0.0.1");
        request.addHeader("X-Forwarded-For", "2001:db8::8");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Proto", "https");
        var result = filter(request);
        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.request().getRemoteAddr()).isEqualTo("2001:db8::8");
        assertThat(result.request().getHeader("X-Forwarded-Proto")).isEqualTo("https");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "attacker.example",
                "emedicapp.ngrok.app.attacker.example",
                "emedicapp.ngrok.app:8084",
                "emedicapp.ngrok.app, attacker.example"
            })
    void rejectsAHostOutsideTheConfiguredPublicOrigin(String host) throws Exception {
        var request = validTunnelRequest();
        request.removeHeader("Host");
        request.addHeader("Host", host);
        assertRejected(request);
    }

    @Test
    void rejectsDuplicateHostEvenWhenOneValueMatches() throws Exception {
        var request = validTunnelRequest();
        request.addHeader("Host", "attacker.example");
        assertRejected(request);
    }

    @Test
    void acceptsCaseInsensitiveHostAndExplicitDefaultHttpsPort() throws Exception {
        var request = validTunnelRequest();
        request.removeHeader("Host");
        request.addHeader("Host", "EMEDICAPP.NGROK.APP:443");
        assertThat(filter(request).request().getServerPort()).isEqualTo(443);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https, http", "https,", "", "https://"})
    void rejectsAnInsecureOrMissingLastProtocol(String protocol) throws Exception {
        var request = tunnelRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.23");
        request.addHeader("X-Forwarded-Proto", protocol);
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "198.51.100.999",
                "198.51.100.23:1234",
                "198.51.100.23,",
                "127.1",
                "2001:db8::8%eth0",
                "[2001:db8::8]",
                ""
            })
    void rejectsNonNumericMalformedOrMissingLastClient(String client) throws Exception {
        var request = tunnelRequest();
        request.addHeader("X-Forwarded-For", client);
        request.addHeader("X-Forwarded-Proto", "https");
        assertRejected(request);
    }

    @Test
    void rejectsRemotePeerEvenWhenEveryForwardedHeaderLooksValid() throws Exception {
        var request = validTunnelRequest();
        request.setRemoteAddr("198.51.100.10");
        assertRejected(request);
        request.setRequestURI("/actuator/health/readiness");
        request.removeHeader("X-Forwarded-For");
        request.removeHeader("X-Forwarded-Proto");
        request.setMethod("GET");
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/bootstrap",
                "/actuator/health",
                "/actuator/health/readiness",
                "/actuator/health/liveness"
            })
    void permitsOnlyTheLocalGetProbesWithoutForwarding(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8084");
        var result = filter(request);
        assertThat(result.request()).isSameAs(request);
        assertThat(result.response().getStatus()).isEqualTo(200);
        request.setMethod("POST");
        assertRejected(request);
        request.setMethod("GET");
        request.addHeader("Forwarded", "for=198.51.100.23");
        assertRejected(request);
    }

    @Test
    void rejectsLocalApplicationAccessAndIncompleteForwardingInPublicMode() throws Exception {
        var request = tunnelRequest();
        assertRejected(request);
        request.addHeader("X-Forwarded-For", "198.51.100.23");
        assertRejected(request);
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-Proto", "https");
        assertRejected(request);
    }

    @Test
    void emptyConfigurationPreservesTheOriginalLocalRequestWithoutValidation() throws Exception {
        var request = validTunnelRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("Forwarded", "for=attacker.example");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        new PublicTunnelFilter("", "DEMO", "0.0.0.0", false, "NATIVE")
                .doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.isSecure()).isFalse();
        assertThat(request.getRemoteAddr()).isEqualTo("198.51.100.10");
        assertThat(request.getHeader("Forwarded")).isEqualTo("for=attacker.example");
    }

    @Test
    void publicModeRejectsUnsafeDeploymentConfigurationAtStartup() {
        assertThatThrownBy(
                        () -> new PublicTunnelFilter(PUBLIC_URL, "DEMO", "127.0.0.1", true, "NONE"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> new PublicTunnelFilter(PUBLIC_URL, "MANUAL", "0.0.0.0", true, "NONE"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new PublicTunnelFilter(
                                        PUBLIC_URL, "MANUAL", "127.0.0.1", false, "NONE"))
                .isInstanceOf(IllegalStateException.class);
        for (String strategy : new String[] {"NATIVE", "FRAMEWORK"})
            assertThatThrownBy(
                            () ->
                                    new PublicTunnelFilter(
                                            PUBLIC_URL, "MANUAL", "127.0.0.1", true, strategy))
                    .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://emedicapp.ngrok.app",
                "https://user:password@emedicapp.ngrok.app",
                "https://emedicapp.ngrok.app/app",
                "https://emedicapp.ngrok.app?secret=value",
                "https://emedicapp.ngrok.app#fragment",
                "https://emedicapp.ngrok.app:0",
                "https://emedicapp.ngrok.app:65536",
                "no es una URL"
            })
    void publicUrlMustDescribeOnlyAnHttpsOrigin(String url) {
        assertThatThrownBy(() -> new PublicTunnelFilter(url, "MANUAL", "127.0.0.1", true, "NONE"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PublicTunnelFilter configuredFilter() {
        return new PublicTunnelFilter(PUBLIC_URL, "MANUAL", "127.0.0.1", true, "NONE");
    }

    private static MockHttpServletRequest tunnelRequest() {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("127.0.0.1");
        request.setServerPort(8084);
        request.addHeader("Host", "emedicapp.ngrok.app");
        return request;
    }

    private static MockHttpServletRequest validTunnelRequest() {
        var request = tunnelRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.23");
        request.addHeader("X-Forwarded-Proto", "https");
        return request;
    }

    private static Result filter(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        configuredFilter().doFilter(request, response, chain);
        return new Result((HttpServletRequest) chain.getRequest(), response);
    }

    private static void assertRejected(MockHttpServletRequest request) throws Exception {
        var result = filter(request);
        assertThat(result.request()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(result.response().getContentAsString()).contains("PUBLIC_ACCESS_REQUIRED");
    }

    private record Result(HttpServletRequest request, MockHttpServletResponse response) {}
}
