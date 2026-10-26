package io.github.hp2324.mockbank.service;

import io.github.hp2324.mockbank.model.Account;
import io.github.hp2324.mockbank.model.AuditEvent;
import io.github.hp2324.mockbank.model.AuditEventType;
import io.github.hp2324.mockbank.model.Member;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BankService {
    Optional<Member> findMember(String memberId);

    Account openSubAccount(String operatorId, String memberId, String productName, BigDecimal initialDeposit);

    List<AuditEvent> recentAuditEvents(int limit);

    AuditEvent audit(AuditEventType type, String operatorId, String memberId, String message);
}
