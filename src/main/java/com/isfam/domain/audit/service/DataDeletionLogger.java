package com.isfam.domain.audit.service;

import com.isfam.common.log.MdcContext;
import com.isfam.domain.audit.entity.DataDeletionLog;
import com.isfam.domain.audit.repository.DataDeletionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 파기 사실을 기록합니다 (P-29).
 *
 * <p>다른 두 로거와 같은 판단으로 {@link Propagation#MANDATORY} 입니다 —
 * 기록이 실패하면 파기도 롤백됩니다. <b>지워졌는데 기록이 없는 상태는 복구할 수 없고,</b>
 * 롤백은 재시도하면 됩니다. 되돌릴 수 있는 쪽을 고릅니다.
 *
 * <p><b>건수가 0 이어도 기록합니다.</b> 앞의 두 로거와 정반대인데, 이 테이블의 목적이
 * 다르기 때문입니다. {@code embedding_access_logs} 는 "제공이 일어났다"는 사실을 남기는
 * 곳이라 제공이 없으면 남길 게 없습니다. 반면 여기는 <b>"파기 의무를 이행했다"</b>를
 * 남기는 곳이고, "확인했는데 지울 게 없었다"도 이행입니다.
 *
 * <p>0 을 빼면 Phase 13 배치에서 <b>"지울 게 없었다"와 "배치가 죽었다"가 구분되지
 * 않습니다.</b> 둘 다 row 가 없는 모습이 되고, 그 상태로는 배치가 한 달 멈춰 있어도
 * 아무도 모릅니다 — Phase 11 에서 고쳤던 문제(S3-c)와 정확히 같은 구조입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataDeletionLogger {

    /**
     * 테이블 이름 상수.
     *
     * <p>{@code target_table} 을 enum 이 아니라 문자열로 둔 대신(이유는 엔티티 javadoc),
     * 오타를 막으려고 여기 모읍니다. 새 테이블을 파기 대상에 넣을 때 여기에 추가하세요.
     */
    public static final class Tables {
        public static final String USERS = "users";
        public static final String VOICEPRINTS = "voiceprints";
        public static final String VOICEPRINT_SAMPLES = "voiceprint_samples";
        public static final String VOICE_ANALYSES = "voice_analyses";
        public static final String CALL_EVENTS = "call_events";
        public static final String NOTIFICATIONS = "notifications";
        public static final String DEVICES = "devices";
        public static final String FAMILY_MEMBER_ALIASES = "family_member_aliases";
        public static final String PHONE_VERIFICATIONS = "phone_verifications";
        public static final String REFRESH_TOKENS = "refresh_tokens";
        public static final String SPOOF_CHECKS = "spoof_checks";
        public static final String DEMO_SESSIONS = "demo_sessions";

        private Tables() {
        }
    }

    private final DataDeletionLogRepository repository;

    /**
     * 회원 탈퇴로 지운 것들을 테이블별로 남깁니다.
     *
     * <p>탈퇴 1건에 테이블 수만큼 row 가 생깁니다. 한 row 로 묶지 않은 이유는
     * "무엇을 얼마나 지웠나"가 테이블 단위로 나와야 하고, Phase 13 배치도 같은 모양이라
     * 두 경로를 한 쿼리로 볼 수 있어야 하기 때문입니다.
     *
     * @param deletedByTable 테이블 이름 → 지운 건수. <b>0 인 항목도 넣으세요</b> —
     *                       "확인했고 지울 게 없었다"가 기록됩니다.
     *                       순서를 유지하려면 {@code LinkedHashMap} 을 쓰세요
     * @param startedAt      탈퇴 처리를 시작한 시각
     * @param finishedAt     삭제가 끝난 시각. 이 값이 row 의 {@code created_at} 이 됩니다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawal(Long userId, Map<String, Integer> deletedByTable,
                                 Instant startedAt, Instant finishedAt) {

        String traceId = resolveTraceId();

        deletedByTable.forEach((table, count) -> repository.save(DataDeletionLog.success(
                traceId, table, DataDeletionLog.TriggerType.WITHDRAWAL,
                userId, userId, count == null ? 0 : count,
                null, null, startedAt, finishedAt)));

        int total = deletedByTable.values().stream()
                .mapToInt(value -> value == null ? 0 : value)
                .sum();

        log.info("탈퇴 파기 기록 — userId={}, 테이블 {}개, 총 {}건",
                userId, deletedByTable.size(), total);
    }

    /**
     * 보관기간 경과 파기 배치의 결과 (Phase 13, P-03).
     *
     * <p>테이블 하나당 한 번 부릅니다. <b>0 건이어도 부르세요</b> — 배치가 돌았다는 증빙이
     * 그것뿐입니다.
     *
     * @param cutoffAt      이 시각 이전 데이터를 지웠습니다
     * @param retentionDays 적용된 보관 기간(일). {@code RetentionProperties} 값
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBatchSuccess(String targetTable, int deletedCount,
                                   Instant cutoffAt, Integer retentionDays,
                                   Instant startedAt, Instant finishedAt) {

        repository.save(DataDeletionLog.success(
                resolveTraceId(), targetTable, DataDeletionLog.TriggerType.RETENTION_BATCH,
                null, null, deletedCount, cutoffAt, retentionDays, startedAt, finishedAt));

        log.info("파기 배치 — {} {}건 (cutoff={})", targetTable, deletedCount, cutoffAt);
    }

    /**
     * 배치가 실패했을 때 (Phase 13).
     *
     * <p><b>예외 클래스명만 넘기세요.</b> {@code e.getMessage()} 에는 실패한 쿼리 조각이나
     * 파라미터가 들어오고, 그게 이 테이블에 박히면 파기 기록이 개인정보 저장소가 됩니다
     * (P-24 와 같은 이유). 원인은 {@code trace_id} 로 로그를 찾아가면 됩니다.
     *
     * <p>이 호출은 <b>실패한 테이블의 트랜잭션과 분리된 트랜잭션</b>에서 이뤄져야 합니다.
     * 같은 트랜잭션이면 롤백될 때 기록도 함께 사라져서, 실패를 남기려던 목적이 없어집니다.
     * Phase 13 배치는 테이블별로 트랜잭션을 나누므로(계획 문서) 그 구조를 따르세요.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBatchFailure(String targetTable, String failureType,
                                   Instant cutoffAt, Integer retentionDays,
                                   Instant startedAt, Instant finishedAt) {

        repository.save(DataDeletionLog.failure(
                resolveTraceId(), targetTable, DataDeletionLog.TriggerType.RETENTION_BATCH,
                null, null, failureType, cutoffAt, retentionDays, startedAt, finishedAt));

        log.error("파기 배치 실패 — {} ({})", targetTable, failureType);
    }

    /**
     * 정보주체의 파기 요구 (Phase 14, P-18 — {@code DELETE /me/voiceprint}).
     *
     * <p>미리 만들어 두는 이유는 Phase 14 에서 이 로거를 고치지 않고 쓰기 위해서입니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserRequest(Long userId, String targetTable, int deletedCount,
                                  Instant startedAt, Instant finishedAt) {

        repository.save(DataDeletionLog.success(
                resolveTraceId(), targetTable, DataDeletionLog.TriggerType.USER_REQUEST,
                userId, userId, deletedCount, null, null, startedAt, finishedAt));
    }

    /**
     * MDC 에 trace id 가 없으면 새로 만듭니다.
     *
     * <p>배치는 HTTP 요청이 아니라 {@code TraceIdFilter} 를 거치지 않습니다. Phase 13 에서
     * 배치 시작 시 {@code MdcContext.newTraceId()} 로 하나 만들어 실행 전체에 붙이면,
     * 그 배치가 남긴 테이블별 row 가 같은 {@code trace_id} 로 묶여 한 번의 실행으로 읽힙니다.
     */
    private String resolveTraceId() {
        String traceId = MdcContext.traceId();
        return traceId != null ? traceId : MdcContext.newTraceId();
    }
}