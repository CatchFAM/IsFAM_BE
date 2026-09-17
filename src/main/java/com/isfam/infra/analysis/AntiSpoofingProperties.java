package com.isfam.infra.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * FastAPI 딥보이스 분석 서버 접속 설정.
 *
 * <p>{@code base-url} 의 전송 구간 검사는 {@link AnalysisEndpointGuard} 가
 * 운영 기동 시점에 수행합니다(P-25).
 *
 * @param baseUrl        예: {@code http://localhost:8000}
 * @param detectPath     딥보이스 판정 경로. 화자 대조 경로는 기동 거부 (P-26)
 * @param connectTimeout 분석 서버가 죽었을 때 앱을 오래 붙잡지 않게 짧게
 * @param readTimeout    30초 오디오 추론 시간(수백 ms) + 여유
 */
@ConfigurationProperties(prefix = "isfam.fastapi")
public record AntiSpoofingProperties(
        String baseUrl,
        String detectPath,
        Duration connectTimeout,
        Duration readTimeout
) {

    private static final String DEFAULT_DETECT_PATH = "/api/v1/anti-spoofing/detect";

    /** 정확 일치로 막으면 FastAPI 가 경로명을 바꿨을 때 그냥 통과합니다 */
    private static final List<String> FORBIDDEN_PATH_TOKENS =
            List.of("verify", "identify", "speaker", "embedding", "match", "compare", "similarity");

    public AntiSpoofingProperties {
        if (detectPath == null || detectPath.isBlank()) {
            detectPath = DEFAULT_DETECT_PATH;
        }
        requireDetectionOnlyPath(detectPath);

        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(20);
        }
    }

    /**
     * 화자 대조 경로면 기동을 거부합니다.
     *
     * <p>서버는 합성 판별만, 화자 대조는 단말에서만 합니다. 코드에도 DB 에도 대조 수단이
     * 없지만 이 경로만 설정값이라 그 통제를 통째로 우회할 수 있습니다. 대조 엔드포인트를
     * 호출하면 통화 상대방의 생체정보를 동의 없이 처리하게 됩니다
     * (legal-compliance.md L7-d · 제23조).
     *
     * <p>로컬에서 호출해도 같은 처리가 일어나므로 전 프로파일에 적용합니다.
     */
    private static void requireDetectionOnlyPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);

        for (String token : FORBIDDEN_PATH_TOKENS) {
            if (normalized.contains(token)) {
                throw new IllegalStateException("""
                        isfam.fastapi.detect-path 에 화자 식별 경로를 지정할 수 없습니다.
                          설정값: %s
                          금지 조각: %s
                          기본값: %s"""
                        .formatted(path, token, DEFAULT_DETECT_PATH));
            }
        }
    }
}