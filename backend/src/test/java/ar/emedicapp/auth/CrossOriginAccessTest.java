package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CrossOriginAccessTest {
    private static final String ORIGIN = "https://jereprograma.github.io";

    @Test
    void headerSessionsSurviveWithoutCookiesRotateAndInvalidate() throws Exception {
        try (var access = new CrossOriginAccess(ORIGIN)) {
            var response = new MockHttpServletResponse();
            access.sessions().doFilter(request(null), response, (request, result) ->
                    ((HttpServletRequest) request).getSession().setAttribute("user", "demo"));
            String original = response.getHeader(CrossOriginAccess.SESSION_HEADER);
            assertThat(original).isNotBlank();
            assertThat(response.getHeader("Set-Cookie")).isNull();

            response = new MockHttpServletResponse();
            access.sessions().doFilter(request(original), response, (request, result) -> {
                var http = (HttpServletRequest) request;
                assertThat(http.getSession(false).getAttribute("user")).isEqualTo("demo");
                http.changeSessionId();
            });
            String rotated = response.getHeader(CrossOriginAccess.SESSION_HEADER);
            assertThat(rotated).isNotBlank().isNotEqualTo(original);
            access.sessions().doFilter(request(original), new MockHttpServletResponse(), (request, result) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isNull());

            response = new MockHttpServletResponse();
            access.sessions().doFilter(request(rotated), response, (request, result) ->
                    ((HttpServletRequest) request).getSession(false).invalidate());
            assertThat(response.getHeader(CrossOriginAccess.SESSION_HEADER)).isEmpty();
            access.sessions().doFilter(request(rotated), new MockHttpServletResponse(), (request, result) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isNull());
        }
    }

    @Test
    void nativeSessionsAreUnchangedAndOtherOriginsCannotResolveHeaderSessions() throws Exception {
        try (var access = new CrossOriginAccess(ORIGIN)) {
            var nativeRequest = new MockHttpServletRequest("GET", "/api/auth/me");
            var nativeSession = nativeRequest.getSession();
            access.sessions().doFilter(nativeRequest, new MockHttpServletResponse(), (request, response) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isSameAs(nativeSession));
            var crossOriginWithCookie = request(null);
            crossOriginWithCookie.setSession((org.springframework.mock.web.MockHttpSession) nativeSession);
            access.sessions().doFilter(crossOriginWithCookie, new MockHttpServletResponse(), (request, response) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isNull());

            var response = new MockHttpServletResponse();
            access.sessions().doFilter(request(null), response, (request, result) ->
                    ((HttpServletRequest) request).getSession().setAttribute("user", "demo"));
            var forbidden = request(response.getHeader(CrossOriginAccess.SESSION_HEADER));
            forbidden.removeHeader("Origin");
            forbidden.addHeader("Origin", "https://untrusted.example");
            access.sessions().doFilter(forbidden, new MockHttpServletResponse(), (request, result) ->
                    assertThat(((HttpServletRequest) request).getSession(false)).isNull());
            assertThat(access.cors().getCorsConfiguration(forbidden)
                    .checkOrigin("https://untrusted.example")).isNull();
        }
    }

    @Test
    void rejectsBroadOrMalformedOriginsAtStartup() {
        for (String invalid : new String[] {"*", "https://*.github.io", ORIGIN + "/E-MEDICAPP/",
                ORIGIN + "/", "http://remote.example", "null", "https://user:pass@remote.example"}) {
            assertThatThrownBy(() -> new CrossOriginAccess(invalid))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (var access = new CrossOriginAccess(ORIGIN + ", http://localhost:5173")) {
            assertThat(access.cors().getCorsConfiguration(request(null)).checkOrigin(ORIGIN))
                    .isEqualTo(ORIGIN);
        }
    }

    private static MockHttpServletRequest request(String session) {
        var request = new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.addHeader("Origin", ORIGIN);
        if (session != null) request.addHeader(CrossOriginAccess.SESSION_HEADER, session);
        return request;
    }
}
