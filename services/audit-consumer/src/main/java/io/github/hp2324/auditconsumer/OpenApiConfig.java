package io.github.hp2324.auditconsumer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description served at /v3/api-docs and rendered by Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI auditConsumerApiDocs() {
        return new OpenAPI().info(new Info()
                .title("Audit Consumer API")
                .version("v1")
                .description("Read-only view of the audit events this service consumed from the "
                        + "`mockbank.audit-events` Kafka topic."));
    }
}
