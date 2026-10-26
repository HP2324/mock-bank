package io.github.hp2324.mockbank.audit;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "mockbank.kafka.enabled", havingValue = "true")
public class KafkaAuditConfig {
    @Bean
    NewTopic auditEventsTopic(@Value("${mockbank.kafka.audit-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
