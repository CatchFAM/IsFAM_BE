package com.isfam.domain.family.repository;

import com.isfam.domain.family.entity.FamilyPassphrase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FamilyPassphraseRepository extends JpaRepository<FamilyPassphrase, Long> {

    Optional<FamilyPassphrase> findByFamilyId(Long familyId);

    /**
     * 공간 삭제 정리. {@code FamilyMemberAliasRepository.deleteAllByFamilyId} 와 같은 규칙입니다 —
     * 호출 전 변경은 {@code flushAutomatically} 로 먼저 반영되고, 호출 후엔 영속성 컨텍스트가 비워집니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FamilyPassphrase p where p.familyId = :familyId")
    int deleteAllByFamilyId(@Param("familyId") Long familyId);
}