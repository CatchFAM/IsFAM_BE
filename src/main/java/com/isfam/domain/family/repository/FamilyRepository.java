package com.isfam.domain.family.repository;

import com.isfam.domain.family.entity.Family;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 조회 조건에 항상 {@code deletedAt IS NULL} 이 붙습니다.
 * 빼면 삭제된 공간이 GET /family 에 다시 나타납니다.
 */
public interface FamilyRepository extends JpaRepository<Family, Long> {

    Optional<Family> findByIdAndDeletedAtIsNull(Long id);
}