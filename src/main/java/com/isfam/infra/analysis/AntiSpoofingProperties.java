package com.isfam.infra.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * FastAPI 딥보이스 분석 서버 접속 설정.
 *
 * <p><b>{@code base-url} 은 반드시 설정으로 남겨둡니다.</b> 분석 서버는 로컬·스테이징·
 * 운영에서 위치가 다르고, 외부 인프라에 두면 처리 위탁 계약 대상이 됩니다
 * (legal-compliance.md 9장 미완 항목). 코드에 박아두면 그때 재배포가 필요합니다.
 *
 * <p><b>사설망이 아니면 이 URL 은 https 여야 합니다</b>(4장 ① — 서버 내부 구간도 TLS).
 * 통화 음성이 지나가는 구간이라 평문이면 그 자체가 위반입니다.
 *
 * @param baseUrl        예: {@code http://localhost:8000}
 * @param detectPath     딥보이스 판정 경로. <b>{@code /voice/verify} 를 넣지 마세요</b> —
 *                       가족 대조가 포함된 엔드포인트라 호출하는 순간 발신자 식별이 됩니다
 *                       (legal-compliance.md 5장)
 * @param connectTimeout 분석 서버가 죽었을 때 앱을 오래 붙잡지 않게 짧게
 * @param readTimeout    30초 오디오 추론 시간(수백 ms) + 여유. 너무 짧으면 정상 분석이 502 가 됩니다
 */
@ConfigurationProperties(prefix = "isfam.fastapi")
public record AntiSpoofingProperties(
        String baseUrl,
        String detectPath,
        Duration connectTimeout,
        Duration readTimeout
) {

    private static final String DEFAULT_DETECT_PATH = "/api/v1/anti-spoofing/detect";

    public AntiSpoofingProperties {
        if (detectPath == null || detectPath.isBlank()) {
            detectPath = DEFAULT_DETECT_PATH;
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(20);
        }
    }
}