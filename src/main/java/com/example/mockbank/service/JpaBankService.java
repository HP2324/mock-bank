package com.example.mockbank.service;

import com.example.mockbank.entity.AccountEntity;
import com.example.mockbank.entity.AuditEventEntity;
import com.example.mockbank.entity.MemberEntity;
import com.example.mockbank.model.Account;
import com.example.mockbank.model.Member;
import com.example.mockbank.repository.AuditEventRepository;
import com.example.mockbank.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class JpaBankService implements BankService {
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemberRepository memberRepository;
    private final AuditEventRepository auditEventRepository;

    public JpaBankService(MemberRepository memberRepository, AuditEventRepository auditEventRepository) {
        this.memberRepository = memberRepository;
        this.auditEventRepository = auditEventRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Member> findMember(String memberId) {
        return memberRepository.findById(memberId).map(this::toMember);
    }

    @Override
    @Transactional
    public Account openSubAccount(String memberId, String productName, BigDecimal initialDeposit) {
        MemberEntity member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown member: " + memberId));
        String prefix = productName.toUpperCase().startsWith("SAV") ? "SAV" : "SUB";
        String number = prefix + "-" + memberId.substring(memberId.length() - 3) + "-" + (member.getAccounts().size() + 1);
        AccountEntity account = new AccountEntity(number, productName, initialDeposit);
        member.addAccount(account);
        memberRepository.save(member);
        audit("Opened " + productName + " " + account.getAccountNumber() + " for member " + memberId);
        return toAccount(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> auditEvents() {
        return auditEventRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(event -> event.getCreatedAt().format(AUDIT_TIME) + " - " + event.getMessage())
                .toList();
    }

    @Override
    @Transactional
    public void audit(String event) {
        auditEventRepository.save(new AuditEventEntity(LocalDateTime.now(), event));
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
}
