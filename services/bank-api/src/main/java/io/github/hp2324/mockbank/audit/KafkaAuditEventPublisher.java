package io.github.hp2324.mockbank.audit;

import io.github.hp2324.mockbank.model.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes every committed audit-log row to Kafka as JSON, keyed by member ID so one member's
 * events stay ordered on a single partition. The database row is the source of truth: a broker
 * outage is logged and never fails or rolls back the operator's request.
 */
@Component
@ConditionalOnProperty(name = "mockbank.kafka.enabled", havingValue = "true")
public class KafkaAuditEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaAuditEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${mockbank.kafka.audit-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEventRecorded(AuditEventRecorded recorded) {
        AuditEvent event = recorded.event();
        try {
            kafkaTemplate.send(topic, partitionKey(event), objectMapper.writeValueAsString(event))
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.warn("Kafka rejected audit event {} ({})", event.eventId(), failure.getMessage());
                        }
                    });
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("Could not publish audit event {} to Kafka ({})", event.eventId(), ex.getMessage());
        }
    }

    static String partitionKey(AuditEvent event) {
        if (event.memberId() != null) {
            return event.memberId();
        }
        return event.operatorId();
    }
}
