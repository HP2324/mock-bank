package io.github.hp2324.mockbank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description served at /v3/api-docs and rendered by Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {
    public static final String SESSION_SCHEME = "staffSession";

    @Bean
    OpenAPI bankApiDocs() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mock Bank Staff API")
                        .version("v1")
                        .description("""
                                JSON API behind the staff console. Sign in with `POST /api/session` \
                                (demo: `operator` / `password`); the browser then sends the session \
                                cookie with every call, including from this page. Errors are always \
                                `{"code", "message"}`."""))
                .components(new Components().addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")
                        .description("Servlet session cookie set by POST /api/session.")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME));
    }
}
