package ar.emedicapp.auth;

import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final Clock clock;

    public AuthService(
            UserRepository users, PasswordEncoder passwords, AuditLog audit, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Actor register(String email, String password, String name, LocalDate birthDate) {
        var normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        var normalizedName = name == null ? "" : name.trim();
        if (normalizedEmail.length() > 254
                || !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw AppException.badRequest("INVALID_EMAIL", "Ingresá un correo válido.");
        }
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw AppException.badRequest(
                    "INVALID_NAME", "Ingresá tu nombre (hasta 120 caracteres).");
        }
        if (password == null
                || password.isBlank()
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw AppException.badRequest(
                    "INVALID_PASSWORD",
                    "La contraseña debe tener al menos 12 caracteres y hasta 72 bytes.");
        }
        if (birthDate == null || birthDate.isAfter(LocalDate.now(clock).minusYears(18))) {
            throw AppException.badRequest(
                    "ADULT_REQUIRED",
                    "La compra está habilitada únicamente para personas adultas.");
        }
        if (birthDate.isBefore(LocalDate.now(clock).minusYears(125))) {
            throw AppException.badRequest("INVALID_BIRTH_DATE", "Revisá la fecha de nacimiento.");
        }
        try {
            var actor =
                    users.register(
                            normalizedEmail, normalizedName, passwords.encode(password), birthDate);
            audit.record(
                    actor, "USER_REGISTERED", "USER", actor.id(), Map.of("roles", actor.roles()));
            return actor;
        } catch (DuplicateKeyException exception) {
            throw AppException.conflict(
                    "EMAIL_UNAVAILABLE",
                    "No se puede registrar esa cuenta. Si ya tenés acceso, ingresá o contactá a soporte.");
        }
    }
}
