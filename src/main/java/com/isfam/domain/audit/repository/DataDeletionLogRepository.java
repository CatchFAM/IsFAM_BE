package com.isfam.domain.audit.repository;

import com.isfam.domain.audit.entity.DataDeletionLog;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;

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
     * 배치가 <b>돌기는 했는지</b> 확인합니다.
     *
     * <p>실패 건수만 보면 "배치가 아예 안 돌았다"를 놓칩니다 — 실패가 0건인 건
     * 잘 돌았을 때와 죽었을 때가 똑같습니다. 지난 24시간에 row 가 하나도 없으면
     * 그것도 이상 신호입니다(Phase 13 에서 이 판정을 붙입니다).
     */
    long countByCreatedAtAfter(Instant after);

    List<DataDeletionLog> findByTargetTableOrderByCreatedAtDesc(String targetTable);

    /** 같은 요청·같은 배치 실행에서 남은 기록을 한 덩어리로 모을 때 씁니다 */
    List<DataDeletionLog> findByTraceId(String traceId);
}