package com.isfam.domain.voice.service;

import com.isfam.domain.voice.dto.VoiceprintDto;
import com.isfam.domain.voice.entity.VoiceprintSample;

/**
 * 음질 판정 — <b>서버 판정은 방어용입니다.</b>
 *
 * <p>명세상 실제 음질 판정은 앱이 합니다. 미달이면 앱에서 재녹음을 유도하고 전송조차
 * 하지 않습니다. 그럼에도 서버가 다시 보는 이유는 앱 버전이 여럿 돌아다니고,
 * 한 번 저장된 임베딩은 <b>가족 전원의 폰으로 복제</b>되기 때문입니다.
 * 쓰레기 임베딩 하나가 8대의 기기로 퍼진 뒤에는 되돌릴 방법이 없습니다.
 *
 * <p>판정은 두 단계입니다.
 * <ol>
 *   <li><b>거절</b>(VOICE_001 422) — {@code is_analyzable=false} 이거나 물리적으로 말이 안 되는 값.
 *       저장하지 않습니다</li>
 *   <li><b>{@code REVIEW}</b> — 저장은 하되 앱이 재녹음을 권합니다.
 *       {@code GET /me/voiceprint} 의 {@code samples[].quality} 로 내려갑니다</li>
 * </ol>
 *
 * <p>임계값이 상수로 박혀 있는 건 아직 조정할 데이터가 없어서입니다.
 * {@code voiceprint_samples.quality_score} 를 함께 저장해 두었으니, 운영 데이터가 쌓이면
 * 기존 row 로 새 임계값을 검증할 수 있습니다.
 */
final class VoiceQualityPolicy {

    /** ECAPA 는 짧아도 뽑히지만, 2초 미만은 화자 특성이 거의 안 잡힙니다 */
    private static final double MIN_DURATION_SECONDS = 2.0;

    /** 이보다 짧으면 문장을 읽다 만 것으로 봅니다 */
    private static final double REJECT_DURATION_SECONDS = 1.0;

    /** 발화 구간 비율. 낮으면 침묵·잡음이 대부분입니다 */
    private static final double MIN_SPEECH_RATIO = 0.5;
    private static final double REJECT_SPEECH_RATIO = 0.2;

    /** 너무 작으면 마이크가 멀거나 음소거에 가깝습니다 */
    private static final double MIN_RMS_ENERGY = 0.02;
    private static final double REJECT_RMS_ENERGY = 0.005;

    /** 1.0 에 붙으면 클리핑 — 파형이 잘려 특징이 뭉갭니다 */
    private static final double MAX_PEAK_AMPLITUDE = 0.99;

    private VoiceQualityPolicy() {
    }

    /** 저장 자체가 불가능한 수준인지. true 면 서비스가 VOICE_001 을 던집니다 */
    static boolean isRejected(VoiceprintDto.AudioQuality quality) {
        if (quality == null || !Boolean.TRUE.equals(quality.isAnalyzable())) {
            return true;
        }
        return below(quality.durationSeconds(), REJECT_DURATION_SECONDS)
                || below(quality.speechRatio(), REJECT_SPEECH_RATIO)
                || below(quality.rmsEnergy(), REJECT_RMS_ENERGY);
    }

    static VoiceprintSample.Quality evaluate(VoiceprintDto.AudioQuality quality) {
        boolean good = notBelow(quality.durationSeconds(), MIN_DURATION_SECONDS)
                && notBelow(quality.speechRatio(), MIN_SPEECH_RATIO)
                && notBelow(quality.rmsEnergy(), MIN_RMS_ENERGY)
                && notAbove(quality.peakAmplitude(), MAX_PEAK_AMPLITUDE);

        return good ? VoiceprintSample.Quality.GOOD : VoiceprintSample.Quality.REVIEW;
    }

    /**
     * 0.0 ~ 1.0 점수. 세 지표를 각각 기준값 대비 비율로 환산해 평균냅니다.
     *
     * <p>정밀한 지표가 아니라 <b>임계값 조정을 위한 기록</b>입니다.
     * 이 값으로 판정하지 않습니다 — 판정은 {@link #evaluate} 의 개별 조건입니다.
     */
    static float score(VoiceprintDto.AudioQuality quality) {
        double duration = ratio(quality.durationSeconds(), MIN_DURATION_SECONDS);
        double speech = ratio(quality.speechRatio(), MIN_SPEECH_RATIO);
        double energy = ratio(quality.rmsEnergy(), MIN_RMS_ENERGY);

        double mean = (duration + speech + energy) / 3.0;

        // 클리핑은 다른 지표가 아무리 좋아도 특징을 망가뜨리므로 감점합니다
        if (quality.peakAmplitude() != null && quality.peakAmplitude() > MAX_PEAK_AMPLITUDE) {
            mean *= 0.7;
        }
        return (float) Math.max(0.0, Math.min(1.0, mean));
    }

    private static double ratio(Double value, double threshold) {
        if (value == null) {
            return 0.5; // 안 보내온 지표는 중립. 없다고 0점을 주면 구버전 앱이 전부 review 가 됩니다
        }
        return Math.min(1.0, value / threshold);
    }

    private static boolean below(Double value, double threshold) {
        return value != null && value < threshold;
    }

    private static boolean notBelow(Double value, double threshold) {
        return value == null || value >= threshold;
    }

    private static boolean notAbove(Double value, double threshold) {
        return value == null || value <= threshold;
    }
}