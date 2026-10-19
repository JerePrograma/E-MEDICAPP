package ar.emedicapp.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.UUID;

public final class UserPrincipal extends User {
    private final UUID id;
    private final boolean emailVerified;
    private final long credentialsVersion;

    public UserPrincipal(
            UUID id,
            String email,
            String passwordHash,
            boolean active,
            Collection<? extends GrantedAuthority> authorities) {
        this(id, email, passwordHash, active, true, 0, authorities);
    }

    public UserPrincipal(
            UUID id,
            String email,
            String passwordHash,
            boolean active,
            boolean emailVerified,
            long credentialsVersion,
            Collection<? extends GrantedAuthority> authorities) {
        super(email, passwordHash, active, true, true, true, authorities);
        this.id = id;
        this.emailVerified = emailVerified;
        this.credentialsVersion = credentialsVersion;
    }

    public UUID id() {
        return id;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public long credentialsVersion() {
        return credentialsVersion;
    }
}
