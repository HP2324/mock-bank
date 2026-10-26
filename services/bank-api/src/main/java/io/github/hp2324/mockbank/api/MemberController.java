package io.github.hp2324.mockbank.api;

import io.github.hp2324.mockbank.model.Account;
import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.model.Member;
import io.github.hp2324.mockbank.service.BankService;
import io.github.hp2324.mockbank.service.SubAccountDecision;
import io.github.hp2324.mockbank.service.SubAccountPolicy;
import io.github.hp2324.mockbank.web.OperatorSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Members", description = "Member lookup and sub-account opening")
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
    @Operation(summary = "Look up a member from the search form", description = "Trims the typed ID, then validates it.")
    @ApiResponse(responseCode = "200", description = "Member found")
    @ApiResponse(responseCode = "400", description = "INVALID_MEMBER_ID: not exactly five digits",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/lookup")
    public Member lookup(@RequestParam(defaultValue = "") String memberId) {
        return requireMember(memberId);
    }

    @Operation(summary = "Member with accounts")
    @ApiResponse(responseCode = "200", description = "Member found")
    @ApiResponse(responseCode = "400", description = "INVALID_MEMBER_ID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{memberId}")
    public Member detail(@PathVariable String memberId) {
        return requireMember(memberId);
    }

    @Operation(summary = "Open a sub-account", description = "Deposits above $5,000 are not opened: they return 409 "
            + "HUMAN_APPROVAL_REQUIRED so a human can review them. Every outcome except validation errors is audited.")
    @ApiResponse(responseCode = "201", description = "Opened; the body carries checkpoint CONFIRM-SUBACCOUNT-CREATED")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or INVALID_MEMBER_ID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "PERMISSION_DENIED: restricted member",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "HUMAN_APPROVAL_REQUIRED: deposit over $5,000",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
