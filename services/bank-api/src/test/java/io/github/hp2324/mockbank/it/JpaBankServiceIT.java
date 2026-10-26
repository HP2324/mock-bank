package io.github.hp2324.mockbank.it;

import io.github.hp2324.mockbank.model.Account;
import io.github.hp2324.mockbank.model.AuditEvent;
import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.service.BankService;
import io.github.hp2324.mockbank.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each test runs in a transaction that is rolled back, so the seed data stays untouched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mockbank.kafka.enabled=false")
@Transactional
class JpaBankServiceIT extends PostgresIntegrationTest {
    @Autowired
    private BankService bankService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loadsSeededMembersThroughJpa() {
        var jordan = bankService.findMember("12345").orElseThrow();

        assertThat(jordan.name()).isEqualTo("Jordan Lee");
        assertThat(jordan.accounts()).extracting(Account::number).startsWith("SAV-1001", "CHK-2042");
        assertThat(bankService.findMember("00000")).isEmpty();
    }

    @Test
    void openSubAccountPersistsTheAccountAndItsAuditRow() {
        int before = bankService.findMember("24680").orElseThrow().accounts().size();

        Account account = bankService.openSubAccount("operator", "24680", "Holiday Fund", new BigDecimal("250.00"));
        entityManager.flush();
        entityManager.clear();

        assertThat(account.number()).isEqualTo("SUB-680-" + (before + 1));
        assertThat(bankService.findMember("24680").orElseThrow().accounts()).contains(account);
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM audit_events WHERE account_number = ?", String.class, account.number()))
                .isEqualTo("SUBACCOUNT_OPENED");
    }

    @Test
    void consecutiveOpeningsGetConsecutiveAccountNumbers() {
        int before = bankService.findMember("24680").orElseThrow().accounts().size();

        Account first = bankService.openSubAccount("operator", "24680", "Savings Plus", BigDecimal.TEN);
        Account second = bankService.openSubAccount("operator", "24680", "Savings Plus", BigDecimal.TEN);

        assertThat(first.number()).isEqualTo("SAV-680-" + (before + 1));
        assertThat(second.number()).isEqualTo("SAV-680-" + (before + 2));
    }

    @Test
    void restrictedMembersCannotOpenAccountsAtTheServiceLayer() {
        assertThatThrownBy(() -> bankService.openSubAccount("operator", "77777", "Savings Plus", BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recentAuditEventsAreNewestFirstAndRespectTheLimit() {
        bankService.audit(AuditEventType.OPERATOR_SIGNED_IN, "operator", null, "first");
        bankService.audit(AuditEventType.PERMISSION_DENIED, "operator", "77777", "second");
        AuditEvent third = bankService.audit(AuditEventType.OPERATOR_SIGNED_OUT, "operator", null, "third");

        List<AuditEvent> recent = bankService.recentAuditEvents(2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).isEqualTo(third);
        assertThat(recent.get(1).message()).isEqualTo("second");
    }
}
