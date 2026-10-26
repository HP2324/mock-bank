package io.github.hp2324.mockbank.repository;

import io.github.hp2324.mockbank.entity.AuditEventEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
    List<AuditEventEntity> findByOrderByCreatedAtDescIdDesc(Limit limit);
}
