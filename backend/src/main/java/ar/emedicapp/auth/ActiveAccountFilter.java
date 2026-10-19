package ar.emedicapp.auth;

import ar.emedicapp.common.ApiError;
import ar.emedicapp.common.Json;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class ActiveAccountFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final Json json;

    ActiveAccountFilter(UserRepository users, Json json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")
                && authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && !users.sessionValid(principal.id(), principal.credentialsVersion())) {
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            response.setStatus(401);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write(
                            json.encode(
                                    new ApiError(
                                            "ACCOUNT_DISABLED",
                                            "La sesión ya no está habilitada. Ingresá nuevamente si"
                                                    + " recuperaste tu contraseña.")));
            return;
        }
        chain.doFilter(request, response);
    }
}
