package com.example.mockbank.service;

import com.example.mockbank.model.Account;
import com.example.mockbank.model.Member;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BankService {
    Optional<Member> findMember(String memberId);

    Account openSubAccount(String memberId, String productName, BigDecimal initialDeposit);

    List<String> auditEvents();

    void audit(String event);
}
