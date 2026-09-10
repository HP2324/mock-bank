package com.example.mockbank.controller;

import com.example.mockbank.model.Account;
import com.example.mockbank.model.Member;
import com.example.mockbank.service.BankService;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.Optional;

@Controller
public class BankController {
    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String home(HttpSession session) {
        if (!isLoggedIn(session)) {
            return redirectScript("/login");
        }
        return redirectScript("/members/search");
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String loginPage(@RequestParam Optional<String> reason) {
        String banner = reason.map(value -> HtmlPage.banner("warning", switch (value) {
            case "expired" -> "Your staff session expired. Please sign in again.";
            case "required" -> "Please sign in to continue.";
            default -> "Please sign in.";
        })).orElse("");
        String form = banner + """
                <form method="post" action="/login">
                  <table class="field-table">
                    <tr><th><label for="operatorId">Operator ID</label></th><td><input id="operatorId" name="operatorId" autocomplete="username"></td></tr>
                    <tr><th><label for="password">Password</label></th><td><input id="password" name="password" type="password" autocomplete="current-password"></td></tr>
                    <tr><th>Demo credentials</th><td><span class="muted">operator / password</span></td></tr>
                    <tr><th></th><td><button type="submit">Sign in</button></td></tr>
                  </table>
                </form>
                """;
        return HtmlPage.layout("Staff Sign In", null, HtmlPage.panel("Staff sign in", form));
    }

    @PostMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(@RequestParam String operatorId, @RequestParam String password, HttpSession session) {
        if ("operator".equals(operatorId) && "password".equals(password)) {
            session.setAttribute("operatorId", operatorId);
            bankService.audit("Operator signed in");
            return redirectScript("/members/search");
        }
        String body = HtmlPage.banner("error", "Invalid operator ID or password.") + """
                <p><a class="button secondary" href="/login">Try again</a></p>
                """;
        return HtmlPage.layout("Sign In Failed", null, HtmlPage.panel("Staff sign in", body));
    }

    @GetMapping(value = "/logout", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String logout(HttpSession session) {
        session.invalidate();
        return redirectScript("/login");
    }

    @GetMapping(value = "/members/search", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String searchPage(HttpSession session, @RequestParam Optional<String> status) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=required");
        }
        String banner = status.map(value -> HtmlPage.banner("success", "Ready for next member lookup.")).orElse("");
        return HtmlPage.layout("Member Lookup", operator, HtmlPage.panel("Member lookup", banner + searchForm("")));
    }

    @PostMapping(value = "/members/search", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String search(@RequestParam String memberId, HttpSession session) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=expired");
        }
        String normalized = memberId == null ? "" : memberId.trim();
        if (!normalized.matches("\\d{5}")) {
            String body = HtmlPage.banner("error", "Invalid member ID. Enter exactly five digits.")
                    + searchForm(normalized);
            return HtmlPage.layout("Member Lookup Error", operator, HtmlPage.panel("Member lookup", body));
        }
        if (bankService.findMember(normalized).isEmpty()) {
            String body = HtmlPage.banner("warning", "No member record was found for ID " + normalized + ".")
                    + searchForm(normalized);
            return HtmlPage.layout("Member Not Found", operator, HtmlPage.panel("Member lookup", body));
        }
        return redirectScript("/members/" + normalized);
    }

