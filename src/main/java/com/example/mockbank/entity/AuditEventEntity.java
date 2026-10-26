package com.example.mockbank.entity;

import com.example.mockbank.model.AuditEventType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID eventId;

    @Enumerated(EnumType.STRING)
    private AuditEventType eventType;

    private String operatorId;
    private String memberId;
    private String accountNumber;
    private String message;
    private Instant createdAt;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(
            UUID eventId,
            AuditEventType eventType,
            String operatorId,
            String memberId,
            String accountNumber,
            String message,
            Instant createdAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.operatorId = operatorId;
        this.memberId = memberId;
        this.accountNumber = accountNumber;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
