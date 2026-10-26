package io.github.hp2324.mockbank.audit;

import io.github.hp2324.mockbank.model.AuditEvent;

/** Spring application event raised inside the audit transaction. */
public record AuditEventRecorded(AuditEvent event) {
}
