package com.isfam.domain.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 파기 기록 (P-29).
 *
 * <p><b>왜 필요한가.</b> 법 제21조는 파기를 요구하고, 법 제39조는 <b>고의·과실을 추정</b>합니다 —
 * 사고가 나면 회사가 "우리는 잘못이 없다"를 증명해야 면책됩니다(L13-a·b·c). 그 증명 수단이
 * 접속기록·동의 이력·<b>파기 기록</b> 셋입니다. 지금까지 우리에겐 셋째가 없었습니다.
 *
 * <p>"지웠습니다"라고 말할 수는 있지만, 지워진 데이터는 정의상 남아 있지 않으므로
 * <b>지웠다는 사실 자체를 보여줄 방법이 없습니다.</b> 이 테이블이 그 자리를 채웁니다.
 *
 * <p><b>Phase 13 파기 배치가 결과를 남길 곳입니다.</b> 배치는 테이블별로 한 row 씩 남기고,
 * <b>지운 게 0건이어도 남깁니다</b> — 0건은 "지울 게 없었다"이지 "배치가 안 돌았다"가
 * 아닙니다. 둘을 구분하지 못하면 배치가 죽어 있어도 아무도 모릅니다.
 * 인프라 설계 계획의 대시보드 지표 "파기 배치 성공 여부"가 이 테이블을 읽습니다.
 *
 * <p><b>{@code voiceprint_access_logs} 와 겹치는 부분이 있습니다.</b> 생체정보 파기는 양쪽에
 * 남습니다. 의도한 중복입니다.
 * <ul>
 *   <li>{@code voiceprint_access_logs} — 생체정보 <b>한 종류</b>를 깊게. 파기 사유,
 *       모델 버전, 샘플/평균 구분까지</li>
 *   <li>이 테이블 — <b>모든 테이블</b>을 같은 모양으로. "탈퇴 1건에 무엇을 얼마나 지웠나"와
 *       "어제 배치가 무엇을 얼마나 지웠나"가 같은 쿼리로 나와야 합니다</li>
 * </ul>
 *
 * <p>{@code embedding_access_logs} · {@code voiceprint_access_logs} 와 마찬가지로
 * <b>수정되지 않는 row</b> 이고, Phase 15 에서 {@code UPDATE}·{@code DELETE} 권한이
 * 회수됩니다(P-13).
 */
@Entity
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "data_deletion_logs",
        indexes = {
                @Index(name = "ix_ddl_table", columnList = "target_table, created_at"),
                @Index(name = "ix_ddl_subject", columnList = "subject_user_id, created_at"),
                @Index(name = "ix_ddl_status", columnList = "status, created_at"),
                @Index(name = "ix_ddl_trace", columnList = "trace_id")
        })
