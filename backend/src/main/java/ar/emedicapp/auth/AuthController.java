package ar.emedicapp.auth;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final Access access;
    private final AuthService auth;
    private final UserRepository users;
    private final EmailVerificationService verification;

    public AuthController(
            Access access,
            AuthService auth,
            UserRepository users,
            EmailVerificationService verification) {
        this.access = access;
        this.auth = auth;
        this.users = users;
        this.verification = verification;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken csrf) {
        return new CsrfResponse(csrf.getToken(), csrf.getHeaderName());
    }

    @GetMapping("/me")
    SessionProfile me() {
        Actor actor = access.actor();
        var contact = users.contact(actor.id());
        return new SessionProfile(
                actor.id(),
                actor.email(),
                actor.name(),
                actor.roles(),
                actor.branchIds(),
                users.birthDate(actor.id()),
                users.branchRoles(actor.id()),
                true,
                contact.dni(),
                contact.phone(),
                contact.address());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthDtos.Result register(@RequestBody AuthDtos.Registration request) {
        return auth.register(request);
    }

    @PostMapping("/verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthDtos.Result resend(@RequestBody AuthDtos.EmailRequest request) {
        return verification.resend(request.email());
    }

    @PostMapping("/verification/confirm")
    AuthDtos.Result confirm(@RequestBody AuthDtos.TokenRequest request) {
        return verification.confirm(request.token());
    }

    @PostMapping("/password/request-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthDtos.Result requestReset(@RequestBody AuthDtos.EmailRequest request) {
        return verification.requestReset(request.email());
    }

    @PostMapping("/password/reset")
    AuthDtos.Result reset(@RequestBody AuthDtos.PasswordReset request) {
        return verification.resetPassword(request.token(), request.password());
    }

    public record CsrfResponse(String token, String headerName) {}

    public record SessionProfile(
            java.util.UUID id,
            String email,
            String name,
            java.util.Set<String> roles,
            java.util.Set<java.util.UUID> branchIds,
            LocalDate birthDate,
            java.util.Map<java.util.UUID, java.util.Set<String>> branchRoles,
            boolean emailVerified,
            String dni,
            String phone,
            String address) {}
}
