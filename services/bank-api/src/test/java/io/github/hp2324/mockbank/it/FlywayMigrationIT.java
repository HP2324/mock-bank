package io.github.hp2324.mockbank.it;

import io.github.hp2324.mockbank.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mockbank.kafka.enabled=false")
class FlywayMigrationIT extends PostgresIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesEveryMigrationInOrder() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2", "3");
    }

    @Test
    void seedsTheDemoMembersTheAutomationArtifactsUse() {
        assertThat(jdbc.queryForList("SELECT member_id FROM members ORDER BY member_id", String.class))
                .containsExactly("12345", "24680", "77777");
        assertThat(jdbc.queryForObject("SELECT can_open_sub_account FROM members WHERE member_id = '77777'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = 'SAV-1001'", BigDecimal.class))
                .isEqualByComparingTo("1842.77");
    }

    @Test
    void rejectsMemberIdsThatAreNotFiveDigits() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO members (member_id, name, status) VALUES ('12a45', 'Bad Id', 'Active')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("members_member_id_format");
    }

    @Test
    void rejectsDuplicateAccountNumbers() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (member_id, account_number, account_type, balance)
                VALUES ('24680', 'SAV-1001', 'Duplicate', 1.00)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_account_number_key");
    }

    @Test
    void rejectsNegativeBalances() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (member_id, account_number, account_type, balance)
                VALUES ('24680', 'NEG-680-1', 'Overdrawn', -0.01)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_balance_non_negative");
    }

    @Test
    void auditEventsMustReferenceARealMember() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_events (event_id, event_type, member_id, message)
                VALUES (gen_random_uuid(), 'SUBACCOUNT_OPENED', '99999', 'ghost member')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
