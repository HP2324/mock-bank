package io.github.hp2324.mockbank.api;

import io.github.hp2324.mockbank.model.AuditEvent;
import io.github.hp2324.mockbank.service.BankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Audit log", description = "Audited operator actions, newest first")
@RestController
public class AuditController {
    private static final int MAX_LIMIT = 500;

    private final BankService bankService;

    public AuditController(BankService bankService) {
        this.bankService = bankService;
    }

    @Operation(summary = "Newest audit events", description = "limit is clamped to 1–500. The same events are published to Kafka.")
    @GetMapping("/api/audit-events")
    public List<AuditEvent> recent(@RequestParam(defaultValue = "100") int limit) {
        return bankService.recentAuditEvents(Math.max(1, Math.min(limit, MAX_LIMIT)));
    }
}
