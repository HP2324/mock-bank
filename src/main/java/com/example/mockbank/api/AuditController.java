package com.example.mockbank.api;

import com.example.mockbank.model.AuditEvent;
import com.example.mockbank.service.BankService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AuditController {
    private static final int MAX_LIMIT = 500;

    private final BankService bankService;

    public AuditController(BankService bankService) {
        this.bankService = bankService;
    }

    @GetMapping("/api/audit-events")
    public List<AuditEvent> recent(@RequestParam(defaultValue = "100") int limit) {
        return bankService.recentAuditEvents(Math.max(1, Math.min(limit, MAX_LIMIT)));
    }
}