    @GetMapping(value = "/members/{memberId}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String detail(@PathVariable String memberId, HttpSession session) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=expired");
        }
        return bankService.findMember(memberId)
                .map(member -> HtmlPage.layout("Member " + memberId, operator, memberDetail(member, "")))
                .orElseGet(() -> HtmlPage.layout("Member Not Found", operator,
                        HtmlPage.panel("Member detail", HtmlPage.banner("warning", "No member record was found for ID " + memberId + "."))));
    }

    @GetMapping(value = "/members/{memberId}/subaccounts/new", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String newSubAccount(@PathVariable String memberId, HttpSession session) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=expired");
        }
        Optional<Member> member = bankService.findMember(memberId);
        if (member.isEmpty()) {
            return HtmlPage.layout("Member Not Found", operator,
                    HtmlPage.panel("Open sub-account", HtmlPage.banner("warning", "No member record was found for ID " + memberId + ".")));
        }
        if (!member.get().canOpenSubAccount()) {
            String message = "Permission denied. This member is restricted and requires a supervisor override.";
            return HtmlPage.layout("Permission Denied", operator,
                    memberDetail(member.get(), HtmlPage.banner("error", message)));
        }
        return HtmlPage.layout("Open Sub-Account", operator, subAccountForm(member.get(), "", "", ""));
    }

    @PostMapping(value = "/members/{memberId}/subaccounts", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String createSubAccount(
            @PathVariable String memberId,
            @RequestParam String productName,
            @RequestParam String initialDeposit,
            HttpSession session
    ) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=expired");
        }
        Optional<Member> member = bankService.findMember(memberId);
        if (member.isEmpty()) {
            return HtmlPage.layout("Member Not Found", operator,
                    HtmlPage.panel("Open sub-account", HtmlPage.banner("warning", "No member record was found for ID " + memberId + ".")));
        }
        if (!member.get().canOpenSubAccount()) {
            return HtmlPage.layout("Permission Denied", operator,
                    memberDetail(member.get(), HtmlPage.banner("error", "Permission denied. This member is restricted and requires a supervisor override.")));
        }
        String trimmedProduct = productName == null ? "" : productName.trim();
        BigDecimal deposit;
        try {
            deposit = new BigDecimal(initialDeposit.trim());
        } catch (RuntimeException ex) {
            return HtmlPage.layout("Open Sub-Account Error", operator,
                    subAccountForm(member.get(), HtmlPage.banner("error", "Initial deposit must be a valid dollar amount."), trimmedProduct, initialDeposit));
        }
        if (trimmedProduct.isBlank()) {
            return HtmlPage.layout("Open Sub-Account Error", operator,
                    subAccountForm(member.get(), HtmlPage.banner("error", "Product name is required."), trimmedProduct, initialDeposit));
        }
        if (deposit.compareTo(BigDecimal.ZERO) < 0) {
            return HtmlPage.layout("Open Sub-Account Error", operator,
                    subAccountForm(member.get(), HtmlPage.banner("error", "Initial deposit cannot be negative."), trimmedProduct, initialDeposit));
        }
        if (deposit.compareTo(new BigDecimal("5000")) > 0) {
            String handoff = HtmlPage.banner("warning", "Human approval required. Initial deposits over $5,000 are treated as risky actions.");
            bankService.audit("Paused for human approval on member " + memberId + " sub-account deposit " + deposit);
            return HtmlPage.layout("Human Approval Required", operator,
                    subAccountForm(member.get(), handoff, trimmedProduct, initialDeposit));
        }
        Account account = bankService.openSubAccount(memberId, trimmedProduct, deposit);
        String body = HtmlPage.banner("success", "Sub-account opened successfully.")
                + """
                <table class="field-table">
                  <tr><th>Member ID</th><td>%s</td></tr>
                  <tr><th>Member name</th><td>%s</td></tr>
                  <tr><th>New account number</th><td>%s</td></tr>
                  <tr><th>Product</th><td>%s</td></tr>
                  <tr><th>Opening balance</th><td>%s</td></tr>
                  <tr><th>Checkpoint</th><td id="confirmation-code">CONFIRM-SUBACCOUNT-CREATED</td></tr>
                </table>
                <p><a class="button" href="/members/%s">Return to member detail</a>
                   <a class="button secondary" href="/members/search?status=ready">New lookup</a></p>
                """.formatted(
                HtmlPage.escape(memberId),
                HtmlPage.escape(member.get().name()),
                HtmlPage.escape(account.number()),
                HtmlPage.escape(account.type()),
                HtmlPage.money(account.balance()),
                HtmlPage.escape(memberId)
        );
        return HtmlPage.layout("Sub-Account Confirmation", operator, HtmlPage.panel("Confirmation", body));
    }

    @GetMapping(value = "/debug/expire-session", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String expireSession(HttpSession session) {
        session.invalidate();
        return redirectScript("/login?reason=expired");
    }

    @GetMapping(value = "/ops/audit", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String audit(HttpSession session) {
        String operator = requireLogin(session);
        if (operator == null) {
            return redirectScript("/login?reason=required");
        }
        StringBuilder rows = new StringBuilder();
        for (String event : bankService.auditEvents()) {
            rows.append("<tr><td>").append(HtmlPage.escape(event)).append("</td></tr>");
        }
        String body = """
                <table class="data-grid">
                  <tr><th>Recent events</th></tr>
                  %s
                </table>
                """.formatted(rows);
        return HtmlPage.layout("Audit Log", operator, HtmlPage.panel("Audit log", body));
    }

    private String requireLogin(HttpSession session) {
        Object operator = session.getAttribute("operatorId");
        return operator instanceof String value ? value : null;
    }

    private boolean isLoggedIn(HttpSession session) {
        return requireLogin(session) != null;
    }

    private String searchForm(String previousValue) {
        return """
                <form method="post" action="/members/search">
                  <table class="field-table">
                    <tr><th><label for="memberId">Member ID</label></th><td><input id="memberId" name="memberId" value="%s" inputmode="numeric"></td></tr>
                    <tr><th>Examples</th><td><span class="muted">12345 happy path, 77777 restricted, 00000 not found</span></td></tr>
                    <tr><th></th><td><button type="submit">Search member records</button></td></tr>
                  </table>
                </form>
                """.formatted(HtmlPage.escape(previousValue));
    }

    private String memberDetail(Member member, String leadingBanner) {
        StringBuilder accounts = new StringBuilder();
        for (Account account : member.accounts()) {
            accounts.append("""
                    <tr>
                      <td>%s</td>
                      <td>%s</td>
                      <td>%s</td>
                    </tr>
                    """.formatted(
                    HtmlPage.escape(account.number()),
                    HtmlPage.escape(account.type()),
                    HtmlPage.money(account.balance())
            ));
        }
        String action = member.canOpenSubAccount()
                ? "<a class=\"button\" href=\"/members/%s/subaccounts/new\">Open sub-account</a>".formatted(HtmlPage.escape(member.id()))
                : "<span class=\"muted\">Sub-account opening disabled for restricted members.</span>";
        String body = leadingBanner + """
                <table class="field-table">
                  <tr><th>Member ID</th><td>%s</td></tr>
                  <tr><th>Name</th><td>%s</td></tr>
                  <tr><th>Status</th><td>%s</td></tr>
                  <tr><th>Available action</th><td>%s</td></tr>
                </table>
                <h2>Accounts</h2>
                <table class="data-grid">
                  <tr><th>Account number</th><th>Account type</th><th>Current balance</th></tr>
                  %s
                </table>
                <p><a class="button secondary" href="/members/search">Back to search</a></p>
                """.formatted(
                HtmlPage.escape(member.id()),
                HtmlPage.escape(member.name()),
                HtmlPage.escape(member.status()),
                action,
                accounts
        );
        return HtmlPage.panel("Member detail", body);
    }

    private String subAccountForm(Member member, String banner, String productName, String initialDeposit) {
        String body = banner + """
                <table class="field-table">
                  <tr><th>Member ID</th><td>%s</td></tr>
                  <tr><th>Name</th><td>%s</td></tr>
                  <tr><th>Status</th><td>%s</td></tr>
                </table>
                <form method="post" action="/members/%s/subaccounts">
                  <table class="field-table">
                    <tr><th><label for="productName">Product name</label></th><td><input id="productName" name="productName" value="%s" placeholder="Savings Plus"></td></tr>
                    <tr><th><label for="initialDeposit">Initial deposit</label></th><td><input id="initialDeposit" name="initialDeposit" value="%s" placeholder="100.00"></td></tr>
                    <tr><th>Risk rule</th><td><span class="muted">Deposits over $5,000 require human approval.</span></td></tr>
                    <tr><th></th><td><button type="submit">Open sub-account</button> <a class="button secondary" href="/members/%s">Cancel</a></td></tr>
                  </table>
                </form>
                """.formatted(
                HtmlPage.escape(member.id()),
                HtmlPage.escape(member.name()),
                HtmlPage.escape(member.status()),
                HtmlPage.escape(member.id()),
                HtmlPage.escape(productName),
                HtmlPage.escape(initialDeposit),
                HtmlPage.escape(member.id())
        );
        return HtmlPage.panel("Open sub-account", body);
    }

    private String redirectScript(String path) {
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=%s"></head>
                <body><a href="%s">Continue</a></body>
                </html>
                """.formatted(HtmlPage.escape(path), HtmlPage.escape(path));
    }
}
