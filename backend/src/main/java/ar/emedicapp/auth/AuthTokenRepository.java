package ar.emedicapp.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthTokenRepository {
    private final JdbcClient jdbc;

    public AuthTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void replace(UUID userId, String purpose, String hash, Instant now, Instant expiresAt) {
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .single();
        jdbc.sql(
                        "UPDATE auth_token SET used_at=:now WHERE user_id=:user AND"
                                + " purpose=:purpose AND used_at IS NULL")
                .param("now", time(now))
                .param("user", userId)
                .param("purpose", purpose)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                                + " auth_token(id,user_id,purpose,token_hash,created_at,expires_at)"
                                + " VALUES(:id,:user,:purpose,:hash,:now,:expires)")
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("purpose", purpose)
                .param("hash", hash)
                .param("now", time(now))
                .param("expires", time(expiresAt))
                .update();
    }

    Optional<UUID> consume(String hash, String purpose, Instant now) {
        var owner =
                jdbc.sql(
                                "SELECT user_id FROM auth_token WHERE token_hash=:hash AND"
                                        + " purpose=:purpose")
                        .param("hash", hash)
                        .param("purpose", purpose)
                        .query(UUID.class)
                        .optional();
        if (owner.isEmpty()) return Optional.empty();
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR UPDATE")
                .param("id", owner.get())
                .query(UUID.class)
                .single();
        return jdbc.sql(
                        """
                        UPDATE auth_token t SET used_at=:now
                        WHERE t.token_hash=:hash AND t.purpose=:purpose AND t.used_at IS NULL AND t.expires_at>:now
                          AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=t.user_id AND u.active)
                        RETURNING t.user_id
                        """)
                .param("now", time(now))
                .param("hash", hash)
                .param("purpose", purpose)
                .query(UUID.class)
                .optional();
    }

    private static OffsetDateTime time(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
