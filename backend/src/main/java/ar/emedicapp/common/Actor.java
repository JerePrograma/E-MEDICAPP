package ar.emedicapp.common;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

public record Actor(UUID id, String email, String name, Set<String> roles, Set<UUID> branchIds) {
    public Actor {
        roles = Set.copyOf(roles);
        branchIds = Set.copyOf(branchIds);
    }

    public boolean hasRole(String... allowedRoles) {
        return Arrays.stream(allowedRoles).anyMatch(roles::contains);
    }

    public void requireRole(String... allowedRoles) {
        if (!hasRole(allowedRoles)) {
            throw AppException.forbidden(
                    "ROLE_REQUIRED", "Tu cuenta no tiene permiso para esta acción.");
        }
    }

    public void requireBranch(UUID branchId) {
        if (branchId == null || (!hasRole("ADMIN") && !branchIds.contains(branchId))) {
            throw AppException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tenés acceso a esta sucursal.");
        }
    }
}
