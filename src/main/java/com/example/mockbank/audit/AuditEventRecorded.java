package com.example.mockbank.audit;

import com.example.mockbank.model.AuditEvent;

/** Spring application event raised inside the audit transaction. */
public record AuditEventRecorded(AuditEvent event) {
}
