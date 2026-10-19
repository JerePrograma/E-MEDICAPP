package ar.emedicapp.auth;

import java.time.LocalDate;

public final class AuthDtos {
    private AuthDtos() {}

    public record Registration(
            String email,
            String password,
            String name,
            LocalDate birthDate,
            String dni,
            String phone,
            String address) {}

    public record EmailRequest(String email) {}

    public record TokenRequest(String token) {}

    public record PasswordReset(String token, String password) {}

    public record Result(String status, String message, boolean emailVerificationRequired) {}
}
