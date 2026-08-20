package com.isfam.domain.voice.service;

import com.isfam.domain.voice.dto.VoiceprintDto;
import com.isfam.domain.voice.entity.VoiceprintSample;

/**
 * 음질 판정 — <b>측정은 앱이 하고, 서버는 앱이 보낸 값을 읽기만 합니다.</b>
 *
 * <p>등록 API 에는 오디오가 오지 않습니다({@code application/json}). 여기서 다루는 건
 * 전부 앱이 계산해 적어 보낸 숫자이고, 서버가 파형을 다시 재는 일은 없습니다.
 *
 * <p><b>서버가 거절하는 조건은 명세가 정한 2개뿐입니다.</b>
 * <ul>
 *   <li>{@code audio_quality.is_analyzable = false}</li>
 *   <li>{@code embedding} 이 192개가 아님 — 이건 {@code VoiceprintService} 가 봅니다</li>
 * </ul>
 * 여기에 서버 나름의 하한선을 덧붙이면, 앱이 정상 판정해 보낸 요청이 422 로 튕깁니다.
 * 앱은 자기 게이트를 통과시킨 녹음이라 사용자에게 안내할 말이 없습니다.
 * <b>거절 조건을 늘리지 마세요.</b>
 *
 * <p>{@code REVIEW} 는 거절이 아니라 <b>권유</b>입니다. 저장은 하고,
 * {@code GET /me/voiceprint} 의 {@code samples[].quality} 로 내려가 앱이 재녹음을 유도합니다
 * (명세 [정책]).
 *
 * <p><b>임계값은 앱 게이트와 같은 값이어야 합니다.</b> 서버가 더 빡빡하면
 * 앱은 통과 → 서버는 review → 앱이 재녹음 요구 → 같은 폰·같은 방이라 같은 값 →
 * 무한 루프가 됩니다. 앱 상수({@code AudioSpec} · {@code PreprocessSpec} ·
 * {@code VoiceRecorder})가 바뀌면 여기도 같이 바꿔야 합니다.
 *
 * <p>운영 데이터가 쌓이면 {@code voiceprint_samples.quality_score} 로 임계값을
 * 다시 볼 수 있습니다. 다만 앱이 지표를 보내지 않는 동안은 그 값이 전부 중립점이라
 * 재검증 근거가 되지 못합니다 — 원본 지표 컬럼 추가는 별건으로 남아 있습니다.
 */
final class VoiceQualityPolicy {

    /**
     * 앱 등록 게이트와 동일한 값입니다.
     *
     * <pre>
     *   MIN_DURATION_SECONDS  ← VoiceRecorder.MIN_DURATION_MS(5000)
     *                           · VoiceprintEnrollmentService(16000 * 5 samples)
     *   MIN_SPEECH_RATIO      ← VoiceRecorder.MIN_SPEECH_RATIO(0.45)
     *   MIN_RMS_ENERGY        ← PreprocessSpec.MIN_RMS(0.005)
     * </pre>
     */
    private static final double MIN_DURATION_SECONDS = 5.0;
    private static final double MIN_SPEECH_RATIO = 0.45;
    private static final double MIN_RMS_ENERGY = 0.005;

    /**
     * 1.0 에 붙으면 클리핑 — 파형이 잘려 특징이 뭉갭니다.
     *
     * <p><b>앱은 정규화 전 {@code max(abs(sample))} 을 보내야 합니다.</b>
     * peak 정규화(target 0.95) 이후 값을 보내면 항상 0.95 라 이 조건이 영영 걸리지 않습니다.
     * 앱에 이 지표를 계산하는 코드가 아직 없어 당분간은 null 로 들어옵니다.
     */
    private static final double MAX_PEAK_AMPLITUDE = 0.99;

    /** 지표를 보내지 않는 앱 버전을 0점 처리하면 등록분 전체가 review 가 됩니다 */
    private static final double NEUTRAL_SCORE = 0.5;

    private VoiceQualityPolicy() {
    }

    /**
     * 저장 자체가 불가능한 수준인지. true 면 서비스가 VOICE_001(422)을 던집니다.
     *
     * <p><b>{@code is_analyzable} 만 봅니다.</b> 음질 판정은 앱의 몫이고,
     * 앱이 "분석 가능하다"고 한 녹음을 서버가 숫자 몇 개로 뒤집지 않습니다.
     * 값이 이상하면 저장하되 {@link #evaluate} 가 {@code REVIEW} 로 표시합니다.
     */
    static boolean isRejected(VoiceprintDto.AudioQuality quality) {
        return quality == null || !Boolean.TRUE.equals(quality.isAnalyzable());
    }

    /**
     * {@code GOOD} / {@code REVIEW}.
     *
     * <p>보내오지 않은 지표는 중립(통과)입니다. 구버전 앱이 {@code is_analyzable} 만
     * 보내면 {@code GOOD} 이 됩니다 — 판정 근거가 없는데 재녹음을 시킬 수는 없습니다.
     */
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
     *
     * <p>지표가 전부 null 이면 {@code GOOD} 인데 점수는 중립점(0.5)이 됩니다.
     * 모순처럼 보이지만 의도한 것입니다 — 통과는 시키되 "근거 없이 통과했다"는 사실을
     * 점수에 남겨, 나중에 실제 측정값이 있는 row 와 섞이지 않게 합니다.
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
            return NEUTRAL_SCORE;
        }
        return Math.min(1.0, value / threshold);
    }

    private static boolean notBelow(Double value, double threshold) {
        return value == null || value >= threshold;
    }

    private static boolean notAbove(Double value, double threshold) {
        return value == null || value <= threshold;
    }
}