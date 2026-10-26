package io.github.hp2324.mockbank.service;

import java.util.Locale;

final class AccountNumbers {
    private AccountNumbers() {
    }

    /** e.g. member 12345 with 2 accounts opening "Savings Plus" gets SAV-345-3. */
    static String next(String memberId, String productName, int existingAccounts) {
        String prefix = productName.toUpperCase(Locale.ROOT).startsWith("SAV") ? "SAV" : "SUB";
        return prefix + "-" + memberId.substring(memberId.length() - 3) + "-" + (existingAccounts + 1);
    }
}
