package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class AuthServiceTest {
    private UserRepository users;
    private PasswordEncoder passwords;
    private AuditLog audit;
    private EmailVerificationService verification;
    private AuthService service;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        audit = mock(AuditLog.class);
        verification = mock(EmailVerificationService.class);
        service =
                new AuthService(
                        users,
                        passwords,
                        audit,
                        Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC),
                        verification);
    }

    @Test
    void rejectsMinorBeforeHashOrDatabaseAccess() {
        assertCode(
                request(
                        "adult@example.test",
                        "ValidPassword2026!",
                        LocalDate.of(2008, 10, 9),
                        "30111222",
                        "+54 261 555-0123",
                        "San Martín 123, Mendoza"),
                "ADULT_REQUIRED");
        verifyNoInteractions(users, passwords, audit, verification);
    }

    @Test
    void eighteenthBirthdayCreatesPendingAccountWithNormalizedContact() {
        var actor =
                new Actor(
                        UUID.randomUUID(),
                        "persona@example.test",
                        "Persona",
                        Set.of("BUYER"),
                        Set.of());
        var pending =
                new AuthDtos.Result("EMAIL_VERIFICATION_PENDING", "Confirmá tu correo.", true);
        when(passwords.encode("ValidPassword2026!")).thenReturn("safe-hash");
        when(users.register(
                        "persona@example.test",
                        "Persona",
                        "safe-hash",
                        LocalDate.of(2008, 10, 8),
                        "30111222",
                        "+542615550123",
                        "San Martín 123, Mendoza"))
                .thenReturn(Optional.of(actor));
        when(verification.registrationPending("persona@example.test")).thenReturn(pending);
        assertThat(
                        service.register(
                                new AuthDtos.Registration(
                                        " PERSONA@EXAMPLE.TEST ",
                                        "ValidPassword2026!",
                                        " Persona ",
                                        LocalDate.of(2008, 10, 8),
                                        "30.111.222",
                                        "+54 (261) 555-0123",
                                        " San Martín 123, Mendoza ")))
                .isEqualTo(pending);
        verify(audit)
                .record(eq(actor), eq("USER_REGISTERED"), eq("USER"), eq(actor.id()), anyMap());
        verify(users, never()).verifyEmail(any(), any());
    }

    @Test
    void rejectsPasswordWhichBcryptWouldTruncateOrIsBlank() {
        assertCode(
                request(
                        "persona@example.test",
                        "á".repeat(37),
                        LocalDate.of(1990, 1, 1),
                        "30111222",
                        "2615550123",
                        "San Martín 123"),
                "INVALID_PASSWORD");
        assertCode(
                request(
                        "persona@example.test",
                        " ".repeat(12),
                        LocalDate.of(1990, 1, 1),
                        "30111222",
                        "2615550123",
                        "San Martín 123"),
                "INVALID_PASSWORD");
        verifyNoInteractions(users, passwords, audit, verification);
    }

    @Test
    void validatesIdentityAndContactBeforeWriting() {
        assertCode(
                request(
                        "persona@example.test",
                        "ValidPassword2026!",
                        LocalDate.of(1990, 1, 1),
                        "ABC30111222",
                        "2615550123",
                        "San Martín 123"),
                "INVALID_DNI");
        assertCode(
                request(
                        "persona@example.test",
                        "ValidPassword2026!",
                        LocalDate.of(1990, 1, 1),
                        "30111222",
                        "telefono",
                        "San Martín 123"),
                "INVALID_PHONE");
        assertCode(
                request(
                        "persona@example.test",
                        "ValidPassword2026!",
                        LocalDate.of(1990, 1, 1),
                        "30111222",
                        "2615550123",
                        "x"),
                "INVALID_ADDRESS");
        verifyNoInteractions(users, passwords, audit, verification);
    }

    @Test
    void duplicateEmailOrDniReceivesSamePendingResponseWithoutActorOrAccountDetails() {
        var pending =
                new AuthDtos.Result(
                        "EMAIL_VERIFICATION_PENDING", "Si corresponde, recibirás un enlace.", true);
        when(passwords.encode(anyString())).thenReturn("safe-hash");
        when(users.register(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(verification.registrationPending("persona@example.test")).thenReturn(pending);
        assertThat(
                        service.register(
                                request(
                                        "persona@example.test",
                                        "ValidPassword2026!",
                                        LocalDate.of(1990, 1, 1),
                                        "30111222",
                                        "2615550123",
                                        "San Martín 123")))
                .isEqualTo(pending);
        verifyNoInteractions(audit);
        verify(users, never()).findActiveActor(any());
    }

    private AuthDtos.Registration request(
            String email,
            String password,
            LocalDate birthDate,
            String dni,
            String phone,
            String address) {
        return new AuthDtos.Registration(
                email, password, "Persona", birthDate, dni, phone, address);
    }

    private void assertCode(AuthDtos.Registration request, String code) {
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOfSatisfying(
                        AppException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
