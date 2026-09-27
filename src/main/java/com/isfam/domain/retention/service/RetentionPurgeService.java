package com.isfam.domain.retention.service;

import com.isfam.common.alert.AlertSender;
import com.isfam.common.log.MdcContext;
import com.isfam.domain.audit.service.DataDeletionLogger;
import com.isfam.domain.retention.config.RetentionProperties;
import com.isfam.domain.retention.config.RetentionTarget;
import com.isfam.domain.retention.repository.RetentionPurgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 보관기간 경과 파기 (P-03 · P-27).
 *
 * <p>legal-compliance.md 4장 "보관 및 파기" 를 실행합니다. 이 클래스가 정책을 정하지 않습니다 —
 * 대상은 {@link RetentionTarget}, 기간은 {@link RetentionProperties} 에 있고 둘 다 문서가 출처입니다.
 *
 * <h2>트랜잭션 구조</h2>
 * <pre>
 *   purgeAll()                         트랜잭션 없음, trace id 1개
 *    ├─ [tx] phone_verifications        청크 DELETE × n  +  recordBatchSuccess
 *    ├─ [tx] refresh_tokens             …
 *    ├─ [tx] voice_analyses  ✗ 실패 → 롤백
 *    │   └─ [tx] recordBatchFailure     ← 실패한 트랜잭션 밖 (원칙 R-6)
 *    ├─ [tx] call_events                계속 진행
 *    └─ …
 * </pre>
 *
 * <ul>
 *   <li><b>테이블마다 트랜잭션이 따로</b> — 한 테이블의 실패가 나머지를 막지 않습니다</li>
 *   <li><b>삭제와 성공 기록이 같은 트랜잭션</b> — 기록이 실패하면 삭제도 롤백됩니다 (원칙 R-5).
 *       {@code DataDeletionLogger} 와 {@code RetentionPurgeRepository} 가 둘 다
 *       {@code MANDATORY} 라 이 구조 밖에서는 호출 자체가 실패합니다</li>
 *   <li><b>실패 기록은 새 트랜잭션</b> — 같은 트랜잭션이면 롤백과 함께 사라집니다 (원칙 R-6)</li>
 *   <li><b>0건이어도 기록</b> — 대상 수만큼 매일 row 가 생깁니다 (원칙 R-4). 그래야
 *       "지울 게 없었다"와 "배치가 안 돌았다"가 구분됩니다</li>
 * </ul>
 *
 * <p><b>한 트랜잭션 안에서 청크로 나누는 이유.</b> 트랜잭션을 청크마다 끊으면 락은 짧아지지만
 * 기록이 청크 수만큼 생기거나, 삭제 뒤 기록 전에 죽으면 "지웠는데 기록이 없는" 청크가 남습니다.
 * 대신 청크 상한({@code max-chunks-per-run})으로 한 트랜잭션의 크기를 묶습니다. 평상시 하루치는
 * 청크 한두 개이고, 상한에 걸리는 건 첫 가동일처럼 밀린 분량이 있을 때뿐입니다.
 *
 * <p><b>예외 메시지를 남기지 않습니다</b> (P-24). PostgreSQL 오류 메시지에는 실패한 행의 값이
 * 통째로 들어옵니다 — {@code spoof_checks} 면 {@code client_ip} 까지. 클래스 이름과
 * trace id 만 남기고, 원인은 DB 쪽 로그에서 찾습니다.
 *
 * <p><b>인스턴스가 둘 이상이면 두 번 돕니다.</b> 삭제는 멱등이라 데이터는 안전하지만 기록이
 * 두 벌 생깁니다. 현재 설계는 단일 인스턴스(인프라 설계 계획)라 락을 두지 않았고,
 * 수평 확장 시 분산 락(ShedLock 등)을 붙입니다 — Phase 15 항목.
 */
@Slf4j
@Service
public class RetentionPurgeService {

    /** 한 테이블이라도 파기에 실패함 */
    public static final String ALERT_KEY_FAILED = "retention.batch.failed";

    /** 청크 상한에 걸림 — 밀린 파기가 5일 내 파기 요건을 넘길 수 있음 */
    public static final String ALERT_KEY_BACKLOG = "retention.batch.backlog";