public class DataDeletionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    /**
     * 지운 테이블 이름.
     *
     * <p>enum 이 아니라 문자열입니다. 테이블은 앞으로도 늘어나는데, enum 이면 값을 추가할
     * 때마다 코드 배포가 필요하고 <b>과거 row 를 읽는 코드가 모르는 값을 만나면 터집니다.</b>
     * 대신 오타가 들어갈 수 있어서, 상수는 {@code DataDeletionLogger} 에 모아 두었습니다.
     */
    @Column(name = "target_table", nullable = false, length = 64)
    private String targetTable;

    /** 무엇이 이 파기를 일으켰는가 */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;

    /**
     * 성공 여부.
     *
     * <p><b>실패도 기록합니다.</b> 파기 의무는 "시도했다"가 아니라 "이행했다"를 요구하므로,
     * 실패가 로그 파일에만 남고 아무도 안 보면 Phase 11 의 {@code countByPurgedAtIsNull()}
     * 과 똑같은 상황이 됩니다 — 메서드는 있는데 호출부가 없던 그것(S3-c).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /**
     * 파기 대상이 특정 개인이면 그 사람. 보관기간 배치처럼 대상이 여럿이면 null 입니다.
     *
     * <p>탈퇴자가 "제 데이터가 정말 지워졌나요"라고 물으면 이 컬럼으로 조회합니다.
     */
    @Column(name = "subject_user_id")
    private Long subjectUserId;

    /** 수행한 계정. 배치면 null — 사람이 한 일이 아니라는 뜻입니다 */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /**
     * 실제로 지워진 건수.
     *
     * <p><b>0 도 유효한 값입니다.</b> "지울 게 없었다"는 것도 확인한 결과입니다.
     */
    @Column(name = "deleted_count", nullable = false)
    private int deletedCount;

    /**
     * 보관기간 배치에서 "이 시각 이전"을 지운 경계. 탈퇴 같은 즉시 파기는 null 입니다.
     *
     * <p>Phase 13 에서 채워집니다. 조사관이 "보관기간을 지켰습니까"를 물으면
     * 이 값과 {@link #retentionDays} 가 답이 됩니다.
     */
    @Column(name = "cutoff_at")
    private Instant cutoffAt;

    /** 적용된 보관 기간(일). {@code RetentionProperties} 값이 그대로 들어갑니다 (Phase 13) */
    @Column(name = "retention_days")
    private Integer retentionDays;

    /**
     * 실패했을 때 예외의 <b>클래스명만</b>. {@link Status#FAILED} 일 때만 채웁니다.
     *
     * <p><b>{@code e.getMessage()} 를 넣지 마세요.</b> P-24 와 같은 이유입니다 —
     * 예외 메시지에는 실패한 쿼리 조각이나 파라미터가 들어오고, 그게 이 테이블에 박히면
     * 파기 기록이 개인정보 저장소가 됩니다. 원인 추적은 {@link #traceId} 로 로그를
     * 찾아가면 됩니다.
     */
    @Column(name = "failure_type", length = 100)
    private String failureType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum TriggerType {
        /** 회원 탈퇴 — {@code DELETE /auth/me} */
        WITHDRAWAL,
        /** 보관기간 경과 파기 배치 (Phase 13, P-03) */
        RETENTION_BATCH,
        /**
         * 정보주체의 파기 요구 — {@code DELETE /me/voiceprint} 등.
         * Phase 14(P-18)에서 쓰입니다.
         */
        USER_REQUEST
    }

    public enum Status {
        SUCCESS,
        FAILED
    }

    public static DataDeletionLog success(
            String traceId, String targetTable, TriggerType triggerType,
            Long subjectUserId, Long actorUserId, int deletedCount,
            Instant cutoffAt, Integer retentionDays,
            Instant startedAt, Instant finishedAt) {

        DataDeletionLog log = base(traceId, targetTable, triggerType,
                subjectUserId, actorUserId, cutoffAt, retentionDays, startedAt, finishedAt);
        log.status = Status.SUCCESS;
        log.deletedCount = deletedCount;
        return log;
    }

    public static DataDeletionLog failure(
            String traceId, String targetTable, TriggerType triggerType,
            Long subjectUserId, Long actorUserId, String failureType,
            Instant cutoffAt, Integer retentionDays,
            Instant startedAt, Instant finishedAt) {

        DataDeletionLog log = base(traceId, targetTable, triggerType,
                subjectUserId, actorUserId, cutoffAt, retentionDays, startedAt, finishedAt);
        log.status = Status.FAILED;
        log.deletedCount = 0;
        log.failureType = failureType;
        return log;
    }

    private static DataDeletionLog base(
            String traceId, String targetTable, TriggerType triggerType,
            Long subjectUserId, Long actorUserId,
            Instant cutoffAt, Integer retentionDays,
            Instant startedAt, Instant finishedAt) {

        DataDeletionLog log = new DataDeletionLog();
        log.traceId = traceId;
        log.targetTable = targetTable;
        log.triggerType = triggerType;
        log.subjectUserId = subjectUserId;
        log.actorUserId = actorUserId;
        log.cutoffAt = cutoffAt;
        log.retentionDays = retentionDays;
        log.startedAt = startedAt;
        log.finishedAt = finishedAt;
        log.createdAt = finishedAt;
        return log;
    }
}