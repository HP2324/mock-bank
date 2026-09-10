package com.example.mockbank;

import com.example.mockbank.controller.BankController;
import com.example.mockbank.model.Account;
import com.example.mockbank.model.Member;
import com.example.mockbank.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BankControllerTest {
    @Test
    void loginSearchAndOpenSubAccountHappyPath() {
        var controller = new BankController(new FakeBankService());
        var session = new MockHttpSession();

        controller.login("operator", "password", session);

        String searchResult = controller.search("12345", session);
        assertTrue(searchResult.contains("/members/12345"));

        String confirmation = controller.createSubAccount("12345", "Savings Plus", "100.00", session);
        assertTrue(confirmation.contains("CONFIRM-SUBACCOUNT-CREATED"));
        assertTrue(confirmation.contains("Sub-account opened successfully."));
    }

    @Test
    void reportsInvalidAndNotFoundMemberIds() {
        var controller = new BankController(new FakeBankService());
        var session = new MockHttpSession();
        controller.login("operator", "password", session);

        assertTrue(controller.search("abc", session).contains("Invalid member ID"));
        assertTrue(controller.search("00000", session).contains("No member record was found"));
    }

    @Test
    void reportsPermissionDeniedAndHumanApprovalStates() {
        var controller = new BankController(new FakeBankService());
        var session = new MockHttpSession();
        controller.login("operator", "password", session);

        assertTrue(controller.newSubAccount("77777", session).contains("Permission denied"));
        assertTrue(controller.createSubAccount("12345", "Savings Plus", "5000.01", session)
                .contains("Human approval required"));
    }

    private static class FakeBankService implements BankService {
        private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        private final Map<String, Member> members = new LinkedHashMap<>();
        private final List<String> auditEvents = new ArrayList<>();

        FakeBankService() {
            members.put("12345", new Member(
                    "12345",
                    "Jordan Lee",
                    "Active",
                    true,
                    new ArrayList<>(List.of(
                            new Account("SAV-1001", "Primary Savings", new BigDecimal("1842.77")),
                            new Account("CHK-2042", "Everyday Checking", new BigDecimal("620.14"))
                    ))
            ));
            members.put("77777", new Member(
                    "77777",
                    "Riley Morgan",
                    "Restricted",
                    false,
                    new ArrayList<>(List.of(
                            new Account("CHK-7770", "Everyday Checking", new BigDecimal("88.45"))
                    ))
            ));
        }

        @Override
        public Optional<Member> findMember(String memberId) {
            return Optional.ofNullable(members.get(memberId));
        }

        @Override
        public Account openSubAccount(String memberId, String productName, BigDecimal initialDeposit) {
            Member member = members.get(memberId);
            String prefix = productName.toUpperCase().startsWith("SAV") ? "SAV" : "SUB";
            Account account = new Account(prefix + "-" + memberId.substring(memberId.length() - 3) + "-" + (member.accounts().size() + 1), productName, initialDeposit);
            member.accounts().add(account);
            audit("Opened " + productName + " " + account.number() + " for member " + memberId);
            return account;
        }

        @Override
        public List<String> auditEvents() {
            return List.copyOf(auditEvents);
        }

        @Override
        public void audit(String event) {
            auditEvents.add(0, LocalDateTime.now().format(AUDIT_TIME) + " - " + event);
        }
    }
}
