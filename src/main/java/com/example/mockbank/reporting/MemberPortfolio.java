package com.example.mockbank.reporting;

import java.math.BigDecimal;
import java.time.Instant;

public record MemberPortfolio(
        String memberId,
        String name,
        String status,
        int accountCount,
        BigDecimal totalBalance,
        Instant lastAccountOpenedAt
) {
}
