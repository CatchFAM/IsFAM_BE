package com.isfam.domain.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.voice.entity.VoiceprintSample;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * {@code /me/voiceprint} 의 요청·응답.
 *
 * <p><b>오디오 원본은 이 API 로 오지 않습니다.</b> 앱이 ONNX 로 임베딩을 추출해
 * base64 로 보내고, 요청은 {@code application/json} 입니다(명세 [정책]).
 * multipart 로 바꾸고 싶어지면 legal-compliance.md 3장을 먼저 읽으세요 —
 * 등록용 음성이 서버에 도달하는 순간 딥보이스 판정과 같은 의무가 붙습니다.
 */
public final class VoiceprintDto {

    private VoiceprintDto() {
    }

    // ── 요청 ────────────────────────────────────────────────

    /**
     * 앱이 계산한 음질 지표.
     *
     * <p>{@code is_analyzable} 만 필수입니다. 나머지는 앱 버전에 따라 빠질 수 있어
     * null 을 허용하고, 없는 지표는 판정에서 중립으로 취급합니다
     * ({@code VoiceQualityPolicy}). 전부 필수로 걸면 구버전 앱의 등록이 통째로 막힙니다.
     */
    public record AudioQuality(
            @NotNull(message = "음질 판정 결과가 필요해요")
            Boolean isAnalyzable,

            Double durationSeconds,
            Double rmsEnergy,
            Double peakAmplitude,
            Double speechRatio
    ) {
    }

    /**
     * {@code embedding} 은 base64 로 감싼 float32[192](= 768바이트)입니다.
     * 길이가 다르거나 디코딩이 안 되면 VOICE_001(422)입니다.
     */
    public record RegisterRequest(
            @NotNull(message = "문장 번호가 필요해요")
            @Positive(message = "문장 번호가 올바르지 않아요")
            Integer sentenceId,

            @NotBlank(message = "임베딩이 필요해요")
            String embedding,

            @NotBlank(message = "모델 버전이 필요해요")
            String embeddingModelVersion,

            @NotNull(message = "음질 정보가 필요해요")
            @Valid
            AudioQuality audioQuality
    ) {
    }

    // ── 응답 ────────────────────────────────────────────────

    /** POST /me/voiceprint */
    public record RegisterResponse(
            boolean voiceprintRegistered,
            String embeddingModelVersion,
            int sampleCount,
            Instant registeredAt
    ) {
    }

    /**
     * GET /me/voiceprint.
     *
     * <p>{@code @JsonInclude(ALWAYS)} 인 이유는 전역 설정이 {@code non_null} 이라
     * 미등록 상태의 {@code embedding_model_version} · {@code registered_at} 이
     * 응답에서 통째로 사라지기 때문입니다. 앱이 키 존재 여부로 분기하면 그때 깨집니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StatusResponse(
            boolean voiceprintRegistered,
            String embeddingModelVersion,
            int sampleCount,
            List<SampleItem> samples,
            Instant registeredAt
    ) {

        /** 아직 한 번도 등록하지 않은 사용자 */
        public static StatusResponse notRegistered() {
            return new StatusResponse(false, null, 0, List.of(), null);
        }
    }

    /** {@code quality="review"} 인 샘플은 앱이 재녹음을 유도합니다 */
    public record SampleItem(
            int sentenceId,
            VoiceprintSample.Quality quality
    ) {

        public static SampleItem from(VoiceprintSample sample) {
            return new SampleItem(sample.getSentenceId(), sample.getQuality());
        }
    }
}