package io.github.wynyga.ledger.adapter.in.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /v3/api-docs}, with Swagger UI at {@code /swagger-ui.html}. Click
 * "Authorize" and paste the {@code accessToken} from {@code POST /api/v1/auth/token}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    static final String BEARER = "bearer";
    static final String TOKEN_PATH = "/api/v1/auth/token";

    /**
     * Development convenience: pre-fills the body of {@code POST /api/v1/auth/token} in Swagger UI with the admin
     * credentials from ADMIN_USERNAME / ADMIN_PASSWORD. Off unless SWAGGER_PREFILL_ADMIN_LOGIN=true, because the
     * example is public to anyone who can open the docs. The values come from the environment (.env), never from
     * the code.
     */
    @Bean
    OpenApiCustomizer adminLoginExample(@Value("${ledger.swagger.prefill-admin-login:false}") boolean enabled,
            @Value("${ledger.admin.username:admin}") String username,
            @Value("${ledger.admin.password:}") String password) {
        return api -> {
            if (api.getPaths() == null || api.getPaths().get(TOKEN_PATH) == null) {
                return;
            }
            var op = api.getPaths().get(TOKEN_PATH).getPost();
            if (op == null || op.getRequestBody() == null || op.getRequestBody().getContent() == null) {
                return;
            }
            // Swagger UI shows these as a drop-down; the first one fills the body.
            Map<String, Example> examples = new LinkedHashMap<>();
            if (enabled && !password.isBlank()) {
                examples.put("admin", credentials("Admin from .env (records deposits)", username, password));
            }
            examples.put("alice", credentials("The user registered with the example of /auth/register", "alice",
                    "alice-password"));
            op.getRequestBody().getContent().values().forEach(media -> {
                media.setExample(null);
                media.setExamples(examples);
            });
        };
    }

    private static Example credentials(String summary, String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return new Example().summary(summary).value(body);
    }

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
