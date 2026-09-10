package com.example.mockbank;

import com.example.mockbank.model.Account;
import com.example.mockbank.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaBankServiceTest {
    @Test
    void bootsWithH2SeedDataAndOpensSubAccount() {
        try (ConfigurableApplicationContext context = SpringApplication.run(
                MockBankApplication.class,
                "--spring.main.web-application-type=none"
        )) {
            BankService bankService = context.getBean(BankService.class);

            var member = bankService.findMember("12345").orElseThrow();
            assertEquals("Jordan Lee", member.name());
            assertEquals(2, member.accounts().size());

            Account newAccount = bankService.openSubAccount("12345", "Savings Plus", new BigDecimal("100.00"));
            assertTrue(newAccount.number().startsWith("SAV-345-"));

            var updated = bankService.findMember("12345").orElseThrow();
            assertEquals(3, updated.accounts().size());
        }
    }
}