    private final RetentionPurgeRepository purgeRepository;
    private final DataDeletionLogger deletionLogger;
    private final RetentionProperties properties;
    private final AlertSender alertSender;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public RetentionPurgeService(RetentionPurgeRepository purgeRepository,
                                 DataDeletionLogger deletionLogger,
                                 RetentionProperties properties,
                                 AlertSender alertSender,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.purgeRepository = purgeRepository;
        this.deletionLogger = deletionLogger;
        this.properties = properties;
        this.alertSender = alertSender;
        this.clock = clock;

        // 호출자가 트랜잭션을 들고 들어와도 테이블마다 독립이어야 합니다.
        // REQUIRED 면 바깥 트랜잭션에 합류해 한 테이블 실패가 전체를 롤백합니다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 전 대상을 한 번 파기합니다.
     *
     * <p>기준 시각({@code now})은 시작할 때 한 번만 잡습니다. 테이블마다 다시 읽으면 같은 실행
     * 안에서 cutoff 가 조금씩 밀려, 기록된 {@code cutoff_at} 이 테이블마다 달라집니다.
     *
     * <p>trace id 를 하나 만들어 실행 전체에 붙입니다. 그 배치가 남긴 row 들이 같은
     * {@code trace_id} 로 묶여 "어제 한 번의 실행"으로 읽힙니다.
     */
    public RunResult purgeAll() {
        MdcContext.put(MdcContext.TRACE_ID, MdcContext.newTraceId());
        try {
            Instant now = clock.instant();
            List<TableResult> results = new ArrayList<>();

            for (RetentionTarget target : RetentionTarget.values()) {
                results.add(purge(target, now));
            }

            RunResult run = new RunResult(now, List.copyOf(results));
            report(run);
            return run;
        } finally {
            // 스케줄러 스레드는 재사용됩니다. 남기면 다음 작업 로그에 이 trace id 가 붙습니다
            MdcContext.clear();
        }
    }

    // ── 내부 ────────────────────────────────────────────────

    private TableResult purge(RetentionTarget target, Instant now) {
        int retentionDays = properties.daysFor(target);
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        Instant startedAt = clock.instant();

        try {
            return transactionTemplate.execute(status -> {
                int chunkSize = properties.chunkSize();
                int maxChunks = properties.maxChunksPerRun();

                int deleted = 0;
                int chunks = 0;
                boolean capped = false;

                while (true) {
                    int affected = purgeRepository.deleteChunk(target, cutoff, chunkSize);
                    deleted += affected;
                    chunks++;

                    if (affected < chunkSize) {
                        break;           // 이번 실행에서 지울 게 끝남
                    }
                    if (chunks >= maxChunks) {
                        capped = true;   // 남은 분량은 내일. 알람으로 알립니다
                        break;
                    }
                }

                deletionLogger.recordBatchSuccess(target.table(), deleted,
                        cutoff, retentionDays, startedAt, clock.instant());

                return TableResult.success(target, deleted, capped);
            });

        } catch (RuntimeException e) {
            String failureType = e.getClass().getSimpleName();
            recordFailure(target, failureType, cutoff, retentionDays, startedAt);
            return TableResult.failure(target, failureType);
        }
    }

    /**
     * 실패를 <b>별도 트랜잭션</b>에 남깁니다.
     *
     * <p>이것마저 실패하면(DB 가 내려간 경우 등) 로그와 알람만 남기고 다음 테이블로 넘어갑니다.
     * 여기서 던지면 남은 테이블이 전부 멈춥니다. 알람은 {@link #report} 에서 나가고,
     * 기록이 빠진 날은 13-3 의 감시가 "24시간 동안 기록 없음"으로 잡습니다.
     */
    private void recordFailure(RetentionTarget target, String failureType,
                               Instant cutoff, int retentionDays, Instant startedAt) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    deletionLogger.recordBatchFailure(target.table(), failureType,
                            cutoff, retentionDays, startedAt, clock.instant()));
        } catch (RuntimeException recordError) {
            log.error("파기 배치 실패 기록도 실패 — {} (원인 {}, 기록 {})",
                    target.table(), failureType, recordError.getClass().getSimpleName());
        }
    }

    private void report(RunResult run) {
        String failed = run.tables().stream()
                .filter(TableResult::failed)
                .map(result -> result.target().table())
                .collect(Collectors.joining(", "));

        String backlog = run.tables().stream()
                .filter(TableResult::capped)
                .map(result -> result.target().table())
                .collect(Collectors.joining(", "));

        // 알람에는 테이블 이름과 건수만 — AlertSender 규칙(리전 밖으로 나가는 경로)
        if (failed.isEmpty()) {
            alertSender.resolve(ALERT_KEY_FAILED, "보관기간 파기 배치 실패 없음");
        } else {
            alertSender.fire(ALERT_KEY_FAILED, "보관기간 파기 실패: " + failed);
        }

        if (backlog.isEmpty()) {
            alertSender.resolve(ALERT_KEY_BACKLOG, "보관기간 파기 밀림 없음");
        } else {
            alertSender.fire(ALERT_KEY_BACKLOG,
                    "보관기간 파기 청크 상한 도달(남은 분량은 다음 실행): " + backlog);
        }

        log.info("보관기간 파기 배치 완료 — 기준 {}, 총 {}건, 실패 [{}], 상한 도달 [{}]",
                run.startedAt(), run.totalDeleted(), failed, backlog);
    }

    /** 한 번의 실행 결과 */
    public record RunResult(Instant startedAt, List<TableResult> tables) {

        public int totalDeleted() {
            return tables.stream().mapToInt(TableResult::deleted).sum();
        }

        public boolean hasFailure() {
            return tables.stream().anyMatch(TableResult::failed);
        }
    }

    /**
     * 테이블 하나의 결과.
     *
     * @param failureType 실패했을 때 예외 클래스 단순명. 성공이면 null
     * @param capped      청크 상한에 걸려 남은 분량이 있을 수 있음
     */
    public record TableResult(RetentionTarget target, int deleted, boolean capped, String failureType) {

        static TableResult success(RetentionTarget target, int deleted, boolean capped) {
            return new TableResult(target, deleted, capped, null);
        }

        static TableResult failure(RetentionTarget target, String failureType) {
            return new TableResult(target, 0, false, failureType);
        }

        public boolean failed() {
            return failureType != null;
        }
    }
}