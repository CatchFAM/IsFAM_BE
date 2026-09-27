package com.isfam.domain.retention.service;

import com.isfam.common.alert.AlertSender;
import com.isfam.domain.audit.entity.DataDeletionLog;
import com.isfam.domain.audit.repository.DataDeletionLogRepository;
import com.isfam.domain.retention.config.RetentionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 13-3 — 파기 배치 감시.
 */
@ExtendWith(MockitoExtension.class)
class RetentionMonitorTest {

    private static final Instant BOOT = Instant.parse("2026-09-27T00:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(26);
    private static final DataDeletionLog.TriggerType BATCH = DataDeletionLog.TriggerType.RETENTION_BATCH;
    private static final long TARGETS = RetentionTarget.values().length;

    @Mock DataDeletionLogRepository repository;
    @Mock AlertSender alertSender;

    private final MutableClock clock = new MutableClock(BOOT);

    private RetentionMonitor newMonitor() {
        return new RetentionMonitor(repository, alertSender, clock, WINDOW);
    }

    private static DataDeletionLog batchRow(String traceId, Instant createdAt) {
        return DataDeletionLog.success(traceId, "notifications", BATCH,
                null, null, 0, createdAt.minus(Duration.ofDays(90)), 90, createdAt, createdAt);
    }

    @Test
    void noRecordRightAfterBootIsNotAlerted() {
        RetentionMonitor monitor = newMonitor();
        clock.set(BOOT.plus(Duration.ofHours(3)));
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH)).willReturn(Optional.empty());

        monitor.check();

        verifyNoInteractions(alertSender);
    }

    @Test
    void noRecordAfterWindowIsAlerted() {
        RetentionMonitor monitor = newMonitor();
        clock.set(BOOT.plus(Duration.ofHours(27)));
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH)).willReturn(Optional.empty());

        monitor.check();

        verify(alertSender).fire(eq(RetentionMonitor.ALERT_KEY), anyString());
    }

    @Test
    void staleLastRunIsAlerted() {
        RetentionMonitor monitor = newMonitor();
        Instant now = BOOT.plus(Duration.ofDays(5));
        clock.set(now);
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH))
                .willReturn(Optional.of(batchRow("t1", now.minus(Duration.ofHours(30)))));

        monitor.check();

        verify(alertSender).fire(eq(RetentionMonitor.ALERT_KEY), anyString());
        verify(repository, never()).countByTraceId(any());
    }

    @Test
    void completeLastRunResolves() {
        RetentionMonitor monitor = newMonitor();
        Instant now = BOOT.plus(Duration.ofDays(5));
        clock.set(now);
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH))
                .willReturn(Optional.of(batchRow("t1", now.minus(Duration.ofHours(3)))));
        given(repository.countByTraceId("t1")).willReturn(TARGETS);

        monitor.check();

        verify(alertSender).resolve(eq(RetentionMonitor.ALERT_KEY), anyString());
        verify(alertSender, never()).fire(any(), any());
    }

    @Test
    void incompleteLastRunIsAlerted() {
        RetentionMonitor monitor = newMonitor();
        Instant now = BOOT.plus(Duration.ofDays(5));
        clock.set(now);
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH))
                .willReturn(Optional.of(batchRow("t1", now.minus(Duration.ofHours(3)))));
        given(repository.countByTraceId("t1")).willReturn(TARGETS - 3);

        monitor.check();

        verify(alertSender).fire(RetentionMonitor.ALERT_KEY,
                "보관기간 파기 배치 기록 누락: " + (TARGETS - 3) + "/" + TARGETS + "개 테이블");
    }

    /** 배치가 도는 중에 보면 개수가 모자라 보입니다 — 판정을 미룹니다 */
    @Test
    void runInProgressIsNotJudged() {
        RetentionMonitor monitor = newMonitor();
        Instant now = BOOT.plus(Duration.ofDays(5));
        clock.set(now);
        given(repository.findTopByTriggerTypeOrderByCreatedAtDesc(BATCH))
                .willReturn(Optional.of(batchRow("t1", now.minus(Duration.ofMinutes(20)))));

        monitor.check();

        verify(repository, never()).countByTraceId(any());
        verifyNoInteractions(alertSender);
    }

    @Test
    void windowShorterThan25HoursIsRejected() {
        assertThatThrownBy(() ->
                new RetentionMonitor(repository, alertSender, clock, Duration.ofHours(24)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 테스트 안에서 시각을 옮기기 위한 시계 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}