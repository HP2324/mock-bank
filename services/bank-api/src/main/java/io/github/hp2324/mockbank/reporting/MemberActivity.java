package io.github.hp2324.mockbank.reporting;

import java.time.Instant;

public record MemberActivity(
        String memberId,
        String name,
        int subAccountsOpened,
        int approvalsRequested,
        int permissionDenials,
        Instant lastActivityAt
) {
}
