package com.isfam.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.isfam.domain.analysis.entity.SpoofCheck;
import com.isfam.infra.analysis.AntiSpoofingResponse;

/**
 * {@code POST /call-events/&#123;id&#125;/spoof-check} 의 응답.
 *
 * <p><b>요청 DTO 가 없습니다.</b> 입력이 {@code multipart/form-data} 의
 * {@code audio_file} 파트 하나뿐이라 컨트롤러가 {@code MultipartFile} 로 직접 받습니다.
 *
 * <p>응답에 <b>화자 관련 필드가 하나도 없는 것</b>이 이 API 의 성격을 말해줍니다.
 * 여기서 나가는 건 "이 소리가 합성인가"뿐이고, "누구 목소리인가"는 앱이 온디바이스로
 * 따로 계산합니다(legal-compliance.md 3장).
 */
public final class SpoofCheckDto {

    private SpoofCheckDto() {
    }

    /**
     * {@code is_spoofed} 와 {@code is_analyzable} 에 {@code @JsonProperty} 를 붙인 이유는
     * Jackson 이 {@code boolean isXxx()} 접근자를 볼 때 {@code is} 접두사를 프로퍼티 이름에서
     * 떼어내기 때문입니다. 그대로 두면 {@code "spoofed"} 로 나가 앱이 필드를 못 찾습니다.
     *
     * <p>{@code @JsonInclude(ALWAYS)} 는 전역 {@code non_null} 설정 때문입니다 —
     * {@code audio_quality} 가 null 일 때 키가 통째로 사라지면 앱이 분기하기 어렵습니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Response(
            Long spoofCheckId,
            Double spoofScore,

            @JsonProperty("is_spoofed")
            Boolean spoofed,

            Double threshold,

            /*
             * additional_confirmation 은 사용자 확인, more_voice_required 는
             * 더 깨끗한 음성 재수집이 필요합니다. 둘을 하나로 합치면 안 됩니다.
             */
            SpoofCheck.AnalysisStatus analysisStatus,

            Integer analyzedSegments,
            String modelVersion,
            AudioQuality audioQuality
    ) {

        public static Response of(SpoofCheck saved, AntiSpoofingResponse source) {
            return new Response(
                    saved.getId(),
                    saved.getSpoofScore(),
                    saved.isSpoofed(),
                    saved.getThreshold(),
                    saved.getAnalysisStatus(),
                    saved.getAnalyzedSegments(),
                    saved.getModelVersion(),
                    AudioQuality.from(source));
        }
    }

    /**
     * 앱이 재요청 이유를 설명하는 데 필요한 값만 내려보냅니다. FastAPI 의
     * {@code rms_energy}·{@code peak_amplitude} 는 전달하지 않습니다.
     * 나머지는 {@code spoof_checks} 에 남기지도 않습니다 — 음성의 물리적 특성을
     * 필요 이상으로 축적할 이유가 없습니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AudioQuality(
            @JsonProperty("is_analyzable")
            Boolean analyzable,

            String message,
            Double durationSeconds,
            Double speechRatio,
            Double estimatedSnrDb
    ) {

        static AudioQuality from(AntiSpoofingResponse source) {
            AntiSpoofingResponse.AudioQuality quality = source.audioQuality();
            if (quality == null) {
                return null;
            }
            return new AudioQuality(
                    quality.isAnalyzable(),
                    quality.message(),
                    quality.durationSeconds(),
                    quality.speechRatio(),
                    quality.estimatedSnrDb());
        }
    }
}
