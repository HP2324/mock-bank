package com.example.mockbank.repository;

import com.example.mockbank.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
    List<AuditEventEntity> findAllByOrderByCreatedAtDescIdDesc();
}
