package com.example.mockbank.api;

import com.example.mockbank.model.AuditEvent;
import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.reporting.MemberPortfolio;
import com.example.mockbank.reporting.ReportingRepository;
import com.example.mockbank.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuditController.class, ReportController.class})
class AuditAndReportControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BankService bankService;

    @MockitoBean
    private ReportingRepository reports;

    private MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");
        return session;
    }

    @Test
    void auditEventsAreReturnedAsJsonWithIsoTimestamps() throws Exception {
        when(bankService.recentAuditEvents(100)).thenReturn(List.of(new AuditEvent(UUID.randomUUID(),
                AuditEventType.OPERATOR_SIGNED_IN, "operator", null, null, "Operator signed in",
                Instant.parse("2026-10-26T14:30:15Z"))));

        mvc.perform(get("/api/audit-events").session(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("OPERATOR_SIGNED_IN"))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-10-26T14:30:15Z"));
    }

    @Test
    void auditEventLimitIsClamped() throws Exception {
        mvc.perform(get("/api/audit-events").param("limit", "100000").session(signedIn())).andExpect(status().isOk());

        verify(bankService).recentAuditEvents(500);
    }

    @Test
    void memberPortfolioReportIsServed() throws Exception {
        when(reports.memberPortfolios()).thenReturn(List.of(
                new MemberPortfolio("24680", "Maya Chen", "Active", 2, new BigDecimal("24420.01"), null)));

        mvc.perform(get("/api/reports/member-portfolios").session(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberId").value("24680"))
                .andExpect(jsonPath("$[0].accountCount").value(2))
                .andExpect(jsonPath("$[0].totalBalance").value(24420.01));
    }

    @Test
    void reportsRequireASignedInOperator() throws Exception {
        mvc.perform(get("/api/reports/member-activity")).andExpect(status().isUnauthorized());
    }
}
