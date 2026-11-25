package io.github.wynyga.ledger.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.wynyga.ledger.adapter.out.security.JwtTokenIssuer;
import io.github.wynyga.ledger.application.TokenIssuer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Keys, token encoder/decoder and password encoder. One key signs and verifies, so it lives here, in one place. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class SecurityBeans {
    private static final Logger log = LoggerFactory.getLogger(SecurityBeans.class);
    private static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey jwtKey(JwtProperties props) {
        byte[] key;
        if (props.secret() == null || props.secret().isBlank()) {
            key = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(key);
            log.warn("ledger.jwt.secret (JWT_SECRET) is not set: using a random key; tokens stop working on restart");
        } else {
            key = Base64.getDecoder().decode(props.secret().trim());
            if (key.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("ledger.jwt.secret must decode to at least 32 bytes for HS256");
            }
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** Checks signature, algorithm, expiry (with the default 60 s clock skew) and issuer. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey key, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    @Bean
    TokenIssuer tokenIssuer(JwtEncoder encoder, JwtProperties props, Clock clock) {
        return new JwtTokenIssuer(encoder, props.issuer(), props.ttl(), clock);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
