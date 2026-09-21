package com.isfam.infra.analysis;

import com.isfam.common.alert.AlertSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * tmpfs 잔여 통화 음성 청소 (P-22 · S3-③).
 *
 * <p>{@code SpoofCheckService} 의 {@code finally} 는 프로세스가 살아 있을 때만 돕니다.
 * OOM · SIGKILL · 컨테이너 강제 종료면 그 블록은 실행되지 않고 통화 음성이 tmpfs 에
 * 그대로 남습니다. 두 개의 그물을 칩니다.
 *
 * <ol>
 *   <li><b>기동 시 전량 청소</b> — 이전 프로세스가 남긴 것은 전부 고아입니다.
 *       잔여 건수는 ERROR 로 남깁니다. "재기동했더니 조용히 깨끗해졌다"가 되면
 *       사고가 있었다는 사실 자체가 사라집니다</li>
 *   <li><b>10분 주기 고아 파일 스위퍼</b> — 살아 있는 동안의 안전망</li>
 * </ol>
 *
 * <p><b>기동 청소를 {@code @PostConstruct} 에서 하는 이유</b>는 웹 서버가 요청을 받기
 * 전이어야 하기 때문입니다. {@code ApplicationReadyEvent} 는 이미 포트가 열린 뒤라,
 * 그 사이에 들어온 업로드 파일을 지울 수 있습니다.
 *
 * <p><b>주기 청소는 파일 나이를 봅니다.</b> 분석 중인 파일을 지우면 그 요청은 502 로
 * 떨어지고, 더 나쁘게는 "파기했다"는 기록 없이 파일이 사라집니다.
 * {@code orphan-age} 가 분석 read-timeout 보다 짧으면 기동을 거부합니다 —
 * 설정 한 줄로 안전망이 흉기가 되는 걸 막습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioTempSweeper {

    /** 인프라 설계 계획의 대시보드 지표 2 — tmpfs 잔여 파일. 0 이어야 하는 값입니다 */
    public static final String ALERT_KEY = "audio.tmpfs.orphan";

    /** read-timeout 에 더할 최소 여유. 이보다 짧게는 설정할 수 없습니다 */
    private static final Duration MIN_MARGIN_OVER_READ_TIMEOUT = Duration.ofMinutes(1);

    private final AudioTempStorage storage;
    private final AudioTempProperties audioProperties;
    private final AntiSpoofingProperties analysisProperties;
    private final AlertSender alertSender;
    private final Clock clock;

    /** 마지막 스위핑에서 처리한 파일 수. 부하 테스트 확인·Phase 15 대시보드용 */
    private final AtomicInteger lastSweepTotal = new AtomicInteger(0);

    /**
     * 기동 검증 + 전량 청소.
     *
     * <p>{@link AudioTempStorage} 를 주입받으므로 그쪽 {@code @PostConstruct}
     * (디렉터리 생성)가 먼저 끝난 뒤에 호출됩니다.
     */
    @PostConstruct
    void cleanOnStartup() {
        requireSafeOrphanAge();

        AudioTempStorage.Sweep sweep = storage.purgeAll();

        if (sweep.isClean()) {
            log.info("기동 시 tmpfs 잔여 통화 음성 없음: {}", storage.root().toAbsolutePath());
            return;
        }

        // 파일명은 UUID 라 개인정보가 아니지만, 건수만으로 충분하므로 목록은 남기지 않습니다
        log.error("!!! 기동 시 tmpfs 에 통화 음성이 남아 있었습니다 — "
                        + "직전 프로세스가 비정상 종료된 것으로 보입니다. "
                        + "삭제={}, 삭제실패={}, 디렉터리={}",
                sweep.deleted(), sweep.failed(), storage.root().toAbsolutePath());

        alertSender.fire(ALERT_KEY,
                "기동 시 tmpfs 잔여 통화 음성 " + sweep.total() + "건 "
                        + "(삭제 " + sweep.deleted() + " / 실패 " + sweep.failed() + ")");
    }

    /**
     * 10분 주기 고아 파일 스위퍼.
     *
     * <p>{@code initial-delay} 는 기동 청소와 겹치지 않게 하려는 것입니다.
     */
    @Scheduled(
            fixedDelayString = "${isfam.audio.sweep-interval:PT10M}",
            initialDelayString = "${isfam.audio.sweep-initial-delay:PT2M}")
    public void sweepOrphans() {
        Instant cutoff = clock.instant().minus(audioProperties.orphanAge());
        AudioTempStorage.Sweep sweep = storage.purgeOlderThan(cutoff);

        lastSweepTotal.set(sweep.total());

        if (sweep.isClean()) {
            // 정상은 조용해야 합니다. 10분마다 INFO 가 쌓이면 로그에서 이상을 못 찾습니다
            log.debug("tmpfs 스위퍼 — 잔여 없음");
            alertSender.resolve(ALERT_KEY, "tmpfs 잔여 통화 음성 없음");
            return;
        }

        log.error("!!! tmpfs 고아 통화 음성 발견 — finally 가 실행되지 않은 요청이 있습니다. "
                        + "삭제={}, 삭제실패={}, 기준={}초 경과",
                sweep.deleted(), sweep.failed(), audioProperties.orphanAge().toSeconds());

        alertSender.fire(ALERT_KEY,
                "tmpfs 고아 통화 음성 " + sweep.total() + "건 "
                        + "(삭제 " + sweep.deleted() + " / 실패 " + sweep.failed() + ")");
    }

    /** 부하 테스트 후 "tmp-dir 파일 0" 확인용 */
    public int lastSweepTotal() {
        return lastSweepTotal.get();
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * {@code orphan-age} 가 분석 왕복보다 짧으면 스위퍼가 분석 중인 파일을 지웁니다.
     * 기동 시점에 막습니다 — 운영 중에 발견되면 이미 몇 건은 사라진 뒤입니다.
     */
    private void requireSafeOrphanAge() {
        Duration minimum = analysisProperties.readTimeout().plus(MIN_MARGIN_OVER_READ_TIMEOUT);

        if (audioProperties.orphanAge().compareTo(minimum) < 0) {
            throw new IllegalStateException("""
                    isfam.audio.orphan-age 가 분석 왕복 시간보다 짧습니다.
                      orphan-age:   %s
                      read-timeout: %s
                      최소값:       %s (read-timeout + %s)

                    이대로 두면 스위퍼가 분석 중인 통화 음성을 지웁니다."""
                    .formatted(audioProperties.orphanAge(), analysisProperties.readTimeout(),
                            minimum, MIN_MARGIN_OVER_READ_TIMEOUT));
        }
    }
}