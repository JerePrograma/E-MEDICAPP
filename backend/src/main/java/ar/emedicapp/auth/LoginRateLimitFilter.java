package ar.emedicapp.auth;

import ar.emedicapp.common.ApiError;
import ar.emedicapp.common.Json;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class LoginRateLimitFilter extends OncePerRequestFilter {
    private final LoginRateLimiter limiter;
    private final Json json;

    LoginRateLimitFilter(LoginRateLimiter limiter, Json json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean guarded =
                "POST".equals(request.getMethod())
                        && (path.equals("/api/auth/login")
                                || path.equals("/api/auth/register")
                                || path.equals("/api/auth/verification/resend")
                                || path.equals("/api/auth/verification/confirm")
                                || path.equals("/api/auth/password/request-reset")
                                || path.equals("/api/auth/password/reset"));
        if (guarded && !limiter.allow(request.getRemoteAddr() + ":" + path)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "300");
            response.getWriter()
                    .write(
                            json.encode(
                                    new ApiError(
                                            "TOO_MANY_ATTEMPTS",
                                            "Se hicieron demasiados intentos. Esperá unos minutos"
                                                    + " antes de continuar.")));
            return;
        }
        chain.doFilter(request, response);
    }
}
