package com.isfam.domain.retention.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/**
 * 보관 기간과 파기 배치 설정 (P-03).
 *
 * <p><b>값의 출처는 legal-compliance.md 4장 "보관 및 파기" 입니다.</b> 여기 기본값은 그 표를
 * 옮긴 것이고, 기간을 바꿀 때는 문서를 먼저 고친 뒤 이 값을 맞춥니다.
 *
 * <p><b>설정으로 뺀 이유</b>는 값의 성격이 섞여 있기 때문입니다. 접속기록 2년은 고시가 정한
 * 최소치이고, 나머지는 서비스 판단값이라 변호사 검토 뒤 바뀔 수 있습니다. 코드를 다시
 * 배포하지 않고 조정할 수 있어야 합니다.
 *
 * <p><b>대신 법정 최소치 아래로는 내릴 수 없습니다</b> (원칙 R-7). 접속기록을 730일 미만으로
 * 적으면 기동을 거부합니다. {@code EmbeddingCipher} 가 키 없이 뜨지 않는 것과 같은 이유 —
 * 운영 중에 발견되면 이미 지워진 뒤라 되돌릴 수 없습니다.
 *
 * <pre>
 * isfam.retention.cron=0 10 4 * * *
 * isfam.retention.zone=Asia/Seoul
 * isfam.retention.chunk-size=1000
 * isfam.retention.max-chunks-per-run=100
 * isfam.retention.days.phone-verifications=30
 * ...
 * </pre>
 *
 * @param cron            실행 시각. {@code -} 이면 Spring 이 스케줄을 끕니다 —
 *                        끄면 파기 의무 위반 상태가 되므로 로컬 외에는 쓰지 마세요
 * @param zone            {@code cron} 을 해석할 시간대. 서버 기본값(UTC)에 기대지 않습니다
 * @param chunkSize       DELETE 한 문장이 지우는 최대 행 수. 대량 DELETE 로 락과 WAL 이
 *                        한꺼번에 몰리지 않게 나눕니다
 * @param maxChunksPerRun 테이블당 한 번 실행의 청크 상한. 밀린 분량이 많아도 한 트랜잭션이
 *                        무한정 커지지 않게 합니다. 상한에 걸리면 알람(5일 내 파기 요건)
 * @param days            테이블별 보관 기간(일)
 */
