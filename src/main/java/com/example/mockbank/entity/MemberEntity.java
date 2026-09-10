package com.example.mockbank.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "members")
public class MemberEntity {
    @Id
    private String memberId;

    private String name;
    private String status;
    private boolean canOpenSubAccount;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<AccountEntity> accounts = new ArrayList<>();

    protected MemberEntity() {
    }

    public MemberEntity(String memberId, String name, String status, boolean canOpenSubAccount) {
        this.memberId = memberId;
        this.name = name;
        this.status = status;
        this.canOpenSubAccount = canOpenSubAccount;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public boolean canOpenSubAccount() {
        return canOpenSubAccount;
    }

    public List<AccountEntity> getAccounts() {
        return accounts;
    }

    public void addAccount(AccountEntity account) {
        accounts.add(account);
        account.setMember(this);
    }
}
