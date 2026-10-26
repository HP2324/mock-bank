package com.example.mockbank.api;

import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.service.BankService;
import com.example.mockbank.web.OperatorSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    public SessionResponse current(HttpSession session) {
        return new SessionResponse(OperatorSession.require(session));
    }

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

    @DeleteMapping
    public ResponseEntity<Void> logout(HttpSession session) {
        OperatorSession.signOut(session, bankService);
        return ResponseEntity.noContent().build();
    }
}
