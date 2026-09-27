package com.isfam.domain.audit.repository;

import com.isfam.domain.audit.entity.DataDeletionLog;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 파기 기록 조회·기록.
 *
 * <p>다른 두 기록 테이블과 같은 이유로 {@code JpaRepository} 를 상속하지 않습니다 —
 * 파기 기록을 지울 수 있는 수단이 애플리케이션에 있으면 안 됩니다(P-13).
 *
 * <p><b>이 테이블은 Phase 13 파기 배치의 대상이 아닙니다.</b> 다른 기록 테이블들은
 * 2년 뒤 정리되지만, "무엇을 언제 지웠는가"는 그 데이터가 사라진 뒤에도 남아야 의미가
 * 있습니다. 보관 기간은 Phase 13 에서 확정합니다 — 기본 방향은 <b>장기 보존</b>입니다.
 */
public interface DataDeletionLogRepository extends Repository<DataDeletionLog, Long> {

    DataDeletionLog save(DataDeletionLog log);

    /**
     * 특정 사용자에 대한 파기 이력.
     *
     * <p>탈퇴자가 "제 데이터가 정말 지워졌나요"라고 물으면 이걸로 답합니다.
     * {@code users} row 는 익명화되지만 이 테이블의 {@code subject_user_id} 는 남습니다.
     */
    List<DataDeletionLog> findBySubjectUserIdOrderByCreatedAtDesc(Long subjectUserId);

    /**
     * <b>대시보드 지표 "파기 배치 성공 여부"의 원천입니다</b>(인프라 설계 계획).
     *
     * <p>0 이 아니면 즉시 알람 대상입니다.
     */
    long countByStatusAndCreatedAtAfter(DataDeletionLog.Status status, Instant after);

    /**
     * 기간 내 전체 파기 기록 건수.
     *
     * <p>배치 감시에는 쓰지 않습니다. 탈퇴 기록도 함께 세므로, 배치가 멈춰도 탈퇴가 있으면
     * 0 이 아니게 되어 정지를 가립니다. 감시는 {@link #findTopByTriggerTypeOrderByCreatedAtDesc}
     * + {@link #countByTraceId} 를 씁니다(Phase 13-3).
     */
    long countByCreatedAtAfter(Instant after);

    List<DataDeletionLog> findByTargetTableOrderByCreatedAtDesc(String targetTable);

    /** 같은 요청·같은 배치 실행에서 남은 기록을 한 덩어리로 모을 때 씁니다 */
    List<DataDeletionLog> findByTraceId(String traceId);

    // ── Phase 13-3 추가 ─────────────────────────────────────

    /**
     * 가장 최근의 파기 기록 한 건 — 배치 감시({@code RetentionMonitor})가
     * {@code RETENTION_BATCH} 로 부릅니다.
     *
     * <p>이 row 의 {@code trace_id} 가 곧 마지막 배치 실행입니다.
     */
    Optional<DataDeletionLog> findTopByTriggerTypeOrderByCreatedAtDesc(DataDeletionLog.TriggerType triggerType);

    /**
     * 한 번의 실행이 남긴 row 수. 배치는 대상 테이블마다 1행(성공이든 실패든)을 남기므로,
     * 대상 수보다 적으면 실행 도중 기록이 빠진 것입니다.
     */
    long countByTraceId(String traceId);
}