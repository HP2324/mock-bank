package com.example.mockbank.api;

import com.example.mockbank.model.Account;
import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.model.Member;
import com.example.mockbank.service.BankService;
import com.example.mockbank.service.SubAccountDecision;
import com.example.mockbank.service.SubAccountPolicy;
import com.example.mockbank.web.OperatorSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    public static final String CONFIRMATION_CHECKPOINT = "CONFIRM-SUBACCOUNT-CREATED";
    static final String PERMISSION_DENIED_MESSAGE =
            "Permission denied. This member is restricted and requires a supervisor override.";

    private static final Pattern MEMBER_ID = Pattern.compile("\\d{5}");

    private final BankService bankService;
    private final SubAccountPolicy subAccountPolicy;

    public MemberController(BankService bankService, SubAccountPolicy subAccountPolicy) {
        this.bankService = bankService;
        this.subAccountPolicy = subAccountPolicy;
    }

    public record OpenSubAccountRequest(String productName, String initialDeposit) {
    }

    public record SubAccountConfirmation(
            String memberId,
            String memberName,
            String accountNumber,
            String product,
            BigDecimal openingBalance,
            String checkpoint
    ) {
    }

    /** Search-form lookup: validates the raw input the operator typed. */
    @GetMapping("/lookup")
    public Member lookup(@RequestParam(defaultValue = "") String memberId) {
        return requireMember(memberId);
    }

    @GetMapping("/{memberId}")
    public Member detail(@PathVariable String memberId) {
        return requireMember(memberId);
    }

    @PostMapping("/{memberId}/subaccounts")
    public ResponseEntity<SubAccountConfirmation> openSubAccount(
            @PathVariable String memberId,
            @RequestBody OpenSubAccountRequest request,
            HttpSession session
    ) {
        String operator = OperatorSession.require(session);
        Member member = requireMember(memberId);
        if (!member.canOpenSubAccount()) {
            bankService.audit(AuditEventType.PERMISSION_DENIED, operator, member.id(),
                    "Blocked sub-account opening for restricted member " + member.id());
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", PERMISSION_DENIED_MESSAGE);
        }

        SubAccountDecision decision = subAccountPolicy.evaluate(request.productName(), request.initialDeposit());
        switch (decision.verdict()) {
            case REJECTED -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", decision.message());
            case HUMAN_APPROVAL_REQUIRED -> {
                bankService.audit(AuditEventType.HUMAN_APPROVAL_REQUIRED, operator, member.id(),
                        "Paused for human approval on member " + member.id() + " sub-account deposit " + decision.deposit());
                throw new ApiException(HttpStatus.CONFLICT, "HUMAN_APPROVAL_REQUIRED", decision.message());
            }
            case APPROVED -> {
                // fall through to open the account
            }
        }

        Account account = bankService.openSubAccount(operator, member.id(), decision.productName(), decision.deposit());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubAccountConfirmation(
                member.id(),
                member.name(),
                account.number(),
                account.type(),
                account.balance(),
                CONFIRMATION_CHECKPOINT
        ));
    }

    private Member requireMember(String rawMemberId) {
        String memberId = rawMemberId == null ? "" : rawMemberId.trim();
        if (!MEMBER_ID.matcher(memberId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEMBER_ID", "Invalid member ID. Enter exactly five digits.");
        }
        return bankService.findMember(memberId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "No member record was found for ID " + memberId + "."));
    }
}
