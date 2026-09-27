package com.isfam.domain.retention.service;

import com.isfam.common.alert.AlertSender;
import com.isfam.common.log.MdcContext;
import com.isfam.domain.audit.service.DataDeletionLogger;
import com.isfam.domain.retention.config.RetentionProperties;
import com.isfam.domain.retention.config.RetentionTarget;
import com.isfam.domain.retention.repository.RetentionPurgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 13-2 — 보관기간 파기 배치.
 *
 * <p>완료 기준: 배치 1회 → data_deletion_logs 가 대상 수만큼(0건이어도) / 경계값 /
 * 한 테이블 실패가 나머지를 막지 않음.
 */
@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T19:10:00Z");
    private static final int TARGET_COUNT = RetentionTarget.values().length;

    @Mock RetentionPurgeRepository purgeRepository;
    @Mock DataDeletionLogger deletionLogger;
    @Mock AlertSender alertSender;
    @Mock PlatformTransactionManager transactionManager;

    private RetentionPurgeService service;

    @BeforeEach
    void setUp() {
        service = newService(new RetentionProperties(null, null, 1_000, 3, null));
    }

    private RetentionPurgeService newService(RetentionProperties properties) {
        return new RetentionPurgeService(purgeRepository, deletionLogger, properties,
                alertSender, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void recordsEveryTargetEvenWhenNothingToDelete() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);

        RetentionPurgeService.RunResult run = service.purgeAll();

        // 원칙 R-4 — 0건이어도 대상 수만큼
        verify(deletionLogger, times(TARGET_COUNT)).recordBatchSuccess(
                anyString(), eq(0), any(), any(), any(), any());
        verify(deletionLogger, never()).recordBatchFailure(any(), any(), any(), any(), any(), any());
        assertThat(run.tables()).hasSize(TARGET_COUNT);
        assertThat(run.hasFailure()).isFalse();
        verify(alertSender).resolve(eq(RetentionPurgeService.ALERT_KEY_FAILED), anyString());
        verify(alertSender).resolve(eq(RetentionPurgeService.ALERT_KEY_BACKLOG), anyString());
    }

    @Test
    void cutoffIsNowMinusRetentionDays() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);

        service.purgeAll();

        verify(purgeRepository).deleteChunk(RetentionTarget.PHONE_VERIFICATIONS,
                NOW.minus(Duration.ofDays(30)), 1_000);
        verify(purgeRepository).deleteChunk(RetentionTarget.SPOOF_CHECKS,
                NOW.minus(Duration.ofDays(730)), 1_000);
        verify(deletionLogger).recordBatchSuccess(
                eq("spoof_checks"), eq(0), eq(NOW.minus(Duration.ofDays(730))), eq(730), any(), any());
    }

    @Test
    void loopsChunksUntilShortChunk() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        given(purgeRepository.deleteChunk(eq(RetentionTarget.NOTIFICATIONS), any(), eq(1_000)))
                .willReturn(1_000, 1_000, 250);

        RetentionPurgeService.RunResult run = service.purgeAll();

        verify(deletionLogger).recordBatchSuccess(
                eq("notifications"), eq(2_250), any(), eq(90), any(), any());
        assertThat(run.totalDeleted()).isEqualTo(2_250);
        assertThat(run.tables()).noneMatch(RetentionPurgeService.TableResult::capped);
    }

    @Test
    void chunkCapStopsAndFiresBacklogAlert() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        given(purgeRepository.deleteChunk(eq(RetentionTarget.PHONE_VERIFICATIONS), any(), eq(1_000)))
                .willReturn(1_000);   // 끝없이 가득 참

        RetentionPurgeService.RunResult run = service.purgeAll();

        // 상한 3청크에서 멈추고, 지운 만큼은 기록
        verify(purgeRepository, times(3))
                .deleteChunk(eq(RetentionTarget.PHONE_VERIFICATIONS), any(), eq(1_000));
        verify(deletionLogger).recordBatchSuccess(
                eq("phone_verifications"), eq(3_000), any(), any(), any(), any());
        assertThat(run.tables().get(0).capped()).isTrue();
        verify(alertSender).fire(eq(RetentionPurgeService.ALERT_KEY_BACKLOG), anyString());
    }

    @Test
    void oneTableFailureDoesNotStopOthers() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        willThrow(new QueryTimeoutException("select ... 010-1234-5678"))
                .given(purgeRepository)
                .deleteChunk(eq(RetentionTarget.VOICE_ANALYSES), any(), anyInt());

        RetentionPurgeService.RunResult run = service.purgeAll();

        // 실패 기록 — 클래스 단순명만, 메시지 금지(P-24)
        verify(deletionLogger).recordBatchFailure(
                eq("voice_analyses"), eq("QueryTimeoutException"), any(), eq(365), any(), any());
        // 나머지는 성공 기록
        verify(deletionLogger, times(TARGET_COUNT - 1)).recordBatchSuccess(
                anyString(), anyInt(), any(), any(), any(), any());
        verify(transactionManager).rollback(isNull());
        assertThat(run.hasFailure()).isTrue();
        verify(alertSender).fire(eq(RetentionPurgeService.ALERT_KEY_FAILED), eq("보관기간 파기 실패: voice_analyses"));
    }

    /** 원칙 R-5 — 성공 기록이 실패하면 그 테이블의 삭제도 롤백 대상 */
    @Test
    void successRecordFailureRollsBackThatTable() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        willThrow(new DataIntegrityViolationException("ck_data_deletion_logs ..."))
                .given(deletionLogger)
                .recordBatchSuccess(eq("notifications"), anyInt(), any(), any(), any(), any());

        RetentionPurgeService.RunResult run = service.purgeAll();

        verify(transactionManager).rollback(isNull());
        verify(deletionLogger).recordBatchFailure(
                eq("notifications"), eq("DataIntegrityViolationException"), any(), any(), any(), any());
        assertThat(run.hasFailure()).isTrue();
    }

    @Test
    void failureRecordFailureDoesNotStopBatch() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        willThrow(new QueryTimeoutException("x"))
                .given(purgeRepository)
                .deleteChunk(eq(RetentionTarget.PHONE_VERIFICATIONS), any(), anyInt());
        willThrow(new QueryTimeoutException("y"))
                .given(deletionLogger)
                .recordBatchFailure(any(), any(), any(), any(), any(), any());

        RetentionPurgeService.RunResult run = service.purgeAll();

        assertThat(run.tables()).hasSize(TARGET_COUNT);
        verify(deletionLogger, times(TARGET_COUNT - 1)).recordBatchSuccess(
                anyString(), anyInt(), any(), any(), any(), any());
    }

    /** 한 번의 실행이 남긴 row 가 같은 trace id 로 묶이고, 끝나면 MDC 가 비워짐 */
    @Test
    void oneTraceIdPerRunAndMdcClearedAfter() {
        given(purgeRepository.deleteChunk(any(), any(), anyInt())).willReturn(0);
        List<String> traceIds = new ArrayList<>();
        willAnswer(invocation -> {
            traceIds.add(MdcContext.traceId());
            return null;
        }).given(deletionLogger).recordBatchSuccess(any(), anyInt(), any(), any(), any(), any());

        service.purgeAll();

        assertThat(traceIds).hasSize(TARGET_COUNT).doesNotContainNull();
        assertThat(traceIds).containsOnly(traceIds.get(0));
        assertThat(MdcContext.traceId()).isNull();
    }
}