package ar.emedicapp.auth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmailSenderTest {
    @Test
    void requiresExplicitSmtpSenderAndSafePublicOrigin() {
        assertThat(sender("", "", "https://medicapp.example.test", true, false).configured())
                .isFalse();
        assertThat(sender("smtp.example.test", "valid@example.test", "", true, false).configured())
                .isFalse();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "http://medicapp.example.test",
                                        true,
                                        false)
                                .configured())
                .isFalse();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "https://user:secret@example.test",
                                        true,
                                        false)
                                .configured())
                .isFalse();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "https://example.test/?secret=bad",
                                        true,
                                        false)
                                .configured())
                .isFalse();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "https://medicapp.example.test",
                                        true,
                                        false)
                                .configured())
                .isTrue();
    }

    @Test
    void permitsIsolatedLoopbackSinkAndRejectsConflictingTlsModes() {
        assertThat(
                        sender(
                                        "127.0.0.1",
                                        "qa@example.test",
                                        "http://127.0.0.1:8085",
                                        false,
                                        false)
                                .configured())
                .isTrue();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "https://medicapp.example.test",
                                        true,
                                        true)
                                .configured())
                .isFalse();
        assertThat(
                        sender(
                                        "smtp.example.test",
                                        "valid@example.test",
                                        "https://medicapp.example.test",
                                        false,
                                        true)
                                .configured())
                .isTrue();
    }

    @Test
    void supportsTheGithubPagesProjectPathForVerificationAndRecovery() {
        assertThat(sender("smtp.example.test", "valid@example.test",
                "https://jereprograma.github.io/E-MEDICAPP/", true, false).configured()).isTrue();
        assertThat(sender("smtp.example.test", "valid@example.test",
                "https://jereprograma.github.io/E-MEDICAPP/#token", true, false).configured()).isFalse();
        assertThat(sender("smtp.example.test", "valid@example.test",
                "https://jereprograma.github.io/E-MEDICAPP/../other", true, false).configured()).isFalse();
    }

    @Test
    void incompleteConfigurationNeverAttemptsDelivery() {
        assertThatThrownBy(
                        () ->
                                sender("", "", "", true, false)
                                        .send(
                                                "recipient@example.test",
                                                "VERIFY_EMAIL",
                                                "secret",
                                                30))
                .isInstanceOf(IllegalStateException.class);
    }

    private EmailSender sender(String host, String from, String url, boolean tls, boolean ssl) {
        return new EmailSender(host, 587, "", "", from, url, tls, ssl);
    }
}
