package com.isfam.domain.voice.repository;

import com.isfam.domain.voice.entity.Voiceprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VoiceprintRepository extends JpaRepository<Voiceprint, Long> {

    Optional<Voiceprint> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /**
     * GET /family 의 members[].voiceprint_registered 용.
     *
     * <p>멤버마다 existsByUserId 를 돌면 8명이면 쿼리가 8번 나갑니다.
     * 등록된 user_id 집합을 한 번에 받아 메모리에서 대조합니다.
     */
    @Query("select v.userId from Voiceprint v where v.userId in :userIds")
    List<Long> findRegisteredUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * GET /family/embeddings 용 (Phase 7).
     *
     * <p>임베딩 본문까지 필요하므로 위 메서드와 달리 엔티티를 통째로 가져옵니다.
     * 가족 정원이 8명이라 한 번에 받아도 크기가 뻔합니다(796B × 7).
     */
    List<Voiceprint> findByUserIdIn(Collection<Long> userIds);

    /**
     * 회원 탈퇴 시 물리 삭제(Phase 9). 생체정보는 soft delete 가 허용되지 않습니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Voiceprint v where v.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}