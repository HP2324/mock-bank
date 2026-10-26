package com.example.auditconsumer;

import java.time.Instant;
import java.util.UUID;

/** The JSON payload mock-bank publishes (com.example.mockbank.model.AuditEvent). */
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
