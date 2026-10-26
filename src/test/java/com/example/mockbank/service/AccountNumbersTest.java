package com.example.mockbank.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumbersTest {
    @Test
    void savingsProductsGetTheSavPrefix() {
        assertThat(AccountNumbers.next("12345", "Savings Plus", 2)).isEqualTo("SAV-345-3");
    }

    @Test
    void prefixMatchingIsCaseInsensitive() {
        assertThat(AccountNumbers.next("12345", "savings goal", 0)).isEqualTo("SAV-345-1");
    }

    @Test
    void otherProductsGetTheSubPrefix() {
        assertThat(AccountNumbers.next("24680", "Holiday Fund", 2)).isEqualTo("SUB-680-3");
    }
}
