package com.isfam.common.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Slack Incoming Webhook 발송 (P-21).
 *
 * <p><b>Webhook URL 이 비어 있으면 아무것도 보내지 않습니다.</b> Phase 11 의 기본
 * 상태가 그것입니다 — 알람 코드는 지금 짜두고 수신처는 Phase 15 에서 붙입니다.
 * 미설정 경고는 기동 후 한 번만 남깁니다. 매 점검마다 WARN 이 찍히면 정작 봐야 할
 * ERROR 가 묻힙니다.
 *
 * <p><b>발송 여부와 무관하게 로그에는 항상 남깁니다.</b> Slack 이 죽어도, 네트워크가
 * 막혀도 기록은 남아야 합니다. 알람은 관측을 빠르게 하는 수단이지 유일한 증빙이
 * 아닙니다 — 증빙은 {@code spoof_checks.purged_at} 과 이 로그입니다.
 *
 * <p><b>이 클래스가 서울 리전을 벗어나는 유일한 경로입니다.</b> 인프라 설계 계획의
 * 다이어그램에서 리전 밖으로 나가는 선이 이것 하나뿐입니다. 그래서 {@code summary} 에
 * 개인정보가 들어가면 곧바로 고지 없는 국외 이전이 됩니다 — {@link AlertSender} 의
 * 주의사항을 반드시 지키세요.
 *
 * <p><b>{@code RestClient.builder()} 를 직접 부릅니다.</b> {@code AntiSpoofingConfig}
 * 와 같은 이유입니다 — Boot 4 의 {@code spring-boot-starter-webmvc} 만으로는
 * {@code RestClient.Builder} 빈이 등록되지 않습니다.
 *
 * <p>JSON 은 손으로 만듭니다. 보내는 필드가 {@code text} 하나뿐이라
 * Jackson 2/3 패키지 이동에 묶일 이유가 없습니다.
 */
@Slf4j
@Component
public class SlackAlertSender implements AlertSender {

    /** Slack 메시지 상한은 40,000자지만, 알람은 한 줄이어야 읽힙니다 */
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final AlertProperties properties;
    private final Clock clock;
    private final RestClient restClient;

    /** 미설정 경고는 1회만 */
    private final AtomicBoolean unconfiguredWarned = new AtomicBoolean(false);

    /** 현재 이상 상태인 key. {@link #resolve} 가 "올린 적 있는 알람"만 내리도록 */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /**
     * key 별 마지막 <b>발송 성공</b> 시각.
     * 발송 실패는 기록하지 않아 다음 주기에 다시 시도합니다.
     */
    private final Map<String, Instant> lastNotifiedAt = new ConcurrentHashMap<>();

    public SlackAlertSender(AlertProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void fire(String key, String summary) {
        // 발송 여부와 무관하게 먼저 기록합니다
        log.error("[ALERT] {} — {}", key, summary);

        boolean firstTime = active.add(key);

        if (!isConfigured()) {
            return;
        }

        Instant now = clock.instant();
        Instant last = lastNotifiedAt.get(key);
        boolean due = firstTime || last == null
                || now.isAfter(last.plus(properties.repeatInterval()));
        if (!due) {
            return;
        }

        if (post(":rotating_light: *" + key + "*\n" + truncate(summary))) {
            lastNotifiedAt.put(key, now);
        }
    }

    @Override
    public void resolve(String key, String summary) {
        if (!active.remove(key)) {
            // 한 번도 이상이 아니었던 key. 기동할 때마다 "정상" 알람을 보내지 않습니다
            return;
        }
        lastNotifiedAt.remove(key);
        log.info("[ALERT RESOLVED] {} — {}", key, summary);

        if (isConfigured()) {
            post(":white_check_mark: *" + key + "* 해소\n" + truncate(summary));
        }
    }

    // ── 내부 ────────────────────────────────────────────────

    private boolean isConfigured() {
        if (properties.isConfigured()) {
            return true;
        }
        if (unconfiguredWarned.compareAndSet(false, true)) {
            log.warn("isfam.alert.slack-webhook-url 이 비어 있어 알람을 발송하지 않습니다. "
                    + "이상 상태는 ERROR 로그와 GET /actuator/health 로만 확인됩니다 "
                    + "(Phase 15 에서 연결)");
        }
        return false;
    }

    /**
     * @return 발송 성공 여부. <b>예외를 밖으로 던지지 않습니다</b> —
     * 알람 발송 실패가 스케줄러를 멈추면 감시 자체가 사라집니다
     */
    private boolean post(String text) {
        try {
            restClient.post()
                    .uri(properties.slackWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"text\":\"" + escape(text) + "\"}")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // Webhook URL 은 비밀값입니다. 예외 메시지에 섞여 나올 수 있어 클래스명만 남깁니다
            log.error("알람 발송 실패 ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private String truncate(String summary) {
        if (summary == null) {
            return "";
        }
        return summary.length() <= MAX_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_SUMMARY_LENGTH) + "...(생략)";
    }

    private String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}