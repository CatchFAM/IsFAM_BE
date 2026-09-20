package com.isfam.common.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 운영 알람 설정.
 *
 * <p>Phase 11 에서는 {@code slack-webhook-url} 을 비워둡니다. 미설정이면
 * {@link SlackAlertSender} 가 발송하지 않고 WARN 을 한 번만 남깁니다
 * (인프라 설계 계획 — 착수 시점).
 *
 * @param slackWebhookUrl Slack Incoming Webhook. 비어 있으면 발송하지 않습니다.
 *                        <b>비밀값이라 환경변수로만 주입합니다</b> — git 에 커밋 금지
 * @param repeatInterval  같은 key 의 재발송 주기. 5분 주기 점검이 이상을 계속 잡아내도
 *                        알람은 이 간격으로만 나갑니다. 짧으면 사람이 무시하게 되고,
 *                        길면 교대 근무 사이에 묻힙니다
 * @param timeout         connect · read 공통. 알람 때문에 스케줄러가 오래 잡히면 안 됩니다
 */
@ConfigurationProperties(prefix = "isfam.alert")
public record AlertProperties(
        String slackWebhookUrl,
        Duration repeatInterval,
        Duration timeout
) {

    public AlertProperties {
        if (repeatInterval == null || repeatInterval.isNegative() || repeatInterval.isZero()) {
            repeatInterval = Duration.ofHours(1);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(3);
        }
    }

    public boolean isConfigured() {
        return slackWebhookUrl != null && !slackWebhookUrl.isBlank();
    }
}