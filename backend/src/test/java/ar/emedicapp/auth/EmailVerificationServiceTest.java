package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class EmailVerificationServiceTest {
    private UserRepository users;
    private AuthTokenRepository tokens;
    private EmailSender sender;
    private PasswordEncoder passwords;
    private AuditLog audit;
    private MutableClock clock;
    private EmailVerificationService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        tokens = mock(AuthTokenRepository.class);
        sender = mock(EmailSender.class);
        passwords = mock(PasswordEncoder.class);
        audit = mock(AuditLog.class);
        clock = new MutableClock();
        service =
                new EmailVerificationService(
                        users, tokens, sender, passwords, audit, clock, 30, 15);
    }

    @Test
    void missingSmtpNeverIssuesTokenOrConfirmsAccount() {
        assertThat(service.resend("PERSONA@EXAMPLE.TEST").status())
                .isEqualTo("EMAIL_CONFIGURATION_PENDING");
        verifyNoInteractions(users, tokens, passwords, audit);
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void persistedTokenIsHashAndHasExpiryWhileOnlyMailReceivesSecret() {
        when(sender.configured()).thenReturn(true);
        when(users.emailAccount("persona@example.test"))
                .thenReturn(Optional.of(new UserRepository.EmailAccount(user, true, false)));
        var result = service.resend("persona@example.test");
        var token = ArgumentCaptor.forClass(String.class);
        verify(sender)
                .send(eq("persona@example.test"), eq("VERIFY_EMAIL"), token.capture(), eq(30));
        assertThat(token.getValue()).matches("[A-Za-z0-9_-]{43}");
        verify(tokens)
                .replace(
                        user,
                        "VERIFY_EMAIL",
                        EmailVerificationService.hash(token.getValue()),
                        clock.instant(),
                        clock.instant().plusSeconds(1800));
        assertThat(result.toString()).doesNotContain(token.getValue());
        verify(users, never()).verifyEmail(any(), any());
    }

    @Test
    void existingVerifiedInactiveAndUnknownAccountsUseSameConfidentialResponse() {
        when(sender.configured()).thenReturn(true);
        when(users.emailAccount("verified@example.test"))
                .thenReturn(Optional.of(new UserRepository.EmailAccount(user, true, true)));
        when(users.emailAccount("inactive@example.test"))
                .thenReturn(Optional.of(new UserRepository.EmailAccount(user, false, false)));
        assertThat(service.resend("verified@example.test"))
                .isEqualTo(service.resend("unknown@example.test"))
                .isEqualTo(service.resend("inactive@example.test"));
        verifyNoInteractions(tokens);
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void smtpFailureIsExplicitAndDoesNotApproveAccount() {
        when(sender.configured()).thenReturn(true);
        when(users.emailAccount("persona@example.test"))
                .thenReturn(Optional.of(new UserRepository.EmailAccount(user, true, false)));
        doThrow(new MailSendException("private SMTP detail"))
                .when(sender)
                .send(anyString(), anyString(), anyString(), anyInt());
        assertThatThrownBy(() -> service.resend("persona@example.test"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
                            assertThat(error.getMessage()).doesNotContain("private SMTP");
                        });
        verify(users, never()).verifyEmail(any(), any());
    }

    @Test
    void resendRequiresOneMinuteAndStopsAfterFiveRequestsIncludingUnknownEmail() {
        service.resend("unknown@example.test");
        assertRateLimit(() -> service.resend(" UNKNOWN@EXAMPLE.TEST "));
        for (int i = 0; i < 4; i++) {
            clock.advance(60);
            service.resend("unknown@example.test");
        }
        clock.advance(60);
        assertRateLimit(() -> service.resend("unknown@example.test"));
        clock.advance(3600);
        assertThat(service.resend("unknown@example.test").status())
                .isEqualTo("EMAIL_CONFIGURATION_PENDING");
    }

    @Test
    void confirmationUsesItsPurposeAndRejectsUsedExpiredOrMalformedToken() {
        String secret = "a".repeat(43), hash = EmailVerificationService.hash(secret);
        when(tokens.consume(hash, "VERIFY_EMAIL", clock.instant()))
                .thenReturn(Optional.of(user), Optional.empty());
        when(users.findActiveActor(user))
                .thenReturn(
                        Optional.of(
                                new Actor(
                                        user,
                                        "persona@example.test",
                                        "Persona",
                                        Set.of("BUYER"),
                                        Set.of())));
        assertThat(service.confirm(secret).status()).isEqualTo("EMAIL_VERIFIED");
        verify(users).verifyEmail(user, clock.instant());
        assertInvalid(() -> service.confirm(secret));
        assertInvalid(() -> service.confirm("short"));
        verify(tokens, times(2)).consume(hash, "VERIFY_EMAIL", clock.instant());
    }

    @Test
    void passwordResetConsumesOnlyResetTokenAndRevokesSessionsWithoutVerifyingEmail() {
        String secret = "b".repeat(43);
        when(tokens.consume(
                        EmailVerificationService.hash(secret), "RESET_PASSWORD", clock.instant()))
                .thenReturn(Optional.of(user));
        when(passwords.encode("NewValidPassword2026!")).thenReturn("new-hash");
        assertThat(service.resetPassword(secret, "NewValidPassword2026!").status())
                .isEqualTo("PASSWORD_RESET");
        verify(users).resetPassword(user, "new-hash");
        verify(users, never()).verifyEmail(any(), any());
        verify(audit)
                .record(
                        isNull(),
                        eq("PASSWORD_RESET"),
                        eq("USER"),
                        eq(user),
                        argThat(
                                details ->
                                        Boolean.TRUE.equals(
                                                details.get("previousSessionsRevoked"))));
    }

    @Test
    void invalidResetPasswordDoesNotConsumeToken() {
        assertThatThrownBy(() -> service.resetPassword("c".repeat(43), "short"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PASSWORD"));
        verifyNoInteractions(tokens, users, passwords, audit);
    }

    private void assertRateLimit(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_REQUEST_RATE_LIMIT"));
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("AUTH_TOKEN_INVALID"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-09T15:00:00Z");

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
