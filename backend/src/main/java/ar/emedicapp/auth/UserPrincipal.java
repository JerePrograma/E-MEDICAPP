package ar.emedicapp.auth;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class UserPrincipal extends User {
    private final UUID id;

    public UserPrincipal(
            UUID id,
            String email,
            String passwordHash,
            boolean active,
            Collection<? extends GrantedAuthority> authorities) {
        super(email, passwordHash, active, true, true, true, authorities);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
