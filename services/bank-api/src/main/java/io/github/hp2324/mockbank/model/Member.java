package io.github.hp2324.mockbank.model;

import java.util.List;

public record Member(
        String id,
        String name,
        String status,
        boolean canOpenSubAccount,
        List<Account> accounts
) {
}
