package com.example.mockbank.api;

import com.example.mockbank.reporting.MemberActivity;
import com.example.mockbank.reporting.MemberPortfolio;
import com.example.mockbank.reporting.ReportingRepository;
import com.example.mockbank.reporting.SubAccountOpening;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportingRepository reports;

    public ReportController(ReportingRepository reports) {
        this.reports = reports;
    }

    @GetMapping("/member-portfolios")
    public List<MemberPortfolio> memberPortfolios() {
        return reports.memberPortfolios();
    }

    @GetMapping("/subaccount-openings")
    public List<SubAccountOpening> subAccountOpenings(@RequestParam(defaultValue = "20") int limit) {
        return reports.recentSubAccountOpenings(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/member-activity")
    public List<MemberActivity> memberActivity() {
        return reports.memberActivity();
    }
}
