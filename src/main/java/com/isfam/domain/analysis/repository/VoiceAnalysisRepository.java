package com.isfam.domain.analysis.repository;

import com.isfam.domain.analysis.entity.VoiceAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * <p>{@code JpaSpecificationExecutor} 를 쓰는 이유는 {@code GET /voice-analyses} 의
 * 필터가 4개(date_from, date_to, final_decision, min_risk_level)이고 전부 선택이라
 * 조합이 16가지이기 때문입니다. JPQL 에 {@code (:param is null or ...)} 을 늘어놓는 방식은
 * Hibernate 6 에서 파라미터 타입 추론이 실패해 런타임에 터지는 경우가 있어 피했습니다.
 */
public interface VoiceAnalysisRepository
        extends JpaRepository<VoiceAnalysis, Long>, JpaSpecificationExecutor<VoiceAnalysis> {

    Optional<VoiceAnalysis> findByIdAndUserId(Long id, Long userId);

    /**
     * 중복 제출 판정.
     *
     * <p>앱이 제출 응답을 못 받고 재시도하는 흐름이 실제로 있습니다. 유니크 제약이
     * 최종 방어선이지만, 그 전에 여기서 걸러 같은 요청이면 기존 결과를 그대로 돌려줍니다 —
     * 그래야 {@code danger_call} 알림이 두 번 가지 않습니다.
     */
    Optional<VoiceAnalysis> findByCallEventId(Long callEventId);

    boolean existsBySpoofCheckId(Long spoofCheckId);

    /** 회원 탈퇴 시 삭제 (Phase 9 ⑤). spoof_checks 는 대상이 아닙니다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from VoiceAnalysis v where v.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}