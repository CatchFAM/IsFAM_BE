package com.isfam.common.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

/**
 * 화자 임베딩의 표현 변환 — base64 ↔ float32[192] ↔ byte[].
 *
 * <p><b>바이트 순서는 리틀엔디언입니다.</b> 앱이 ONNX Runtime 출력(FloatBuffer)을 그대로
 * base64 로 감싸 보내는데, 안드로이드·iOS 모두 리틀엔디언입니다. 자바 {@link ByteBuffer} 의
 * 기본값은 <b>빅엔디언</b>이라 이 지정을 빠뜨리면 예외 없이 통과하고 값만 쓰레기가 됩니다 —
 * 192개 float 이 전부 말도 안 되는 크기가 되어, 코사인 유사도가 항상 0 근처로 나옵니다.
 * 등록도 되고 동기화도 되는데 탐지만 안 되는 형태라 원인 찾기가 가장 어려운 종류의 버그입니다.
 *
 * <p>여기서는 검증만 하고 예외는 던지지 않습니다({@link IllegalArgumentException} 제외).
 * VOICE_001 로 바꾸는 건 서비스 계층의 책임입니다.
 */
public final class EmbeddingCodec {

    /** ECAPA-TDNN 출력 차원. 명세의 {@code float32[192]} */
    public static final int DIMENSION = 192;

    /** 192 × 4 = 768 바이트 */
    public static final int BYTE_LENGTH = DIMENSION * Float.BYTES;

    private EmbeddingCodec() {
    }

    /**
     * base64 문자열을 float32 배열로 풉니다.
     *
     * @throws IllegalArgumentException base64 가 아니거나 길이가 768 바이트가 아닐 때
     */
    public static float[] decodeBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("임베딩이 비어 있습니다");
        }

        byte[] raw;
        try {
            // 앱이 URL-safe 로 보낼 가능성까지 흡수합니다. 앱 수정 없이 받는 편이 낫습니다.
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            raw = Base64.getUrlDecoder().decode(base64.trim());
        }

        return toFloats(raw);
    }

    /**
     * @throws IllegalArgumentException 길이가 768 바이트가 아닐 때
     */
    public static float[] toFloats(byte[] raw) {
        if (raw == null || raw.length != BYTE_LENGTH) {
            throw new IllegalArgumentException(
                    "임베딩 길이가 %d 바이트가 아닙니다: %d".formatted(
                            BYTE_LENGTH, raw == null ? 0 : raw.length));
        }

        float[] values = new float[DIMENSION];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);

        // NaN/Infinity 가 섞이면 평균이 통째로 오염되고, 그 상태가 DB 에 그대로 굳습니다.
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("임베딩에 유효하지 않은 값이 있습니다");
            }
        }
        return values;
    }

    public static byte[] toBytes(float[] values) {
        if (values == null || values.length != DIMENSION) {
            throw new IllegalArgumentException("임베딩 차원이 %d 이 아닙니다".formatted(DIMENSION));
        }
        ByteBuffer buffer = ByteBuffer.allocate(BYTE_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);
        return buffer.array();
    }

    public static String encodeBase64(float[] values) {
        return Base64.getEncoder().encodeToString(toBytes(values));
    }

    /**
     * 샘플 평균 — {@code voiceprints.speaker_embedding} 의 정의입니다(ERD).
     *
     * <p><b>정규화하지 않습니다.</b> 앱이 코사인 유사도로 대조하는데 코사인은 크기에
     *영향받지 않아 정규화해도 결과가 같고, 서버가 임의로 손대면 앱이 기대하는 값과
     * 달라졌을 때 원인이 서버인지 모델인지 갈리지 않습니다.
     */
    public static float[] average(List<float[]> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("평균을 낼 샘플이 없습니다");
        }

        double[] sum = new double[DIMENSION];
        for (float[] sample : samples) {
            if (sample.length != DIMENSION) {
                throw new IllegalArgumentException("차원이 다른 샘플이 섞여 있습니다");
            }
            for (int i = 0; i < DIMENSION; i++) {
                sum[i] += sample[i];
            }
        }

        float[] mean = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            mean[i] = (float) (sum[i] / samples.size());
        }
        return mean;
    }
}