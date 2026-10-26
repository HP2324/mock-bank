package com.example.mockbank.it;

import com.example.mockbank.model.Account;
import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.reporting.MemberActivity;
import com.example.mockbank.reporting.MemberPortfolio;
import com.example.mockbank.reporting.ReportingRepository;
import com.example.mockbank.reporting.SubAccountOpening;
import com.example.mockbank.service.BankService;
import com.example.mockbank.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the hand-written reporting SQL against real PostgreSQL, inside rolled-back transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mockbank.kafka.enabled=false")
@Transactional
class ReportingRepositoryIT extends PostgresIntegrationTest {
    @Autowired
    private ReportingRepository reports;

    @Autowired
    private BankService bankService;

    @Autowired
    private EntityManager entityManager;

    private static MemberPortfolio portfolio(List<MemberPortfolio> rows, String memberId) {
        return rows.stream().filter(row -> row.memberId().equals(memberId)).findFirst().orElseThrow();
    }

    @Test
    void memberPortfoliosAggregateAccountsPerMember() {
        List<MemberPortfolio> rows = reports.memberPortfolios();

        assertThat(rows).extracting(MemberPortfolio::memberId).contains("12345", "24680", "77777");
        MemberPortfolio maya = portfolio(rows, "24680");
        assertThat(maya.accountCount()).isEqualTo(2);
        assertThat(maya.totalBalance()).isEqualByComparingTo("24420.01");
        assertThat(portfolio(rows, "77777").totalBalance()).isEqualByComparingTo("88.45");
        assertThat(rows.get(0).memberId()).isEqualTo("24680"); // ordered by total balance
    }

    @Test
    void memberPortfoliosIncludeMembersWithoutAccounts() {
        entityManager.createNativeQuery("INSERT INTO members (member_id, name, status) VALUES ('13579', 'Sam Ortiz', 'Active')")
                .executeUpdate();

        MemberPortfolio sam = portfolio(reports.memberPortfolios(), "13579");

        assertThat(sam.accountCount()).isZero();
        assertThat(sam.totalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sam.lastAccountOpenedAt()).isNull();
    }

    @Test
    void subAccountOpeningsJoinTheAccountMemberAndOperator() {
        Account opened = bankService.openSubAccount("operator", "24680", "Savings Plus", new BigDecimal("75.00"));
        entityManager.flush();

        SubAccountOpening latest = reports.recentSubAccountOpenings(5).get(0);

        assertThat(latest.accountNumber()).isEqualTo(opened.number());
        assertThat(latest.memberName()).isEqualTo("Maya Chen");
        assertThat(latest.openedBy()).isEqualTo("operator");
        assertThat(latest.openingBalance()).isEqualByComparingTo("75.00");
    }

    @Test
    void seededAccountsAreNotReportedAsConsoleOpenings() {
        assertThat(reports.recentSubAccountOpenings(200))
                .extracting(SubAccountOpening::accountNumber)
                .doesNotContain("SAV-1001", "CHK-2042", "SAV-9820", "MM-1180", "CHK-7770");
    }

    @Test
    void memberActivityCountsEachKindOfAuditedAction() {
        bankService.openSubAccount("operator", "24680", "Savings Plus", BigDecimal.ONE);
        bankService.audit(AuditEventType.HUMAN_APPROVAL_REQUIRED, "operator", "24680", "approval 1");
        bankService.audit(AuditEventType.HUMAN_APPROVAL_REQUIRED, "operator", "24680", "approval 2");
        entityManager.flush();

        MemberActivity maya = reports.memberActivity().stream()
                .filter(row -> row.memberId().equals("24680")).findFirst().orElseThrow();

        assertThat(maya.subAccountsOpened()).isEqualTo(1);
        assertThat(maya.approvalsRequested()).isEqualTo(2);
        assertThat(maya.permissionDenials()).isZero();
        assertThat(maya.lastActivityAt()).isNotNull();
        assertThat(reports.memberActivity().get(0).memberId()).isEqualTo("24680"); // most recent activity first
    }
}
