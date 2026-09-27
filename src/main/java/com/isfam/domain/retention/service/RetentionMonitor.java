package com.isfam.domain.retention.service;

import com.isfam.common.alert.AlertSender;
import com.isfam.domain.audit.entity.DataDeletionLog;
import com.isfam.domain.audit.repository.DataDeletionLogRepository;
import com.isfam.domain.retention.config.RetentionTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 보관기간 파기 배치 감시 (P-03).
 *
 * <p><b>배치가 멈추면 아무 일도 일어나지 않습니다.</b> 실패는 배치가 스스로 알람을 보내지만,
 * 스케줄이 꺼지거나({@code cron=-}) 스레드가 죽으면 알람을 보낼 주체가 없습니다. 이 클래스가
 * 그 빈자리를 봅니다 — Phase 11 에서 {@code countByPurgedAtIsNull()} 을 아무도 부르지
 * 않았던 것(S3-c)과 같은 구멍입니다.
 *
 * <p>1시간마다 마지막 배치 실행을 확인합니다.
 * <ul>
 *   <li><b>정지</b> — 마지막 {@code RETENTION_BATCH} 기록이 {@code window}(기본 26시간)보다 오래됨</li>
 *   <li><b>누락</b> — 마지막 실행의 기록이 대상 테이블 수보다 적음.
 *       배치는 성공·실패 모두 테이블당 1행을 남기므로, 적으면 실패 기록마저 못 남긴 테이블이 있음</li>
 * </ul>
 *
 * <p><b>탈퇴 기록은 보지 않습니다.</b> 전체 건수로 보면 배치가 멈춰도 탈퇴가 있는 날은
 * 정상처럼 보입니다.
 *
 * <p><b>헬스체크에는 붙이지 않습니다.</b> 파기 배치가 하루 밀렸다고 서비스를 내릴 이유는
 * 없습니다. 알람만 보냅니다.
 */
@Slf4j
@Component
public class RetentionMonitor {

    public static final String ALERT_KEY = "retention.batch.missing";

    /** 일 1회 배치 + 실행 시간 여유. 이보다 짧으면 정상 주기에도 알람이 울립니다 */
    private static final Duration MIN_WINDOW = Duration.ofHours(25);

    /**
     * 마지막 기록이 이보다 최근이면 아직 실행 중일 수 있어 누락 판정을 미룹니다.
     * 배치는 테이블을 하나씩 처리하며 기록하므로, 도는 중에 보면 개수가 모자랍니다.
     */
    private static final Duration SETTLE = Duration.ofHours(1);

    private final DataDeletionLogRepository repository;
    private final AlertSender alertSender;
    private final Clock clock;
    private final Duration window;
    private final Instant bootedAt;

    public RetentionMonitor(DataDeletionLogRepository repository,
                            AlertSender alertSender,
                            Clock clock,
                            @Value("${isfam.retention.monitor-window:PT26H}") Duration window) {
        if (window.compareTo(MIN_WINDOW) < 0) {
            throw new IllegalStateException(
                    "isfam.retention.monitor-window 는 " + MIN_WINDOW + " 이상이어야 합니다: " + window);
        }
        this.repository = repository;
        this.alertSender = alertSender;
        this.clock = clock;
        this.window = window;
        this.bootedAt = clock.instant();
    }

    @Scheduled(
            fixedDelayString = "${isfam.retention.monitor-interval:PT1H}",
            initialDelayString = "${isfam.retention.monitor-initial-delay:PT10M}")
    public void check() {
        Instant now = clock.instant();

        Optional<DataDeletionLog> latest =
                repository.findTopByTriggerTypeOrderByCreatedAtDesc(DataDeletionLog.TriggerType.RETENTION_BATCH);

        if (latest.isEmpty() || latest.get().getCreatedAt().isBefore(now.minus(window))) {
            // 기동 후 window 가 안 지났으면 첫 실행 전일 수 있습니다 — 배포 직후 오탐 방지
            if (now.isBefore(bootedAt.plus(window))) {
                log.debug("파기 배치 감시 — 기동 후 {} 이내, 판정 보류", window);
                return;
            }
            alertSender.fire(ALERT_KEY,
                    "보관기간 파기 배치가 " + window.toHours() + "시간 동안 실행되지 않았습니다");
            return;
        }

        DataDeletionLog last = latest.get();
        if (last.getCreatedAt().isAfter(now.minus(SETTLE))) {
            log.debug("파기 배치 감시 — 최근 실행이 진행 중일 수 있어 판정 보류");
            return;
        }

        long expected = RetentionTarget.values().length;
        long recorded = repository.countByTraceId(last.getTraceId());

        if (recorded < expected) {
            alertSender.fire(ALERT_KEY,
                    "보관기간 파기 배치 기록 누락: " + recorded + "/" + expected + "개 테이블");
            return;
        }

        alertSender.resolve(ALERT_KEY, "보관기간 파기 배치 정상");
    }
}