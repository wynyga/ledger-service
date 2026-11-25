package io.github.wynyga.ledger.adapter.in.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /v3/api-docs}, with Swagger UI at {@code /swagger-ui.html}. Click
 * "Authorize" and paste the {@code accessToken} from {@code POST /api/v1/auth/token}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    static final String BEARER = "bearer";

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger service")
                        .version("v1")
                        .description("""
                                Double-entry ledger: accounts, idempotent transfers, holds, reconciliation.

                                1. `POST /api/v1/auth/register`, then `POST /api/v1/auth/token`.
                                2. Click **Authorize** and paste the `accessToken`.
                                3. Requests that move money need an `Idempotency-Key` header (any unique string); \
                                sending the same key again returns the first result instead of moving money twice.

                                Amounts are integers in minor units (1 = Rp1). Errors are RFC 9457 problem details \
                                with a stable `code`."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
