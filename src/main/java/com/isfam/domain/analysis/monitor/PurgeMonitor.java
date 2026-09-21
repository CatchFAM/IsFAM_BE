package com.isfam.domain.analysis.monitor;

import com.isfam.common.alert.AlertSender;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 파기 누락 감시 (P-21 · S3-①).
 *
 * <p>{@code countByPurgedAtIsNull()} 은 Phase 8 부터 있었지만 <b>호출하는 코드가
 * 어디에도 없었습니다.</b> 파기에 실패하면 {@code log.error} 한 줄이 전부였고, 그 로그를
 * 보는 사람도 없었습니다. 조사관이 "확인해 보셨습니까"라고 물었을 때 답할 게 없는
 * 상태가 이 클래스가 메우는 구멍입니다.
 *
 * <p>5분마다 두 가지를 셉니다.
 * <ul>
 *   <li>{@code purged_at IS NULL} — <b>우리 쪽</b> tmpfs 파일을 못 지운 건수</li>
 *   <li>{@code remote_purged = false} — <b>분석 서버 쪽</b> 임시 파일이 안 지워진 건수(P-23).
 *       {@code purged_at} 은 Java 쪽 파일만 증명하므로 이것만으로는 부족합니다</li>
 * </ul>
 *
 * <p><b>둘 다 항상 0 이어야 합니다.</b> 0 이 아니면 즉시 알람이고, 알람이 유실돼도
 * {@link PurgeHealthIndicator} 가 헬스체크를 DOWN 으로 떨어뜨립니다.
 *
 * <p><b>여기서 row 를 고치지 않습니다. 세기만 합니다.</b> {@code purged_at} 을 나중에
 * 채우는 코드가 생기면 그 컬럼은 더 이상 증빙이 아니게 됩니다 — 파일이 실제로
 * 지워졌는지 모르는 채로 "지웠다"고 적는 셈이라, 차라리 null 로 남는 편이 정직합니다.
 *
 * <p>조회 전용이라 {@code @Transactional} 을 걸지 않습니다. 두 번의 count 쿼리가
 * 한 스냅샷일 필요는 없습니다.
 */
@Slf4j
@Component
public class PurgeMonitor {

    /** 인프라 설계 계획의 대시보드 지표 1 — {@code purged_at IS NULL} 건수 */
    public static final String ALERT_KEY = "audio.purge.missing";

    /** 분석 서버 쪽 파기 실패 (P-23) */
    public static final String ALERT_KEY_REMOTE = "audio.purge.remote-missing";

    /** 아직 한 번도 점검하지 않은 상태 */
    private static final long NOT_CHECKED = -1L;

    /**
     * 이 배수만큼 점검이 밀리면 스케줄러가 죽은 것으로 봅니다.
     * "점검이 멈춘 것"과 "정상인 것"을 구별하지 못하면 감시가 의미를 잃습니다.
     */
    private static final int STALE_MULTIPLIER = 3;

    private final SpoofCheckRepository spoofCheckRepository;
    private final AlertSender alertSender;
    private final Clock clock;
    private final Duration interval;

    private volatile long unpurged = NOT_CHECKED;
    private volatile long remoteUnpurged = NOT_CHECKED;
    private volatile Instant checkedAt;

    public PurgeMonitor(SpoofCheckRepository spoofCheckRepository,
                        AlertSender alertSender,
                        Clock clock,
                        @Value("${isfam.compliance.purge-check-interval:PT5M}") Duration interval) {
        this.spoofCheckRepository = spoofCheckRepository;
        this.alertSender = alertSender;
        this.clock = clock;
        this.interval = interval;
    }

    @Scheduled(
            fixedDelayString = "${isfam.compliance.purge-check-interval:PT5M}",
            initialDelayString = "${isfam.compliance.purge-check-initial-delay:PT30S}")
    public void check() {
        long local = spoofCheckRepository.countByPurgedAtIsNull();
        long remote = spoofCheckRepository.countByRemotePurgedIsFalse();

        this.unpurged = local;
        this.remoteUnpurged = remote;
        this.checkedAt = clock.instant();

        if (local > 0) {
            alertSender.fire(ALERT_KEY,
                    "spoof_checks.purged_at IS NULL " + local
                            + "건 — 서버 tmpfs 에 통화 음성이 남아 있을 수 있습니다");
        } else {
            alertSender.resolve(ALERT_KEY, "spoof_checks.purged_at IS NULL 0건");
        }

        if (remote > 0) {
            alertSender.fire(ALERT_KEY_REMOTE,
                    "spoof_checks.remote_purged = false " + remote
                            + "건 — 분석 서버에 임시 파일이 남아 있을 수 있습니다");
        } else {
            alertSender.resolve(ALERT_KEY_REMOTE, "spoof_checks.remote_purged = false 0건");
        }

        if (local == 0 && remote == 0) {
            // 정상은 조용해야 합니다. 5분마다 INFO 가 쌓이면 로그에서 이상을 못 찾습니다
            log.debug("파기 점검 — 누락 없음");
        }
    }

    /** {@link PurgeHealthIndicator} 가 읽습니다 */
    public Snapshot snapshot() {
        return new Snapshot(unpurged, remoteUnpurged, checkedAt, isStale());
    }

    // ── 내부 ────────────────────────────────────────────────

    private boolean isStale() {
        Instant last = checkedAt;
        if (last == null) {
            // 기동 직후. initial-delay 안이면 정상이라 여기서는 stale 로 보지 않습니다
            return false;
        }
        return clock.instant().isAfter(last.plus(interval.multipliedBy(STALE_MULTIPLIER)));
    }

    /**
     * 점검 결과 한 장.
     *
     * @param unpurged       {@code purged_at IS NULL} 건수. {@code -1} 이면 아직 점검 전
     * @param remoteUnpurged {@code remote_purged = false} 건수. {@code -1} 이면 아직 점검 전
     * @param checkedAt      마지막 점검 시각. null 이면 아직 점검 전
     * @param stale          점검이 주기보다 한참 밀림 — 스케줄러가 멈췄다는 신호
     */
    public record Snapshot(long unpurged, long remoteUnpurged, Instant checkedAt, boolean stale) {

        public boolean isChecked() {
            return checkedAt != null;
        }

        public boolean isHealthy() {
            return isChecked() && !stale && unpurged == 0 && remoteUnpurged == 0;
        }
    }
}