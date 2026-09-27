package com.isfam.domain.analysis.repository;

import com.isfam.domain.analysis.entity.SpoofCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
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
     * 파기 누락 전체 건수 — <b>우리 서버(tmpfs)</b>.
     *
     * <p>감시({@code PurgeMonitor})는 이 메서드가 아니라 아래 기간 버전을 씁니다.
     * 이 테이블은 row 를 고치지 않으므로 전체 건수는 한 번 1 이 되면 보관 기간 내내
     * 줄지 않습니다. 조사·점검용으로 남겨 둡니다.
     */
    long countByPurgedAtIsNull();

    /**
     * 파기 누락 전체 건수 — <b>분석 서버(FastAPI)</b> (P-23).
     *
     * <p>{@code remote_purged = false} 인 건수입니다. <b>null 은 세지 않습니다</b> —
     * null 은 "분석 서버에 보낸 적 없음"(사전 거부)·"아직 플래그를 안 보내는 버전"·
     * "응답을 못 받음"(failed)이라 파기 실패와 성격이 다릅니다. 파생 쿼리 {@code IsFalse} 가
     * {@code = false} 로 번역되어 SQL 의 3값 논리상 null 이 자동으로 빠집니다.
     */
    long countByRemotePurgedIsFalse();

    /**
     * 최근 파기 누락 건수 — <b>우리 서버(tmpfs)</b>. {@code PurgeMonitor} 가 5분마다 부릅니다.
     *
     * <p><b>기간을 거는 이유.</b> 파기 실패 row 는 접속기록이라 고치지도 지우지도 않습니다.
     * 전체를 세면 실패 1건이 2년 내내 알람과 헬스체크 DOWN 을 유지해, 그 뒤에 새로 생긴
     * 실패를 알아챌 수 없습니다. {@code requested_at} 이 {@code since} 이후인 것만 셉니다.
     */
    long countByPurgedAtIsNullAndRequestedAtGreaterThanEqual(Instant since);

    /** 최근 파기 누락 건수 — <b>분석 서버(FastAPI)</b>. 기간을 거는 이유는 위와 같습니다 */
    long countByRemotePurgedIsFalseAndRequestedAtGreaterThanEqual(Instant since);
}