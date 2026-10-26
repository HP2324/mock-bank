package com.example.mockbank.service;

import com.example.mockbank.audit.AuditEventRecorded;
import com.example.mockbank.entity.AccountEntity;
import com.example.mockbank.entity.AuditEventEntity;
import com.example.mockbank.entity.MemberEntity;
import com.example.mockbank.model.Account;
import com.example.mockbank.model.AuditEvent;
import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.model.Member;
import com.example.mockbank.repository.AuditEventRepository;
import com.example.mockbank.repository.MemberRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JpaBankService implements BankService {
    private final MemberRepository memberRepository;
    private final AuditEventRepository auditEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public JpaBankService(
            MemberRepository memberRepository,
            AuditEventRepository auditEventRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.memberRepository = memberRepository;
        this.auditEventRepository = auditEventRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Member> findMember(String memberId) {
        return memberRepository.findById(memberId).map(this::toMember);
    }

    @Override
    @Transactional
    public Account openSubAccount(String operatorId, String memberId, String productName, BigDecimal initialDeposit) {
        MemberEntity member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown member: " + memberId));
        if (!member.canOpenSubAccount()) {
            throw new IllegalStateException("Member " + memberId + " is restricted from opening sub-accounts.");
        }
        String number = AccountNumbers.next(memberId, productName, member.getAccounts().size());
        AccountEntity account = new AccountEntity(number, productName, initialDeposit, now());
        member.addAccount(account);
        memberRepository.save(member);
        record(AuditEventType.SUBACCOUNT_OPENED, operatorId, memberId, number,
                "Opened " + productName + " " + number + " for member " + memberId);
        return toAccount(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEvent> recentAuditEvents(int limit) {
        return auditEventRepository.findByOrderByCreatedAtDescIdDesc(Limit.of(limit)).stream()
                .map(this::toAuditEvent)
                .toList();
    }

    @Override
    @Transactional
    public AuditEvent audit(AuditEventType type, String operatorId, String memberId, String message) {
        return record(type, operatorId, memberId, null, message);
    }

    private AuditEvent record(AuditEventType type, String operatorId, String memberId, String accountNumber, String message) {
        AuditEventEntity saved = auditEventRepository.save(new AuditEventEntity(
                UUID.randomUUID(), type, operatorId, memberId, accountNumber, message, now()));
        AuditEvent event = toAuditEvent(saved);
        // Delivered to Kafka by KafkaAuditEventPublisher only after this transaction commits.
        eventPublisher.publishEvent(new AuditEventRecorded(event));
        return event;
    }

    private Instant now() {
        // PostgreSQL stores microseconds; truncate so the returned value equals the stored one.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Member toMember(MemberEntity entity) {
        return new Member(
                entity.getMemberId(),
                entity.getName(),
                entity.getStatus(),
                entity.canOpenSubAccount(),
                entity.getAccounts().stream().map(this::toAccount).toList()
        );
    }

    private Account toAccount(AccountEntity entity) {
        return new Account(entity.getAccountNumber(), entity.getAccountType(), entity.getBalance());
    }

    private AuditEvent toAuditEvent(AuditEventEntity entity) {
        return new AuditEvent(
                entity.getEventId(),
                entity.getEventType(),
                entity.getOperatorId(),
                entity.getMemberId(),
                entity.getAccountNumber(),
                entity.getMessage(),
                entity.getCreatedAt()
        );
    }
}
