package io.github.wynyga.ledger.adapter.out.security;

import io.github.wynyga.ledger.application.TokenIssuer;
import io.github.wynyga.ledger.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Signs HS256 access tokens. {@code sub} is the user id and {@code scope} carries the permissions, which Spring
 * Security turns into {@code SCOPE_ledger:user} / {@code SCOPE_ledger:admin} authorities.
 */
public class JwtTokenIssuer implements TokenIssuer {
    public static final String SCOPE_USER = "ledger:user";
    public static final String SCOPE_ADMIN = "ledger:admin";

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public JwtTokenIssuer(JwtEncoder encoder, String issuer, Duration ttl, Clock clock) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public AccessToken issue(User user) {
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.id().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim("username", user.username())
                .claim("scope", user.isAdmin() ? SCOPE_USER + " " + SCOPE_ADMIN : SCOPE_USER)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, now, expires);
    }
}
