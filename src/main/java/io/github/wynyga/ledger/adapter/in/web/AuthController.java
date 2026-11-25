package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.application.AuthService;
import io.github.wynyga.ledger.application.TokenIssuer.AccessToken;
import io.github.wynyga.ledger.domain.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth", description = "Register, sign in, and inspect the current token")
@RequestMapping("/api/v1/auth")
class AuthController {

    record Credentials(@NotBlank String username, @NotBlank String password) {
    }

    record UserResponse(UUID id, String username, String role, Instant createdAt) {
        static UserResponse of(User u) {
            return new UserResponse(u.id(), u.username(), u.role().name(), u.createdAt());
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    record MeResponse(UUID id, String username, boolean admin, Instant expiresAt) {
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody Credentials req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserResponse.of(auth.register(req.username(), req.password())));
    }

    /** RFC 6749 style: tokens must not be cached. */
    @PostMapping("/token")
    ResponseEntity<TokenResponse> token(@Valid @RequestBody Credentials req) {
        AccessToken t = auth.login(req.username(), req.password());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(t.value(), "Bearer", t.expiresInSeconds()));
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var caller = Callers.from(jwt);
        return new MeResponse(caller.userId(), jwt.getClaimAsString("username"), caller.admin(), jwt.getExpiresAt());
    }
}
