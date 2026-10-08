package ar.emedicapp.common;

import ar.emedicapp.auth.UserPrincipal;
import ar.emedicapp.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.Arrays;
import java.util.UUID;

@Component
public class Access {
    private final UserRepository users;
    private final JdbcClient jdbc;

    public Access(UserRepository users, JdbcClient jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    public Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof UserPrincipal user)) {
            throw new AppException(
                    HttpStatus.UNAUTHORIZED,
                    "LOGIN_REQUIRED",
                    "Ingresá a tu cuenta para continuar.");
        }
        return users.findActiveActor(user.id())
                .orElseThrow(
                        () ->
                                new AppException(
                                        HttpStatus.UNAUTHORIZED,
                                        "ACCOUNT_DISABLED",
                                        "Esta cuenta no está habilitada."));
    }

    public void requireBranchRole(UUID branchId, String... allowedRoles) {
        var actor = actor();
        actor.requireRole(allowedRoles);
        actor.requireBranch(branchId);
        if (actor.hasRole("ADMIN") && Arrays.asList(allowedRoles).contains("ADMIN")) return;
        boolean permitted =
                jdbc.sql(
                                """
                SELECT EXISTS (SELECT 1 FROM membership m
                    JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role
                    WHERE m.user_id = :userId AND m.branch_id = :branchId
                    AND m.active AND m.role IN (:roles))
                """)
                        .param("userId", actor.id())
                        .param("branchId", branchId)
                        .param("roles", Arrays.asList(allowedRoles))
                        .query(Boolean.class)
                        .single();
        if (!permitted)
            throw AppException.forbidden(
                    "BRANCH_ROLE_REQUIRED",
                    "Tu permiso para esta acción no corresponde a esa sucursal.");
    }
}
