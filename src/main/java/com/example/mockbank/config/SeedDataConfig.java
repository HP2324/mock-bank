package com.example.mockbank.config;

import com.example.mockbank.entity.AccountEntity;
import com.example.mockbank.entity.MemberEntity;
import com.example.mockbank.repository.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class SeedDataConfig {
    @Bean
    CommandLineRunner seedMembers(MemberRepository memberRepository) {
        return args -> {
            if (memberRepository.count() > 0) {
                return;
            }

            MemberEntity jordan = new MemberEntity("12345", "Jordan Lee", "Active", true);
            jordan.addAccount(new AccountEntity("SAV-1001", "Primary Savings", new BigDecimal("1842.77")));
            jordan.addAccount(new AccountEntity("CHK-2042", "Everyday Checking", new BigDecimal("620.14")));
            memberRepository.save(jordan);

            MemberEntity maya = new MemberEntity("24680", "Maya Chen", "Active", true);
            maya.addAccount(new AccountEntity("SAV-9820", "Primary Savings", new BigDecimal("9320.01")));
            maya.addAccount(new AccountEntity("MM-1180", "Money Market", new BigDecimal("15100.00")));
            memberRepository.save(maya);

            MemberEntity riley = new MemberEntity("77777", "Riley Morgan", "Restricted", false);
            riley.addAccount(new AccountEntity("CHK-7770", "Everyday Checking", new BigDecimal("88.45")));
            memberRepository.save(riley);
        };
    }
}
