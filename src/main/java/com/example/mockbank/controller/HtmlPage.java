package com.example.mockbank.controller;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

final class HtmlPage {
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.US);

    private HtmlPage() {
    }

    static String layout(String title, String operatorId, String body) {
        String operator = operatorId == null ? "Not signed in" : escape(operatorId);
        String logout = operatorId == null ? "" : " &nbsp;|&nbsp; <a href=\"/logout\">Sign out</a>";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <link rel="stylesheet" href="/styles.css">
                </head>
                <body>
                  <div class="topbar">
                    <table><tr>
                      <td><strong>Northstar Credit Union - Staff Console</strong></td>
                      <td style="text-align:right">Operator: %s%s</td>
                    </tr></table>
                  </div>
                  <main class="shell">%s</main>
                </body>
                </html>
                """.formatted(escape(title), operator, logout, body);
    }

    static String panel(String heading, String body) {
        return """
                <section class="panel">
                  <h1>%s</h1>
                  <div class="panel-body">%s</div>
                </section>
                """.formatted(escape(heading), body);
    }

    static String banner(String type, String message) {
        return "<div class=\"banner " + escape(type) + "\" role=\"alert\">" + escape(message) + "</div>";
    }

    static String money(BigDecimal value) {
        return MONEY.format(value);
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
