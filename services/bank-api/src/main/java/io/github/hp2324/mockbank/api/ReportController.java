package io.github.hp2324.mockbank.api;

import io.github.hp2324.mockbank.reporting.MemberActivity;
import io.github.hp2324.mockbank.reporting.MemberPortfolio;
import io.github.hp2324.mockbank.reporting.ReportingRepository;
import io.github.hp2324.mockbank.reporting.SubAccountOpening;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Reports", description = "Read-only reports written as SQL joins")
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportingRepository reports;

    public ReportController(ReportingRepository reports) {
        this.reports = reports;
    }

    @Operation(summary = "Account count and total balance per member", description = "members LEFT JOIN accounts")
    @GetMapping("/member-portfolios")
    public List<MemberPortfolio> memberPortfolios() {
        return reports.memberPortfolios();
    }

    @Operation(summary = "Recent console openings with member and operator", description = "accounts JOIN members JOIN audit_events; limit is clamped to 1–200.")
    @GetMapping("/subaccount-openings")
    public List<SubAccountOpening> subAccountOpenings(@RequestParam(defaultValue = "20") int limit) {
        return reports.recentSubAccountOpenings(Math.max(1, Math.min(limit, 200)));
    }

    @Operation(summary = "Audited actions per member", description = "members LEFT JOIN audit_events with COUNT(*) FILTER")
    @GetMapping("/member-activity")
    public List<MemberActivity> memberActivity() {
        return reports.memberActivity();
    }
}
