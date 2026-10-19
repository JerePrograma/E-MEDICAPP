package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ar.emedicapp.common.Json;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

class ActiveAccountFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changedCredentialVersionInvalidatesSessionBeforeBusinessOperation() throws Exception {
        var users = mock(UserRepository.class);
        var json = mock(Json.class);
        var chain = mock(FilterChain.class);
        var principal =
                new UserPrincipal(
                        UUID.randomUUID(),
                        "persona@example.test",
                        "hash",
                        true,
                        true,
                        4,
                        List.of());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var request = new MockHttpServletRequest("GET", "/api/purchases");
        var session = request.getSession();
        var response = new MockHttpServletResponse();
        when(json.encode(any())).thenReturn("{}");
        when(users.sessionValid(principal.id(), 4)).thenReturn(false);
        new ActiveAccountFilter(users, json).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void matchingVerifiedCredentialVersionAllowsRequest() throws Exception {
        var users = mock(UserRepository.class);
        var json = mock(Json.class);
        var chain = mock(FilterChain.class);
        var principal =
                new UserPrincipal(
                        UUID.randomUUID(),
                        "persona@example.test",
                        "hash",
                        true,
                        true,
                        5,
                        List.of());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var request = new MockHttpServletRequest("GET", "/api/purchases");
        var response = new MockHttpServletResponse();
        when(users.sessionValid(principal.id(), 5)).thenReturn(true);
        new ActiveAccountFilter(users, json).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(json);
    }
}
