package io.github.hp2324.mockbank.api;

import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.service.BankService;
import io.github.hp2324.mockbank.web.OperatorSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session", description = "Sign in and out of the staff console")
@SecurityRequirements
@RestController
@RequestMapping("/api/session")
public class SessionController {
    // Demo credentials for the local mock only; shown on the sign-in page.
    private static final String DEMO_OPERATOR_ID = "operator";
    private static final String DEMO_PASSWORD = "password";

    private final BankService bankService;

    public SessionController(BankService bankService) {
        this.bankService = bankService;
    }

    public record LoginRequest(String operatorId, String password) {
    }

    public record SessionResponse(String operatorId) {
    }

    @Operation(summary = "Current operator", description = "401 AUTHENTICATION_REQUIRED when nobody is signed in.")
    @GetMapping
    public SessionResponse current(HttpSession session) {
        return new SessionResponse(OperatorSession.require(session));
    }

    @Operation(summary = "Sign in", description = "Demo credentials: operator / password. Sets the JSESSIONID session cookie; "
            + "401 INVALID_CREDENTIALS otherwise.")
    @PostMapping
    public SessionResponse login(@RequestBody LoginRequest request, HttpSession session) {
        if (DEMO_OPERATOR_ID.equals(request.operatorId()) && DEMO_PASSWORD.equals(request.password())) {
            OperatorSession.signIn(session, request.operatorId());
            bankService.audit(AuditEventType.OPERATOR_SIGNED_IN, request.operatorId(), null, "Operator signed in");
            return new SessionResponse(request.operatorId());
        }
        bankService.audit(AuditEventType.OPERATOR_SIGN_IN_FAILED, null, null, "Rejected sign-in attempt");
        throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid operator ID or password.");
    }

    @Operation(summary = "Sign out")
    @DeleteMapping
    public ResponseEntity<Void> logout(HttpSession session) {
        OperatorSession.signOut(session, bankService);
        return ResponseEntity.noContent().build();
    }
}
