package com.isfam.infra.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code POST /api/v1/anti-spoofing/detect} 의 응답.
 *
 * <pre>
 * { "analysis_status": "complete", "is_spoofed": false, "spoof_score": 0.2817,
 *   "threshold": 0.5, "predicted_label": "real", "model_name": "...",
 *   "analyzed_segments": 2, "purged": true, "audio_quality": { ... } }
 * </pre>
 *
 * <p><b>모든 필드에 {@code @JsonProperty} 로 이름을 명시한 건 필수입니다.</b>
 * 이 응답을 읽는 {@code RestClient} 는 {@code RestClient.builder()} 로 직접 만들어져
 * Boot 의 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정을 받지 않습니다
 * ({@link AntiSpoofingConfig} 참고). 이름을 빼면 {@code analysis_status} 가
 * {@code analysisStatus} 에 붙지 않아 <b>전 필드가 null 로 들어오고</b>,
 * {@link #isUsable()} 이 false 라 모든 판정이 502 로 떨어집니다 —
 * 예외가 나지 않아서 원인을 찾기 어려운 종류의 고장입니다.
 *
 * <p>{@code com.fasterxml.jackson.annotation} 패키지는 Jackson 3 에서도 그대로라
 * Boot 4 에서도 안전합니다. 옮겨간 건 {@code databind} 쪽입니다.
 *
 * <p><b>{@code audio_quality} 만 nullable 이고 나머지는 항상 옵니다.</b>
 * 그래도 전부 래퍼 타입으로 받는 이유는 FastAPI 가 버전업으로 필드를 빼거나
 * 프록시가 응답을 잘랐을 때 {@code NullPointerException} 대신
 * 판정 불가로 다루기 위해서입니다 — 여기서 NPE 가 나면 tmpfs 파기는 이미 끝난 뒤라
 * 사용자에게는 500 만 남습니다.
 *
 * <p>{@code label_scores}·{@code max_spoof_segment_index}·{@code processing_time_ms} 등은
 * 매핑하지 않습니다. {@code ignoreUnknown = true} 로 흘려보냅니다 —
 * 안 쓰는 값을 받아두면 언젠가 DB 에 저장하고 싶어집니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AntiSpoofingResponse(

        /* "complete" | "more_voice_required" */
        @JsonProperty("analysis_status")
        String analysisStatus,

        @JsonProperty("is_spoofed")
        Boolean isSpoofed,

        @JsonProperty("spoof_score")
        Double spoofScore,

        /* 하드코딩 금지 — 모델이 바뀌면 임계값도 바뀝니다 */
        @JsonProperty("threshold")
        Double threshold,

        @JsonProperty("predicted_label")
        String predictedLabel,

        @JsonProperty("predicted_score")
        Double predictedScore,

        @JsonProperty("message")
        String message,

        /* 예: "Vansh180/deepfake-audio-wav2vec2" */
        @JsonProperty("model_name")
        String modelName,

        @JsonProperty("analyzed_segments")
        Integer analyzedSegments,

        @JsonProperty("segment_seconds")
        Double segmentSeconds,

        @JsonProperty("audio_quality")
        AudioQuality audioQuality,

        /*
         * 분석 서버가 자기 임시 파일을 지웠는지 (P-23 · S3-④).
         *
         * spoof_checks.purged_at 은 Java 쪽 tmpfs 파일만 증명합니다. FastAPI 의
         * UploadFile 은 1MB 를 넘으면 디스크로 스풀되는데 30초 오디오가 정확히 그
         * 구간이라, 분석 서버에도 통화 음성이 남습니다.
         *
         * false 거나 null 이면 그쪽 파기를 증명할 수 없다는 뜻이고,
         * spoof_checks.remote_purged 로 그대로 남아 PurgeMonitor 가 5분마다 셉니다.
         *
         * null 은 "아직 이 필드를 안 보내는 버전"입니다. false 와 구별해 저장합니다 —
         * 배포 순서 때문에 생기는 공백을 파기 실패로 세면 진짜 실패가 묻힙니다.
         * 대신 FastAPI 배포가 끝난 뒤에도 null 이 계속 나오면 그건 확인해야 합니다.
         */
        @JsonProperty("purged")
        Boolean purged
) {

    /** 이 응답으로 판정 row 를 만들 수 있는지. 핵심 값이 하나라도 비면 신뢰할 수 없습니다 */
    public boolean isUsable() {
        return spoofScore != null && threshold != null && isSpoofed != null;
    }

    /**
     * 분석 서버 파기가 <b>확인되지 않은</b> 상태.
     *
     * <p>null(구버전)도 여기 포함됩니다 — 로그 경고용입니다.
     * DB 에는 null 과 false 를 구별해 넣습니다.
     */
    public boolean isRemotePurgeUnconfirmed() {
        return !Boolean.TRUE.equals(purged);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioQuality(

            @JsonProperty("is_analyzable")
            Boolean isAnalyzable,

            @JsonProperty("message")
            String message,

            @JsonProperty("duration_seconds")
            Double durationSeconds,

            @JsonProperty("rms_energy")
            Double rmsEnergy,

            @JsonProperty("peak_amplitude")
            Double peakAmplitude,

            @JsonProperty("speech_ratio")
            Double speechRatio
    ) {
    }
}