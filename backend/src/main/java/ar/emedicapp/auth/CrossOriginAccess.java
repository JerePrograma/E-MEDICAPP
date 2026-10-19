package ar.emedicapp.auth;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Header sessions let an explicitly trusted frontend work without third-party cookies. */
@Component
public final class CrossOriginAccess implements AutoCloseable {
    public static final String SESSION_HEADER = "X-Auth-Session";

    private final Set<String> origins;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup;
    private final Filter sessionFilter;
    private final CorsConfigurationSource cors;

    public CrossOriginAccess(
            @Value("${emedicapp.security.allowed-origins:}") String configuredOrigins) {
        origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(CrossOriginAccess::validatedOrigin)
                .collect(Collectors.toUnmodifiableSet());

        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(origins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "X-CSRF-TOKEN", SESSION_HEADER, "Idempotency-Key",
                "ngrok-skip-browser-warning"));
        configuration.setExposedHeaders(List.of(SESSION_HEADER, "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        cors = source;

        var repository = new MapSessionRepository(sessions);
        repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
        var delegate = new SessionRepositoryFilter<>(repository);
        delegate.setHttpSessionIdResolver(new HeaderHttpSessionIdResolver(SESSION_HEADER));
        sessionFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                // Same-origin deployments continue to use the servlet container's cookie session.
                // Header sessions are isolated from those cookies and only accepted from the
                // configured frontend origins. CORS itself rejects any other browser origin.
                String origin = request.getHeader("Origin");
                if (origin != null && origins.contains(origin)
                        && CorsUtils.isCorsRequest(request)
                        && request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                    delegate.doFilter(request, response, chain);
                } else {
                    chain.doFilter(request, response);
                }
            }
        };
        cleanup = origins.isEmpty() ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "cross-origin-session-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        if (cleanup != null) cleanup.scheduleWithFixedDelay(
                () -> sessions.entrySet().removeIf(entry -> entry.getValue().isExpired()),
                1, 1, TimeUnit.MINUTES);
    }

    CorsConfigurationSource cors() {
        return cors;
    }

    Filter sessions() {
        return sessionFilter;
    }

    @PreDestroy
    @Override
    public void close() {
        if (cleanup != null) cleanup.shutdownNow();
        sessions.clear();
    }

    private static String validatedOrigin(String value) {
        try {
            URI origin = URI.create(value);
            boolean local = origin.getHost() != null
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(origin.getHost());
            if (origin.getHost() != null
                    && ("https".equalsIgnoreCase(origin.getScheme())
                            || (local && "http".equalsIgnoreCase(origin.getScheme())))
                    && origin.getUserInfo() == null
                    && origin.getRawPath().isEmpty()
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && origin.getPort() != 0
                    && origin.getPort() <= 65535) return value;
        } catch (IllegalArgumentException ignored) {
            // Report a configuration error instead of silently broadening frontend access.
        }
        throw new IllegalStateException(
                "EMEDICAPP_ALLOWED_ORIGINS requiere orígenes HTTPS exactos separados por coma,"
                        + " sin rutas ni barra final (HTTP sólo para localhost).");
    }
}
