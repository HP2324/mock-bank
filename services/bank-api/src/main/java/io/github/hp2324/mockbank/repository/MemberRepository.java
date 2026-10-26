package io.github.hp2324.mockbank.repository;

import io.github.hp2324.mockbank.entity.MemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<MemberEntity, String> {
}
