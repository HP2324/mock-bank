package io.github.hp2324.auditconsumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Kafka + PostgreSQL: produce what mock-bank produces and check what the consumer stores. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AuditConsumerIT {
    private static final String TOPIC = "mockbank.audit-events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsumedEventStore store;

    private static String event(UUID id, String type, String memberId) {
        return """
                {"eventId":"%s","type":"%s","operatorId":"operator","memberId":"%s","accountNumber":null,
                 "message":"test event","occurredAt":"%s"}
                """.formatted(id, type, memberId, Instant.now());
    }

    private static void send(String topic, String key, String value) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    private static <T> T eventually(Callable<T> probe, java.util.function.Predicate<T> done) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        T value = probe.call();
        while (!done.test(value) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            value = probe.call();
        }
        return value;
    }

    private int rowsFor(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM consumed_audit_events WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    void storesEachAuditEventOnceEvenWhenKafkaRedeliversIt() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = event(id, "SUBACCOUNT_OPENED", "12345");

        send(TOPIC, "12345", payload);
        send(TOPIC, "12345", payload);
        UUID marker = UUID.randomUUID();
        send(TOPIC, "12345", event(marker, "OPERATOR_SIGNED_OUT", "12345"));

        // Same partition (same key), so once the marker is stored both copies have been processed.
        assertThat(eventually(() -> rowsFor(marker), count -> count == 1)).isEqualTo(1);
        assertThat(rowsFor(id)).isEqualTo(1);
        assertThat(store.recent(10)).extracting(ConsumedEventStore.StoredEvent::eventId).contains(id, marker);
    }

    @Test
    void summaryGroupsStoredEventsByType() throws Exception {
        send(TOPIC, "24680", event(UUID.randomUUID(), "HUMAN_APPROVAL_REQUIRED", "24680"));
        send(TOPIC, "24680", event(UUID.randomUUID(), "HUMAN_APPROVAL_REQUIRED", "24680"));

        List<ConsumedEventStore.TypeCount> summary = eventually(store::countsByType, rows -> rows.stream()
                .anyMatch(row -> row.type().equals("HUMAN_APPROVAL_REQUIRED") && row.count() >= 2));

        assertThat(summary).anySatisfy(row -> {
            assertThat(row.type()).isEqualTo("HUMAN_APPROVAL_REQUIRED");
            assertThat(row.count()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void malformedMessagesGoToTheDeadLetterTopicWithoutBlockingThePartition() throws Exception {
        send(TOPIC, "77777", "this is not json");
        UUID after = UUID.randomUUID();
        send(TOPIC, "77777", event(after, "PERMISSION_DENIED", "77777"));

        assertThat(eventually(() -> rowsFor(after), count -> count == 1)).isEqualTo(1);

        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> deadLetters = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            Instant deadline = Instant.now().plusSeconds(15);
            while (deadLetters.isEmpty() && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> deadLetters.add(record.value()));
            }
        }
        assertThat(deadLetters).contains("this is not json");
    }
}
