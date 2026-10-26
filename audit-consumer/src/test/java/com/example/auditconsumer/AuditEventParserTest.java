package com.example.auditconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventParserTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuditEventParser parser = new AuditEventParser(mapper);

    static final String VALID = """
            {"eventId":"7b0c5a9e-0f7e-4c44-9d0f-3c5f2f0c9a11","type":"SUBACCOUNT_OPENED","operatorId":"operator",
             "memberId":"12345","accountNumber":"SAV-345-3","message":"Opened Savings Plus SAV-345-3 for member 12345",
             "occurredAt":"2026-10-26T14:30:15.123456Z"}
            """;

    @Test
    void parsesTheEventMockBankPublishes() {
        AuditEventMessage event = parser.parse(VALID);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("7b0c5a9e-0f7e-4c44-9d0f-3c5f2f0c9a11"));
        assertThat(event.type()).isEqualTo("SUBACCOUNT_OPENED");
        assertThat(event.accountNumber()).isEqualTo("SAV-345-3");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-26T14:30:15.123456Z"));
    }

    @Test
    void ignoresFieldsAddedByNewerProducers() {
        AuditEventMessage event = parser.parse(VALID.replace("{\"eventId\"", "{\"schemaVersion\":2,\"eventId\""));

        assertThat(event.type()).isEqualTo("SUBACCOUNT_OPENED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not json", "[1,2,3]", "{\"eventId\":\"not-a-uuid\"}"})
    void rejectsPayloadsThatAreNotAnAuditEvent(String payload) {
        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(InvalidAuditEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "type", "occurredAt", "message"})
    void rejectsEventsMissingARequiredField(String field) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(VALID);
        json.remove(field);

        assertThatThrownBy(() -> parser.parse(json.toString()))
                .isInstanceOf(InvalidAuditEventException.class)
                .hasMessageContaining(field);
    }
}
