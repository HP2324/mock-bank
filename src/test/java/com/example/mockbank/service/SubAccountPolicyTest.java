package com.example.mockbank.service;

import com.example.mockbank.service.SubAccountDecision.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SubAccountPolicyTest {
    private final SubAccountPolicy policy = new SubAccountPolicy();

    @Test
    void approvesAnOrdinaryOpening() {
        SubAccountDecision decision = policy.evaluate("Savings Plus", "100.00");

        assertThat(decision.verdict()).isEqualTo(Verdict.APPROVED);
        assertThat(decision.productName()).isEqualTo("Savings Plus");
        assertThat(decision.deposit()).isEqualByComparingTo("100.00");
        assertThat(decision.message()).isNull();
    }

    @Test
    void approvesADepositExactlyAtTheThreshold() {
        assertThat(policy.evaluate("Savings Plus", "5000.00").verdict()).isEqualTo(Verdict.APPROVED);
    }

    @Test
    void approvesAZeroDeposit() {
        assertThat(policy.evaluate("Savings Plus", "0").verdict()).isEqualTo(Verdict.APPROVED);
    }

    @Test
    void requiresHumanApprovalAboveTheThreshold() {
        SubAccountDecision decision = policy.evaluate("Savings Plus", "5000.01");

        assertThat(decision.verdict()).isEqualTo(Verdict.HUMAN_APPROVAL_REQUIRED);
        assertThat(decision.deposit()).isEqualByComparingTo("5000.01");
        assertThat(decision.message()).startsWith("Human approval required.");
    }

    @Test
    void trimsWhitespaceAroundInputs() {
        SubAccountDecision decision = policy.evaluate("  Holiday Fund ", " 25.50 ");

        assertThat(decision.productName()).isEqualTo("Holiday Fund");
        assertThat(decision.deposit()).isEqualTo(new BigDecimal("25.50"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "1.234", "1e3", "$100", "1,000.00", "12.", ".50", "9999999999999"})
    void rejectsAmountsThatAreNotPlainDollarValues(String deposit) {
        SubAccountDecision decision = policy.evaluate("Savings Plus", deposit);

        assertThat(decision.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(decision.message()).isEqualTo("Initial deposit must be a valid dollar amount.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void requiresAProductName(String productName) {
        SubAccountDecision decision = policy.evaluate(productName, "100.00");

        assertThat(decision.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(decision.message()).isEqualTo("Product name is required.");
    }

    @Test
    void rejectsProductNamesLongerThanTheColumn() {
        SubAccountDecision decision = policy.evaluate("x".repeat(SubAccountPolicy.MAX_PRODUCT_NAME_LENGTH + 1), "100.00");

        assertThat(decision.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(decision.message()).isEqualTo("Product name must be 80 characters or fewer.");
    }

    @Test
    void rejectsNegativeDeposits() {
        SubAccountDecision decision = policy.evaluate("Savings Plus", "-0.01");

        assertThat(decision.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(decision.message()).isEqualTo("Initial deposit cannot be negative.");
    }

    @Test
    void checksTheDepositBeforeTheProductNameLikeTheLegacyForm() {
        assertThat(policy.evaluate("", "abc").message()).isEqualTo("Initial deposit must be a valid dollar amount.");
    }
}
