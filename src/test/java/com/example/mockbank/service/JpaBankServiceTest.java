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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaBankServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T14:30:15.123456789Z");

    private final MemberRepository members = mock(MemberRepository.class);
    private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private JpaBankService service;

    @BeforeEach
    void setUp() {
        service = new JpaBankService(members, auditEvents, events, Clock.fixed(NOW, ZoneOffset.UTC));
        when(auditEvents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static MemberEntity jordan() {
        MemberEntity member = new MemberEntity("12345", "Jordan Lee", "Active", true);
        member.addAccount(new AccountEntity("SAV-1001", "Primary Savings", new BigDecimal("1842.77"), NOW));
        member.addAccount(new AccountEntity("CHK-2042", "Everyday Checking", new BigDecimal("620.14"), NOW));
        return member;
    }

    @Test
    void findMemberMapsTheEntityAndItsAccounts() {
        when(members.findById("12345")).thenReturn(Optional.of(jordan()));

        Member member = service.findMember("12345").orElseThrow();

        assertThat(member.name()).isEqualTo("Jordan Lee");
        assertThat(member.canOpenSubAccount()).isTrue();
        assertThat(member.accounts()).extracting(Account::number).containsExactly("SAV-1001", "CHK-2042");
    }

    @Test
    void findMemberIsEmptyForUnknownIds() {
        when(members.findById("00000")).thenReturn(Optional.empty());

        assertThat(service.findMember("00000")).isEmpty();
    }

    @Test
    void openSubAccountAddsTheAccountAndAuditsIt() {
        MemberEntity jordan = jordan();
        when(members.findById("12345")).thenReturn(Optional.of(jordan));

        Account account = service.openSubAccount("operator", "12345", "Savings Plus", new BigDecimal("100.00"));

        assertThat(account).isEqualTo(new Account("SAV-345-3", "Savings Plus", new BigDecimal("100.00")));
        assertThat(jordan.getAccounts()).hasSize(3);
        verify(members).save(jordan);

        ArgumentCaptor<AuditEventEntity> saved = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(auditEvents).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo(AuditEventType.SUBACCOUNT_OPENED);
        assertThat(saved.getValue().getAccountNumber()).isEqualTo("SAV-345-3");
        assertThat(saved.getValue().getOperatorId()).isEqualTo("operator");
        assertThat(saved.getValue().getMessage()).isEqualTo("Opened Savings Plus SAV-345-3 for member 12345");
    }

    @Test
    void openSubAccountRejectsUnknownMembers() {
        when(members.findById("00000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openSubAccount("operator", "00000", "Savings Plus", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        verify(auditEvents, never()).save(any());
    }

    @Test
    void openSubAccountRefusesRestrictedMembersEvenIfTheControllerMissedIt() {
        when(members.findById("77777")).thenReturn(Optional.of(new MemberEntity("77777", "Riley Morgan", "Restricted", false)));

        assertThatThrownBy(() -> service.openSubAccount("operator", "77777", "Savings Plus", BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
        verify(members, never()).save(any());
    }

    @Test
    void auditStoresTheEventAndRaisesItForKafka() {
        AuditEvent event = service.audit(AuditEventType.OPERATOR_SIGNED_IN, "operator", null, "Operator signed in");

        assertThat(event.type()).isEqualTo(AuditEventType.OPERATOR_SIGNED_IN);
        assertThat(event.eventId()).isNotNull();
        // PostgreSQL keeps microseconds, so the service truncates before storing.
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-26T14:30:15.123456Z"));
        verify(events).publishEvent(new AuditEventRecorded(event));
    }

    @Test
    void recentAuditEventsPassesTheLimitAndMapsRows() {
        UUID id = UUID.randomUUID();
        when(auditEvents.findByOrderByCreatedAtDescIdDesc(Limit.of(5))).thenReturn(List.of(
                new AuditEventEntity(id, AuditEventType.PERMISSION_DENIED, "operator", "77777", null, "Blocked", NOW)));

        List<AuditEvent> recent = service.recentAuditEvents(5);

        assertThat(recent).containsExactly(
                new AuditEvent(id, AuditEventType.PERMISSION_DENIED, "operator", "77777", null, "Blocked", NOW));
    }
}
