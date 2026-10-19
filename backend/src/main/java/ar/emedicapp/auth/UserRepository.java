package ar.emedicapp.auth;

import ar.emedicapp.common.Actor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository implements UserDetailsService {
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user =
                jdbc.sql(
                                "SELECT id, email, password_hash, active, email_verified,"
                                        + " credentials_version FROM app_user WHERE email = :email")
                        .param("email", username.trim().toLowerCase(java.util.Locale.ROOT))
                        .query(
                                (rs, row) ->
                                        new LoginUser(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("email"),
                                                rs.getString("password_hash"),
                                                rs.getBoolean("active"),
                                                rs.getBoolean("email_verified"),
                                                rs.getLong("credentials_version")))
                        .optional()
                        .orElseThrow(() -> new UsernameNotFoundException("Cuenta no disponible."));
        var authorities =
                roles(user.id()).stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
        return new UserPrincipal(
                user.id(),
                user.email(),
                user.passwordHash(),
                user.active(),
                user.emailVerified(),
                user.credentialsVersion(),
                authorities);
    }

    public Optional<Actor> findActiveActor(UUID id) {
        return jdbc.sql(
                        "SELECT id, email, name FROM app_user WHERE id = :id AND active = true AND"
                                + " email_verified = true")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new ActorRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("email"),
                                        rs.getString("name")))
                .optional()
                .map(
                        user ->
                                new Actor(
                                        user.id(),
                                        user.email(),
                                        user.name(),
                                        new HashSet<>(roles(id)),
                                        new HashSet<>(branches(id))));
    }

    public Optional<Actor> register(
            String email,
            String name,
            String passwordHash,
            LocalDate birthDate,
            String dni,
            String phone,
            String address) {
        UUID id = UUID.randomUUID();
        var created =
                jdbc.sql(
                                """
                                INSERT INTO app_user (id, email, name, password_hash, birth_date, active, dni, phone, address, email_verified)
                                VALUES (:id, :email, :name, :hash, :birthDate, true, :dni, :phone, :address, false)
                                ON CONFLICT DO NOTHING RETURNING id
                                """)
                        .param("id", id)
                        .param("email", email)
                        .param("name", name)
                        .param("hash", passwordHash)
                        .param("birthDate", birthDate)
                        .param("dni", dni)
                        .param("phone", phone)
                        .param("address", address)
                        .query(UUID.class)
                        .optional();
        if (created.isEmpty()) return Optional.empty();
        jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:id, 'BUYER')")
                .param("id", id)
                .update();
        return Optional.of(
                new Actor(id, email, name, java.util.Set.of("BUYER"), java.util.Set.of()));
    }

    public Optional<EmailAccount> emailAccount(String email) {
        return jdbc.sql("SELECT id,active,email_verified FROM app_user WHERE email=:email")
                .param("email", email)
                .query(
                        (rs, row) ->
                                new EmailAccount(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("active"),
                                        rs.getBoolean("email_verified")))
                .optional();
    }

    public void verifyEmail(UUID id, Instant now) {
        jdbc.sql(
                        "UPDATE app_user SET email_verified=true,email_verified_at=:now WHERE"
                                + " id=:id AND active")
                .param("id", id)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    public void resetPassword(UUID id, String passwordHash) {
        jdbc.sql(
                        "UPDATE app_user SET"
                            + " password_hash=:hash,credentials_version=credentials_version+1 WHERE"
                            + " id=:id AND active")
                .param("id", id)
                .param("hash", passwordHash)
                .update();
    }

    public boolean sessionValid(UUID id, long credentialsVersion) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND active AND"
                                + " email_verified AND credentials_version=:version)")
                .param("id", id)
                .param("version", credentialsVersion)
                .query(Boolean.class)
                .single();
    }

    public Contact contact(UUID id) {
        return jdbc.sql(
                        "SELECT dni,phone,address FROM app_user WHERE id=:id AND active AND"
                                + " email_verified")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Contact(
                                        rs.getString("dni"),
                                        rs.getString("phone"),
                                        rs.getString("address")))
                .single();
    }

    public LocalDate birthDate(UUID id) {
        return jdbc.sql("SELECT birth_date FROM app_user WHERE id=:id AND active")
                .param("id", id)
                .query(LocalDate.class)
                .single();
    }

    public java.util.Map<UUID, java.util.Set<String>> branchRoles(UUID id) {
        java.util.Map<UUID, java.util.Set<String>> result = new java.util.LinkedHashMap<>();
        for (var row :
                jdbc.sql(
                                "SELECT m.branch_id,m.role FROM membership m JOIN user_role ur ON"
                                    + " ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=:id"
                                    + " AND m.active ORDER BY m.branch_id,m.role")
                        .param("id", id)
                        .query()
                        .listOfRows()) {
            result.computeIfAbsent((UUID) row.get("branch_id"), key -> new HashSet<>())
                    .add((String) row.get("role"));
        }
        return result;
    }

    private java.util.List<String> roles(UUID id) {
        return jdbc.sql("SELECT role FROM user_role WHERE user_id = :id ORDER BY role")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private java.util.List<UUID> branches(UUID id) {
        return jdbc.sql("SELECT branch_id FROM user_branch WHERE user_id = :id ORDER BY branch_id")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    public record EmailAccount(UUID id, boolean active, boolean emailVerified) {}

    public record Contact(String dni, String phone, String address) {}

    private record LoginUser(
            UUID id,
            String email,
            String passwordHash,
            boolean active,
            boolean emailVerified,
            long credentialsVersion) {}

    private record ActorRow(UUID id, String email, String name) {}
}
