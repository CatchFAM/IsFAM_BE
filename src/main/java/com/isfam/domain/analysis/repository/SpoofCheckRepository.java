package com.isfam.domain.analysis.repository;

import com.isfam.domain.analysis.entity.SpoofCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * <b>삭제 메서드가 없는 게 정상입니다.</b> 이 테이블은 접속기록 보관 의무 대상이라
 * 사용자 요청({@code DELETE /voice-analyses})으로도, 회원 탈퇴(Phase 9)로도 지우지 않습니다.
 * 보관 기간이 정해지면 그때 별도 배치를 만드세요 — 일반 API 경로에서 지울 수 있는
 * 수단을 열어두면 언젠가 누가 씁니다. (Phase 13, 보관 기간 2년)
 */
public interface SpoofCheckRepository extends JpaRepository<SpoofCheck, Long> {

    /** 남의 판정에 내 분석 결과를 묶는 걸 조회 단계에서 막습니다 */
    Optional<SpoofCheck> findByIdAndUserId(Long id, Long userId);

    /**
     * {@code GET /voice-analyses} 목록의 {@code spoof_score} 채우기용.
     *
     * <p>분석마다 한 건씩 조회하면 page_size 만큼 쿼리가 나갑니다(N+1).
     * 페이지의 spoof_check_id 를 모아 한 번에 가져옵니다 — 상한이 100 이라 크기가 뻔합니다.
     */
    List<SpoofCheck> findByIdIn(Collection<Long> ids);

    /**
     * 파기 누락 감시용 — <b>우리 서버(tmpfs)</b>.
     *
     * <p><b>이 값은 항상 0 이어야 합니다.</b> 0 이 아니면 오디오 원본이 tmpfs 에 남아 있을 수
     * 있다는 뜻이고, legal-compliance.md 4장 ③ 위반입니다.
     *
     * <p>Phase 11 부터 {@code PurgeMonitor} 가 5분 주기로 호출하고,
     * {@code PurgeHealthIndicator} 가 {@code /actuator/health} 에 노출합니다(P-21).
     */
    long countByPurgedAtIsNull();

    /**
     * 파기 누락 감시용 — <b>분석 서버(FastAPI)</b> (P-23).
     *
     * <p>{@code remote_purged = false} 인 건수입니다. <b>null 은 세지 않습니다</b> —
     * null 은 "분석 서버에 보낸 적 없음"(사전 거부) 또는 "아직 플래그를 안 보내는
     * 버전"이라 파기 실패와 성격이 다릅니다. 파생 쿼리 {@code IsFalse} 가
     * {@code = false} 로 번역되어 SQL 의 3값 논리상 null 이 자동으로 빠집니다.
     */
    long countByRemotePurgedIsFalse();
}