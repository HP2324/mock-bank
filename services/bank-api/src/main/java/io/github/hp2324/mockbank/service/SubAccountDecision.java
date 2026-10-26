package io.github.hp2324.mockbank.service;

import java.math.BigDecimal;

public record SubAccountDecision(Verdict verdict, String productName, BigDecimal deposit, String message) {
    public enum Verdict {
        APPROVED,
        REJECTED,
        HUMAN_APPROVAL_REQUIRED
    }

    static SubAccountDecision approved(String productName, BigDecimal deposit) {
        return new SubAccountDecision(Verdict.APPROVED, productName, deposit, null);
    }

    static SubAccountDecision rejected(String message) {
        return new SubAccountDecision(Verdict.REJECTED, null, null, message);
    }

    static SubAccountDecision humanApprovalRequired(String productName, BigDecimal deposit) {
        return new SubAccountDecision(Verdict.HUMAN_APPROVAL_REQUIRED, productName, deposit,
                "Human approval required. Initial deposits over $5,000 are treated as risky actions.");
    }
}
