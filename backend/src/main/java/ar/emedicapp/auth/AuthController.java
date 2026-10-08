package ar.emedicapp.auth;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final Access access;
    private final AuthService auth;
    private final UserRepository users;

    public AuthController(Access access, AuthService auth, UserRepository users) {
        this.access = access;
        this.auth = auth;
        this.users = users;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken csrf) {
        return new CsrfResponse(csrf.getToken(), csrf.getHeaderName());
    }

    @GetMapping("/me")
    SessionProfile me() {
        Actor actor = access.actor();
        return new SessionProfile(
                actor.id(),
                actor.email(),
                actor.name(),
                actor.roles(),
                actor.branchIds(),
                users.birthDate(actor.id()),
                users.branchRoles(actor.id()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Actor register(@RequestBody Registration request) {
        return auth.register(
                request.email(), request.password(), request.name(), request.birthDate());
    }

    public record Registration(String email, String password, String name, LocalDate birthDate) {}

    public record CsrfResponse(String token, String headerName) {}

    public record SessionProfile(
            java.util.UUID id,
            String email,
            String name,
            java.util.Set<String> roles,
            java.util.Set<java.util.UUID> branchIds,
            LocalDate birthDate,
            java.util.Map<java.util.UUID, java.util.Set<String>> branchRoles) {}
}
