package ar.emedicapp.auth;

import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private UserRepository users;
    private PasswordEncoder passwords;
    private AuditLog audit;
    private AuthService service;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        audit = mock(AuditLog.class);
        service =
                new AuthService(
                        users,
                        passwords,
                        audit,
                        Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsMinorBeforeHashOrDatabaseAccess() {
        assertThatThrownBy(
                        () ->
                                service.register(
                                        "adult@example.test",
                                        "ValidPassword2026!",
                                        "Persona",
                                        LocalDate.of(2008, 10, 9)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("ADULT_REQUIRED"));
        verifyNoInteractions(users, passwords, audit);
    }

    @Test
    void eighteenthBirthdayIsAcceptedAndEmailIsNormalized() {
        var actor =
                new Actor(
                        UUID.randomUUID(),
                        "persona@example.test",
                        "Persona",
                        Set.of("BUYER"),
                        Set.of());
        when(passwords.encode("ValidPassword2026!")).thenReturn("safe-hash");
        when(users.register(
                        "persona@example.test", "Persona", "safe-hash", LocalDate.of(2008, 10, 8)))
                .thenReturn(actor);
        assertThat(
                        service.register(
                                " PERSONA@EXAMPLE.TEST ",
                                "ValidPassword2026!",
                                " Persona ",
                                LocalDate.of(2008, 10, 8)))
                .isEqualTo(actor);
        verify(audit)
                .record(eq(actor), eq("USER_REGISTERED"), eq("USER"), eq(actor.id()), anyMap());
    }

    @Test
    void rejectsPasswordWhichBcryptWouldTruncate() {
        assertThatThrownBy(
                        () ->
                                service.register(
                                        "persona@example.test",
                                        "á".repeat(37),
                                        "Persona",
                                        LocalDate.of(1990, 1, 1)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PASSWORD"));
        verifyNoInteractions(users, passwords, audit);
    }

    @Test
    void rejectsBlankPasswordEvenWhenLongEnough() {
        assertThatThrownBy(
                        () ->
                                service.register(
                                        "persona@example.test",
                                        " ".repeat(12),
                                        "Persona",
                                        LocalDate.of(1990, 1, 1)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PASSWORD"));
        verifyNoInteractions(users, passwords, audit);
    }

    @Test
    void duplicateEmailNeverLeaksSqlOrAccountDetails() {
        when(passwords.encode(anyString())).thenReturn("safe-hash");
        when(users.register(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("private database details"));
        assertThatThrownBy(
                        () ->
                                service.register(
                                        "persona@example.test",
                                        "ValidPassword2026!",
                                        "Persona",
                                        LocalDate.of(1990, 1, 1)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("EMAIL_UNAVAILABLE");
                            assertThat(error.getMessage()).doesNotContain("private database");
                        });
    }
}
