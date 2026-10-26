package com.example.auditconsumer;

/** A message that can never be processed; it goes straight to the dead-letter topic. */
public class InvalidAuditEventException extends RuntimeException {
    public InvalidAuditEventException(String message) {
        super(message);
    }

    public InvalidAuditEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
