package io.github.hp2324.mockbank.web;

import io.github.hp2324.mockbank.api.ApiException;
import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.service.BankService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;

/** The signed-in operator lives in the servlet session (JSESSIONID cookie), as in the legacy app. */
public final class OperatorSession {
    static final String ATTRIBUTE = "operatorId";

    private OperatorSession() {
    }

    public static String current(HttpSession session) {
        return session != null && session.getAttribute(ATTRIBUTE) instanceof String operator ? operator : null;
    }

    public static String require(HttpSession session) {
        String operator = current(session);
        if (operator == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Please sign in to continue.");
        }
        return operator;
    }

    public static void signIn(HttpSession session, String operatorId) {
        session.setAttribute(ATTRIBUTE, operatorId);
    }

    public static void signOut(HttpSession session, BankService bankService) {
        String operator = current(session);
        if (operator != null) {
            bankService.audit(AuditEventType.OPERATOR_SIGNED_OUT, operator, null, "Operator signed out");
        }
        session.invalidate();
    }
}
