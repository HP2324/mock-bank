package io.github.hp2324.auditconsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AuditEventParser {
    private final ObjectMapper objectMapper;

    public AuditEventParser(ObjectMapper objectMapper) {
        // Newer producers may add fields; that must not break this consumer.
        this.objectMapper = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public AuditEventMessage parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidAuditEventException("Audit event payload is empty.");
        }
        AuditEventMessage event;
        try {
            event = objectMapper.readValue(json, AuditEventMessage.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidAuditEventException("Audit event payload is not valid JSON: " + ex.getOriginalMessage(), ex);
        }
        if (event.eventId() == null) {
            throw new InvalidAuditEventException("Audit event is missing eventId.");
        }
        if (event.type() == null || event.type().isBlank()) {
            throw new InvalidAuditEventException("Audit event " + event.eventId() + " is missing type.");
        }
        if (event.occurredAt() == null) {
            throw new InvalidAuditEventException("Audit event " + event.eventId() + " is missing occurredAt.");
        }
        if (event.message() == null) {
            throw new InvalidAuditEventException("Audit event " + event.eventId() + " is missing message.");
        }
        return event;
    }
}
