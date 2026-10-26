package io.github.hp2324.mockbank.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Business rules for opening a sub-account, kept free of HTTP and persistence so they can be
 * unit tested directly. Checks run in the same order the legacy server-rendered form used.
 */
@Component
public class SubAccountPolicy {
    public static final BigDecimal HUMAN_APPROVAL_THRESHOLD = new BigDecimal("5000");
    public static final int MAX_PRODUCT_NAME_LENGTH = 80;

    private static final Pattern DOLLAR_AMOUNT = Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");

    public SubAccountDecision evaluate(String productName, String initialDeposit) {
        String product = productName == null ? "" : productName.trim();
        String depositText = initialDeposit == null ? "" : initialDeposit.trim();

        if (!DOLLAR_AMOUNT.matcher(depositText).matches()) {
            return SubAccountDecision.rejected("Initial deposit must be a valid dollar amount.");
        }
        if (product.isBlank()) {
            return SubAccountDecision.rejected("Product name is required.");
        }
        if (product.length() > MAX_PRODUCT_NAME_LENGTH) {
            return SubAccountDecision.rejected("Product name must be " + MAX_PRODUCT_NAME_LENGTH + " characters or fewer.");
        }
        BigDecimal deposit = new BigDecimal(depositText);
        if (deposit.signum() < 0) {
            return SubAccountDecision.rejected("Initial deposit cannot be negative.");
        }
        if (deposit.compareTo(HUMAN_APPROVAL_THRESHOLD) > 0) {
            return SubAccountDecision.humanApprovalRequired(product, deposit);
        }
        return SubAccountDecision.approved(product, deposit);
    }
}
