package com.example.mockbank.reporting;

import java.math.BigDecimal;
import java.time.Instant;

public record SubAccountOpening(
        String accountNumber,
        String product,
        BigDecimal openingBalance,
        Instant openedAt,
        String memberId,
        String memberName,
        String openedBy
) {
}
