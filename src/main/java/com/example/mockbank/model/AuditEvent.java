package com.example.mockbank.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit-log entry. This is also the JSON payload published to Kafka, so field names are part
 * of the contract with the audit-consumer service.
 */
public record AuditEvent(
        UUID eventId,
        AuditEventType type,
        String operatorId,
        String memberId,
        String accountNumber,
        String message,
        Instant occurredAt
) {
}
