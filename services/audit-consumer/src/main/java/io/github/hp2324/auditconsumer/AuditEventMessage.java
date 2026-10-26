package io.github.hp2324.auditconsumer;

import java.time.Instant;
import java.util.UUID;

/** The JSON payload mock-bank publishes (io.github.hp2324.mockbank.model.AuditEvent). */
public record AuditEventMessage(
        UUID eventId,
        String type,
        String operatorId,
        String memberId,
        String accountNumber,
        String message,
        Instant occurredAt
) {
}
