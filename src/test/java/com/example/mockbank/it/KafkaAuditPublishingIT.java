package com.example.mockbank.it;

import com.example.mockbank.model.AuditEvent;
import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.service.BankService;
import com.example.mockbank.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mockbank.kafka.enabled=true")
class KafkaAuditPublishingIT extends PostgresIntegrationTest {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private BankService bankService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mockbank.kafka.audit-topic}")
    private String topic;

    private List<ConsumerRecord<String, String>> readTopic(Duration duration) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(duration);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }

    private ConsumerRecord<String, String> find(List<ConsumerRecord<String, String>> records, UUID eventId) {
        return records.stream().filter(r -> r.value().contains(eventId.toString())).findFirst().orElse(null);
    }

    @Test
    void committedAuditEventsArePublishedAsJsonKeyedByMember() throws Exception {
        AuditEvent event = bankService.audit(AuditEventType.PERMISSION_DENIED, "operator", "77777",
                "Blocked sub-account opening for restricted member 77777");

        ConsumerRecord<String, String> record = find(readTopic(Duration.ofSeconds(8)), event.eventId());

        assertThat(record).as("audit event on topic").isNotNull();
        assertThat(record.key()).isEqualTo("77777");
        JsonNode json = objectMapper.readTree(record.value());
        assertThat(json.get("type").asText()).isEqualTo("PERMISSION_DENIED");
        assertThat(json.get("operatorId").asText()).isEqualTo("operator");
        assertThat(Instant.parse(json.get("occurredAt").asText())).isEqualTo(event.occurredAt());
    }

    @Test
    void rolledBackAuditEventsAreNeverPublished() {
        AtomicReference<AuditEvent> rolledBack = new AtomicReference<>();
        transactions.executeWithoutResult(status -> {
            rolledBack.set(bankService.audit(AuditEventType.OPERATOR_SIGNED_IN, "operator", null, "never committed"));
            status.setRollbackOnly();
        });
        AuditEvent committed = bankService.audit(AuditEventType.OPERATOR_SIGNED_IN, "operator", null, "committed");

        List<ConsumerRecord<String, String>> records = readTopic(Duration.ofSeconds(8));

        assertThat(find(records, committed.eventId())).isNotNull();
        assertThat(find(records, rolledBack.get().eventId())).isNull();
    }
}
