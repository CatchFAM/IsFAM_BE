package com.isfam.domain.voice.repository;

import com.isfam.domain.voice.entity.Voiceprint;
import org.springframework.data.jpa.repository.JpaRepository;
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
}