package io.github.hp2324.mockbank.api;

import io.github.hp2324.mockbank.model.Account;
import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.model.Member;
import io.github.hp2324.mockbank.service.BankService;
import io.github.hp2324.mockbank.service.SubAccountPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberController.class)
@Import(SubAccountPolicy.class)
class MemberControllerTest {
    private static final Member JORDAN = new Member("12345", "Jordan Lee", "Active", true, List.of(
            new Account("SAV-1001", "Primary Savings", new BigDecimal("1842.77")),
            new Account("CHK-2042", "Everyday Checking", new BigDecimal("620.14"))));
    private static final Member RILEY = new Member("77777", "Riley Morgan", "Restricted", false, List.of());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BankService bankService;

    private MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");
        return session;
    }

    private ResultActions open(String memberId, String productName, String deposit) throws Exception {
        return mvc.perform(post("/api/members/{id}/subaccounts", memberId).session(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"%s\",\"initialDeposit\":\"%s\"}".formatted(productName, deposit)));
    }

    @Test
    void memberEndpointsRequireASignedInOperator() throws Exception {
        mvc.perform(get("/api/members/12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Please sign in to continue."));
    }

    @Test
    void lookupRejectsIdsThatAreNotFiveDigits() throws Exception {
        mvc.perform(get("/api/members/lookup").param("memberId", "abc").session(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_ID"))
                .andExpect(jsonPath("$.message").value("Invalid member ID. Enter exactly five digits."));
    }

    @Test
    void lookupReportsUnknownMembersAsNotFound() throws Exception {
        when(bankService.findMember("00000")).thenReturn(Optional.empty());

        mvc.perform(get("/api/members/lookup").param("memberId", "00000").session(signedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No member record was found for ID 00000."));
    }

    @Test
    void lookupTrimsTheTypedIdAndReturnsTheMember() throws Exception {
        when(bankService.findMember("12345")).thenReturn(Optional.of(JORDAN));

        mvc.perform(get("/api/members/lookup").param("memberId", " 12345 ").session(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("12345"))
                .andExpect(jsonPath("$.name").value("Jordan Lee"));
    }

    @Test
    void detailIncludesAccountsAndPermissions() throws Exception {
        when(bankService.findMember("12345")).thenReturn(Optional.of(JORDAN));

        mvc.perform(get("/api/members/12345").session(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canOpenSubAccount").value(true))
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].number").value("SAV-1001"))
                .andExpect(jsonPath("$.accounts[0].balance").value(1842.77));
    }

    @Test
    void opensASubAccountAndReturnsTheConfirmationCheckpoint() throws Exception {
        when(bankService.findMember("12345")).thenReturn(Optional.of(JORDAN));
        when(bankService.openSubAccount("operator", "12345", "Savings Plus", new BigDecimal("100.00")))
                .thenReturn(new Account("SAV-345-3", "Savings Plus", new BigDecimal("100.00")));

        open("12345", "Savings Plus", "100.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("SAV-345-3"))
                .andExpect(jsonPath("$.memberName").value("Jordan Lee"))
                .andExpect(jsonPath("$.openingBalance").value(100.00))
                .andExpect(jsonPath("$.checkpoint").value("CONFIRM-SUBACCOUNT-CREATED"));
    }

    @Test
    void largeDepositsPauseForHumanApprovalWithoutOpeningAnAccount() throws Exception {
        when(bankService.findMember("12345")).thenReturn(Optional.of(JORDAN));

        open("12345", "Savings Plus", "5000.01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HUMAN_APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Human approval required. Initial deposits over $5,000 are treated as risky actions."));

        verify(bankService).audit(AuditEventType.HUMAN_APPROVAL_REQUIRED, "operator", "12345",
                "Paused for human approval on member 12345 sub-account deposit 5000.01");
        verify(bankService, never()).openSubAccount(anyString(), anyString(), anyString(), any());
    }

    @Test
    void restrictedMembersAreDeniedAndTheAttemptIsAudited() throws Exception {
        when(bankService.findMember("77777")).thenReturn(Optional.of(RILEY));

        open("77777", "Savings Plus", "100.00")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.message").value(MemberController.PERMISSION_DENIED_MESSAGE));

        verify(bankService).audit(AuditEventType.PERMISSION_DENIED, "operator", "77777",
                "Blocked sub-account opening for restricted member 77777");
        verify(bankService, never()).openSubAccount(anyString(), anyString(), anyString(), any());
    }

    @Test
    void invalidFormInputIsAValidationError() throws Exception {
        when(bankService.findMember("12345")).thenReturn(Optional.of(JORDAN));

        open("12345", "Savings Plus", "-5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Initial deposit cannot be negative."));
    }

    @Test
    void openingForAnUnknownMemberIsNotFound() throws Exception {
        when(bankService.findMember("00000")).thenReturn(Optional.empty());

        open("00000", "Savings Plus", "100.00").andExpect(status().isNotFound());
    }
}
