package io.github.wynyga.ledger.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secret base64 HMAC key of at least 32 bytes; when empty a random key is generated at startup (tokens then
 *               stop working on restart, which is fine for development only)
 */
@ConfigurationProperties("ledger.jwt")
record JwtProperties(String secret, @DefaultValue("ledger-service") String issuer,
        @DefaultValue("15m") Duration ttl) {
}
