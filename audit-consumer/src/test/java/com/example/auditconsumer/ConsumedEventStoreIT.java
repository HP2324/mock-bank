package com.example.auditconsumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway schema + the store's SQL against real PostgreSQL; each test is rolled back. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConsumedEventStore.class)
@Testcontainers
class ConsumedEventStoreIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ConsumedEventStore store;

    private static AuditEventMessage event(String type, Instant at) {
        return new AuditEventMessage(UUID.randomUUID(), type, "operator", "12345", null, "msg", at);
    }

    @Test
    void saveIsIdempotentOnEventId() {
        AuditEventMessage event = event("SUBACCOUNT_OPENED", Instant.parse("2026-10-26T10:00:00Z"));

        assertThat(store.save(event, 0, 10)).isTrue();
        assertThat(store.save(event, 0, 10)).isFalse();
        assertThat(store.recent(10)).hasSize(1);
    }

    @Test
    void recentReturnsNewestFirstWithKafkaCoordinates() {
        store.save(event("OPERATOR_SIGNED_IN", Instant.parse("2026-10-26T10:00:00Z")), 1, 5);
        store.save(event("OPERATOR_SIGNED_OUT", Instant.parse("2026-10-26T11:00:00Z")), 2, 7);

        var recent = store.recent(10);

        assertThat(recent).extracting(ConsumedEventStore.StoredEvent::type)
                .containsExactly("OPERATOR_SIGNED_OUT", "OPERATOR_SIGNED_IN");
        assertThat(recent.get(0).partition()).isEqualTo(2);
        assertThat(recent.get(0).offset()).isEqualTo(7);
    }

    @Test
    void countsByTypeOrdersTheBusiestTypeFirst() {
        store.save(event("PERMISSION_DENIED", Instant.parse("2026-10-26T09:00:00Z")), 0, 1);
        store.save(event("SUBACCOUNT_OPENED", Instant.parse("2026-10-26T10:00:00Z")), 0, 2);
        store.save(event("SUBACCOUNT_OPENED", Instant.parse("2026-10-26T12:00:00Z")), 0, 3);

        var counts = store.countsByType();

        assertThat(counts.get(0).type()).isEqualTo("SUBACCOUNT_OPENED");
        assertThat(counts.get(0).count()).isEqualTo(2);
        assertThat(counts.get(0).lastOccurredAt()).isEqualTo(Instant.parse("2026-10-26T12:00:00Z"));
    }
}
