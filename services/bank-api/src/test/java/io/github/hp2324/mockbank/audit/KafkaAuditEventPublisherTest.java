package io.github.hp2324.mockbank.audit;

import io.github.hp2324.mockbank.model.AuditEvent;
import io.github.hp2324.mockbank.model.AuditEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaAuditEventPublisherTest {
    private static final String TOPIC = "mockbank.audit-events";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(kafka, mapper, TOPIC);

    private static AuditEvent event(String operatorId, String memberId) {
        return new AuditEvent(UUID.fromString("7b0c5a9e-0f7e-4c44-9d0f-3c5f2f0c9a11"), AuditEventType.SUBACCOUNT_OPENED,
                operatorId, memberId, "SAV-345-3", "Opened Savings Plus SAV-345-3 for member 12345",
                Instant.parse("2026-10-26T14:30:15Z"));
    }

    @Test
    void publishesTheEventAsJsonKeyedByMember() throws Exception {
        when(kafka.send(anyString(), any(), anyString())).thenReturn(new CompletableFuture<>());

        publisher.onAuditEventRecorded(new AuditEventRecorded(event("operator", "12345")));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(TOPIC), eq("12345"), payload.capture());
        JsonNode json = mapper.readTree(payload.getValue());
        assertThat(json.get("eventId").asText()).isEqualTo("7b0c5a9e-0f7e-4c44-9d0f-3c5f2f0c9a11");
        assertThat(json.get("type").asText()).isEqualTo("SUBACCOUNT_OPENED");
        assertThat(json.get("accountNumber").asText()).isEqualTo("SAV-345-3");
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-26T14:30:15Z");
    }

    @Test
    void keysOperatorEventsByOperatorWhenThereIsNoMember() {
        assertThat(KafkaAuditEventPublisher.partitionKey(event("operator", null))).isEqualTo("operator");
        assertThat(KafkaAuditEventPublisher.partitionKey(event(null, null))).isNull();
    }

    @Test
    void aBrokerOutageNeverFailsTheOperatorsRequest() {
        when(kafka.send(anyString(), any(), anyString())).thenThrow(new TimeoutException("metadata not available"));

        assertThatCode(() -> publisher.onAuditEventRecorded(new AuditEventRecorded(event("operator", "12345"))))
                .doesNotThrowAnyException();
    }

    @Test
    void anAsynchronousSendFailureIsOnlyLogged() {
        when(kafka.send(anyString(), any(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("delivery timed out")));

        assertThatCode(() -> publisher.onAuditEventRecorded(new AuditEventRecorded(event("operator", "12345"))))
                .doesNotThrowAnyException();
    }
}
