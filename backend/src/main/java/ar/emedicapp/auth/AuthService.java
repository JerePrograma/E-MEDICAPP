package ar.emedicapp.auth;

import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final Clock clock;
    private final EmailVerificationService verification;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwords,
            AuditLog audit,
            Clock clock,
            EmailVerificationService verification) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
        this.verification = verification;
    }

    @Transactional
    public AuthDtos.Result register(AuthDtos.Registration request) {
        String email = request.email(), password = request.password(), name = request.name();
        LocalDate birthDate = request.birthDate();
        var normalizedEmail = EmailVerificationService.normalizeEmail(email);
        var normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw AppException.badRequest(
                    "INVALID_NAME", "Ingresá tu nombre (hasta 120 caracteres).");
        }
        validatePassword(password);
        if (birthDate == null || birthDate.isAfter(LocalDate.now(clock).minusYears(18))) {
            throw AppException.badRequest(
                    "ADULT_REQUIRED",
                    "La compra está habilitada únicamente para personas adultas.");
        }
        if (birthDate.isBefore(LocalDate.now(clock).minusYears(125))) {
            throw AppException.badRequest("INVALID_BIRTH_DATE", "Revisá la fecha de nacimiento.");
        }
        String dni = request.dni() == null ? "" : request.dni().trim().replaceAll("[.\\s]", "");
        if (!dni.matches("[0-9]{7,12}"))
            throw AppException.badRequest("INVALID_DNI", "Ingresá un DNI de 7 a 12 dígitos.");
        String phone =
                request.phone() == null ? "" : request.phone().trim().replaceAll("[\\s()\\-]", "");
        if (!phone.matches("\\+?[0-9]{7,20}"))
            throw AppException.badRequest(
                    "INVALID_PHONE",
                    "Ingresá un teléfono de contacto válido, con entre 7 y 20 dígitos.");
        String address = request.address() == null ? "" : request.address().trim();
        if (address.length() < 5 || address.length() > 300)
            throw AppException.badRequest(
                    "INVALID_ADDRESS", "Ingresá tu domicilio, entre 5 y 300 caracteres.");
        users.register(
                        normalizedEmail,
                        normalizedName,
                        passwords.encode(password),
                        birthDate,
                        dni,
                        phone,
                        address)
                .ifPresent(
                        actor -> {
                            audit.record(
                                    actor,
                                    "USER_REGISTERED",
                                    "USER",
                                    actor.id(),
                                    Map.of("roles", actor.roles()));
                        });
        return verification.registrationPending(normalizedEmail);
    }

    static void validatePassword(String password) {
        if (password == null
                || password.isBlank()
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw AppException.badRequest(
                    "INVALID_PASSWORD",
                    "La contraseña debe tener al menos 12 caracteres y hasta 72 bytes.");
    }
}
