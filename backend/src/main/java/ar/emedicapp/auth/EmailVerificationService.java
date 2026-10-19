package ar.emedicapp.auth;

import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailVerificationService {
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final EmailSender sender;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final Clock clock;
    private final int verificationMinutes;
    private final int resetMinutes;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Window> requests = new LinkedHashMap<>(16, 0.75f, true);

    public EmailVerificationService(
            UserRepository users,
            AuthTokenRepository tokens,
            EmailSender sender,
            PasswordEncoder passwords,
            AuditLog audit,
            Clock clock,
            @Value("${EMEDICAPP_EMAIL_VERIFY_TTL_MINUTES:30}") int verificationMinutes,
            @Value("${EMEDICAPP_PASSWORD_RESET_TTL_MINUTES:15}") int resetMinutes) {
        if (verificationMinutes < 1
                || verificationMinutes > 1440
                || resetMinutes < 1
                || resetMinutes > 1440)
            throw new IllegalArgumentException(
                    "Los enlaces de correo deben vencer entre 1 y 1440 minutos.");
        this.users = users;
        this.tokens = tokens;
        this.sender = sender;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
        this.verificationMinutes = verificationMinutes;
        this.resetMinutes = resetMinutes;
    }

    @Transactional
    public AuthDtos.Result resend(String email) {
        return request(email, "VERIFY_EMAIL");
    }

    @Transactional
    public AuthDtos.Result requestReset(String email) {
        return request(email, "RESET_PASSWORD");
    }

    private AuthDtos.Result request(String email, String purpose) {
        String normalized = normalizeEmail(email);
        allowRequest(normalized + ":" + purpose);
        return issue(normalized, purpose);
    }

    AuthDtos.Result registrationPending(String email) {
        allowRequest(email + ":VERIFY_EMAIL");
        return issue(email, "VERIFY_EMAIL");
    }

    private AuthDtos.Result issue(String email, String purpose) {
        if (!sender.configured()) return pending(purpose, false);
        var account = users.emailAccount(email);
        if (account.isPresent()
                && account.get().active()
                && (!purpose.equals("VERIFY_EMAIL") || !account.get().emailVerified())) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            int ttl = purpose.equals("VERIFY_EMAIL") ? verificationMinutes : resetMinutes;
            tokens.replace(
                    account.get().id(),
                    purpose,
                    hash(token),
                    clock.instant(),
                    clock.instant().plus(Duration.ofMinutes(ttl)));
            try {
                sender.send(email, purpose, token, ttl);
            } catch (MailException exception) {
                throw new AppException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_DELIVERY_UNAVAILABLE",
                        "No se pudo enviar el correo. Reintentá más tarde o consultá a"
                                + " administración; la cuenta no se confirma automáticamente.");
            }
        }
        return pending(purpose, true);
    }

    @Transactional
    public AuthDtos.Result confirm(String token) {
        var user =
                tokens.consume(hashToken(token), "VERIFY_EMAIL", clock.instant())
                        .orElseThrow(EmailVerificationService::invalidToken);
        users.verifyEmail(user, clock.instant());
        audit.record(
                users.findActiveActor(user).orElseThrow(),
                "EMAIL_VERIFIED",
                "USER",
                user,
                Map.of());
        return new AuthDtos.Result(
                "EMAIL_VERIFIED", "Correo confirmado. Ya podés ingresar a tu cuenta.", false);
    }

    @Transactional
    public AuthDtos.Result resetPassword(String token, String password) {
        AuthService.validatePassword(password);
        var user =
                tokens.consume(hashToken(token), "RESET_PASSWORD", clock.instant())
                        .orElseThrow(EmailVerificationService::invalidToken);
        users.resetPassword(user, passwords.encode(password));
        audit.record(null, "PASSWORD_RESET", "USER", user, Map.of("previousSessionsRevoked", true));
        return new AuthDtos.Result(
                "PASSWORD_RESET",
                "Contraseña actualizada. Ingresá nuevamente; el correo debe estar confirmado.",
                false);
    }

    private synchronized void allowRequest(String key) {
        long now = clock.millis();
        requests.entrySet().removeIf(entry -> now - entry.getValue().since >= 3_600_000);
        var window = requests.computeIfAbsent(key, ignored -> new Window(now));
        if (requests.size() > 5000) requests.remove(requests.keySet().iterator().next());
        if ((window.count > 0 && now - window.last < 60_000) || window.count >= 5)
            throw new AppException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "EMAIL_REQUEST_RATE_LIMIT",
                    "Esperá al menos un minuto antes de solicitar otro correo. Se permiten hasta"
                            + " cinco solicitudes por hora.");
        window.count++;
        window.last = now;
    }

    private static AuthDtos.Result pending(String purpose, boolean configured) {
        if (!configured)
            return new AuthDtos.Result(
                    "EMAIL_CONFIGURATION_PENDING",
                    "El envío de correos todavía no está configurado. Administración debe habilitar"
                            + " SMTP; ninguna cuenta se confirma automáticamente.",
                    true);
        return new AuthDtos.Result(
                purpose.equals("VERIFY_EMAIL")
                        ? "EMAIL_VERIFICATION_PENDING"
                        : "PASSWORD_RESET_PENDING",
                purpose.equals("VERIFY_EMAIL")
                        ? "Si los datos corresponden a una cuenta pendiente, recibirás un enlace"
                              + " para confirmar el correo. Revisá también correo no deseado. Si ya"
                              + " tenés una cuenta, ingresá o recuperá tu acceso."
                        : "Si existe una cuenta habilitada con ese correo, recibirás un enlace para"
                                + " recuperar el acceso.",
                purpose.equals("VERIFY_EMAIL"));
    }

    static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw AppException.badRequest("INVALID_EMAIL", "Ingresá un correo válido.");
        return value;
    }

    private static String hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        return hash(token);
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AppException invalidToken() {
        return AppException.badRequest(
                "AUTH_TOKEN_INVALID",
                "El enlace no es válido, venció o ya fue utilizado. Solicitá uno nuevo.");
    }

    private static final class Window {
        private final long since;
        private long last;
        private int count;

        private Window(long since) {
            this.since = since;
        }
    }
}
