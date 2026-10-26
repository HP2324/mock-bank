package com.example.mockbank.web;

import com.example.mockbank.service.BankService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React app for its client-side routes, and keeps the two server-side session routes
 * the automation artifacts navigate to directly.
 */
@Controller
public class PageController {
    private final BankService bankService;

    public PageController(BankService bankService) {
        this.bankService = bankService;
    }

    @GetMapping({"/", "/login", "/members/**", "/ops/**"})
    public String app() {
        return "forward:/index.html";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        OperatorSession.signOut(session, bankService);
        return "redirect:/login";
    }

    @GetMapping("/debug/expire-session")
    public String expireSession(HttpSession session) {
        session.invalidate();
        return "redirect:/login?reason=expired";
    }
}