@ConfigurationProperties(prefix = "isfam.retention")
public record RetentionProperties(
        String cron,
        String zone,
        Integer chunkSize,
        Integer maxChunksPerRun,
        Days days
) {

    /** 안전성 확보조치 기준 제8조 — 민감정보 처리 시스템 접속기록 2년 이상 */
    public static final int LEGAL_MINIMUM_ACCESS_LOG_DAYS = 730;

    static final String DEFAULT_CRON = "0 10 4 * * *";
    static final String DEFAULT_ZONE = "Asia/Seoul";
    static final int DEFAULT_CHUNK_SIZE = 1_000;
    static final int DEFAULT_MAX_CHUNKS_PER_RUN = 100;

    /** 한 문장이 이보다 크면 청크로 나눈 의미가 없습니다 */
    static final int MAX_CHUNK_SIZE = 10_000;

    public RetentionProperties {
        if (cron == null || cron.isBlank()) {
            cron = DEFAULT_CRON;
        }
        if (zone == null || zone.isBlank()) {
            zone = DEFAULT_ZONE;
        }
        ZoneId.of(zone); // 오타면 여기서 기동 실패 — 새벽 4시가 오후 1시가 되는 걸 막습니다

        if (chunkSize == null) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        if (chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "isfam.retention.chunk-size 는 1~" + MAX_CHUNK_SIZE + " 이어야 합니다: " + chunkSize);
        }
        if (maxChunksPerRun == null) {
            maxChunksPerRun = DEFAULT_MAX_CHUNKS_PER_RUN;
        }
        if (maxChunksPerRun < 1) {
            throw new IllegalStateException(
                    "isfam.retention.max-chunks-per-run 은 1 이상이어야 합니다: " + maxChunksPerRun);
        }
        if (days == null) {
            days = new Days(null, null, null, null, null, null, null, null);
        }
    }

    /** 대상 테이블의 보관 기간(일) */
    public int daysFor(RetentionTarget target) {
        return days.asMap().get(target);
    }

    /**
     * 테이블별 보관 기간(일).
     *
     * <p>필드를 대상마다 따로 둔 이유는 설정 파일에서 <b>무엇이 몇 일인지 한눈에</b> 보여야
     * 하기 때문입니다. 맵 하나로 받으면 키 오타가 조용히 기본값으로 떨어집니다.
     */
    public record Days(
            Integer phoneVerifications,
            Integer refreshTokens,
            Integer notifications,
            Integer callEvents,
            Integer voiceAnalyses,
            Integer spoofChecks,
            Integer embeddingAccessLogs,
            Integer voiceprintAccessLogs
    ) {

        public Days {
            phoneVerifications = orDefault(phoneVerifications, 30);
            refreshTokens = orDefault(refreshTokens, 90);
            notifications = orDefault(notifications, 90);
            callEvents = orDefault(callEvents, 365);
            voiceAnalyses = orDefault(voiceAnalyses, 365);
            spoofChecks = orDefault(spoofChecks, LEGAL_MINIMUM_ACCESS_LOG_DAYS);
            embeddingAccessLogs = orDefault(embeddingAccessLogs, LEGAL_MINIMUM_ACCESS_LOG_DAYS);
            voiceprintAccessLogs = orDefault(voiceprintAccessLogs, LEGAL_MINIMUM_ACCESS_LOG_DAYS);

            validate(Map.of(
                    RetentionTarget.PHONE_VERIFICATIONS, phoneVerifications,
                    RetentionTarget.REFRESH_TOKENS, refreshTokens,
                    RetentionTarget.NOTIFICATIONS, notifications,
                    RetentionTarget.CALL_EVENTS, callEvents,
                    RetentionTarget.VOICE_ANALYSES, voiceAnalyses,
                    RetentionTarget.SPOOF_CHECKS, spoofChecks,
                    RetentionTarget.EMBEDDING_ACCESS_LOGS, embeddingAccessLogs,
                    RetentionTarget.VOICEPRINT_ACCESS_LOGS, voiceprintAccessLogs,
                    RetentionTarget.RETIRED_SPOOF_CHECKS, spoofChecks,
                    RetentionTarget.RETIRED_VOICEPRINT_ACCESS_LOGS, voiceprintAccessLogs));
        }

        /**
         * 대상 → 기간. {@link RetentionTarget} 에 값이 늘었는데 여기 필드가 없으면
         * {@link #daysFor} 가 NPE 로 터집니다 — 조용히 빠지는 것보다 낫습니다.
         * {@code RetentionPropertiesTest} 가 전 대상을 확인합니다.
         */
        Map<RetentionTarget, Integer> asMap() {
            Map<RetentionTarget, Integer> map = new EnumMap<>(RetentionTarget.class);
            map.put(RetentionTarget.PHONE_VERIFICATIONS, phoneVerifications);
            map.put(RetentionTarget.REFRESH_TOKENS, refreshTokens);
            map.put(RetentionTarget.NOTIFICATIONS, notifications);
            map.put(RetentionTarget.CALL_EVENTS, callEvents);
            map.put(RetentionTarget.VOICE_ANALYSES, voiceAnalyses);
            map.put(RetentionTarget.SPOOF_CHECKS, spoofChecks);
            map.put(RetentionTarget.EMBEDDING_ACCESS_LOGS, embeddingAccessLogs);
            map.put(RetentionTarget.VOICEPRINT_ACCESS_LOGS, voiceprintAccessLogs);
            // 탈퇴자 분리 보관분은 원본과 같은 기간 (Phase 13-4)
            map.put(RetentionTarget.RETIRED_SPOOF_CHECKS, spoofChecks);
            map.put(RetentionTarget.RETIRED_VOICEPRINT_ACCESS_LOGS, voiceprintAccessLogs);
            return map;
        }

        private static Integer orDefault(Integer value, int fallback) {
            return value == null ? fallback : value;
        }

        private static void validate(Map<RetentionTarget, Integer> values) {
            values.forEach((target, value) -> {
                if (value < 1) {
                    throw new IllegalStateException(
                            "보관 기간은 1일 이상이어야 합니다: " + target.table() + "=" + value);
                }
                if (target.isAccessLog() && value < LEGAL_MINIMUM_ACCESS_LOG_DAYS) {
                    throw new IllegalStateException("""
                            접속기록 보관 기간을 법정 최소치 아래로 설정할 수 없습니다.
                              대상:   %s
                              설정값: %d일
                              최소:   %d일 (안전성 확보조치 기준 제8조 — 민감정보 처리 시 2년 이상)"""
                            .formatted(target.table(), value, LEGAL_MINIMUM_ACCESS_LOG_DAYS));
                }
            });
        }
    }
}