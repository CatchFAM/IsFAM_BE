package com.isfam.domain.voice.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문장 단위 녹음 샘플. {@code voiceprints.speaker_embedding} 은 이 샘플들의 평균입니다.
 *
 * <p><b>{@code (user_id, sentence_id)} 는 유니크입니다.</b> 명세의
 * "같은 sentence_id 면 해당 샘플만 교체" 가 이 제약으로 표현됩니다. 제약 없이 서비스에서
 * 조회-후-저장만 하면 앱이 같은 문장을 빠르게 두 번 보냈을 때 샘플이 둘 생기고,
 * 그 뒤로 {@code sample_count} 가 실제 문장 수보다 커집니다 — 사용자는 3문장을 녹음했는데
 * 화면에는 4개로 보입니다.
 *
 * <p><b>{@code embedding} 은 암호화된 상태로만 들어옵니다</b>({@code EmbeddingCipher}).
 * 평문 float 배열이 이 필드에 닿는 경로를 만들지 마세요 — 생체인식 특징정보입니다.
 *
 * <p><b>회원 탈퇴 시 물리 삭제</b>입니다(Phase 9). {@code deleted_at} 컬럼을 두지 마세요.
 */
@Getter
@Entity
@Table(name = "voiceprint_samples", uniqueConstraints = {
        @UniqueConstraint(name = "uq_voiceprint_samples_user_sentence",
                columnNames = {"user_id", "sentence_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoiceprintSample extends BaseTimeEntity {

    /**
     * 음질 판정 결과.
     *
     * <p>{@code REVIEW} 는 "저장은 했지만 다시 녹음하는 게 좋다"입니다. 앱이
     * {@code GET /me/voiceprint} 응답을 보고 재녹음을 유도합니다. 아예 못 쓸 수준이면
     * 저장하지 않고 VOICE_001(422)로 거절하므로, 이 값이 REVIEW 라고 평균에서 빼지는 않습니다 —
     * 빼면 3문장 녹음했는데 sample_count 가 2가 되어 앱 화면과 어긋납니다.
     */
    public enum Quality {
        GOOD, REVIEW;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 앱이 제시한 문장 번호 */
    @Column(name = "sentence_id", nullable = false)
    private int sentenceId;

    /** 암호화된 192차원 float32 임베딩 */
    @Column(name = "embedding", nullable = false)
    private byte[] embedding;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality", nullable = false, length = 10)
    private Quality quality;

    /** 0.0 ~ 1.0. 판정 근거를 남겨두면 임계값을 조정할 때 기존 데이터로 검증할 수 있습니다 */
    @Column(name = "quality_score")
    private Float qualityScore;

    private VoiceprintSample(Long userId, int sentenceId, byte[] embedding,
                             Quality quality, Float qualityScore) {
        this.userId = userId;
        this.sentenceId = sentenceId;
        this.embedding = embedding;
        this.quality = quality;
        this.qualityScore = qualityScore;
    }

    public static VoiceprintSample of(Long userId, int sentenceId, byte[] embedding,
                                      Quality quality, Float qualityScore) {
        return new VoiceprintSample(userId, sentenceId, embedding, quality, qualityScore);
    }

    /** 같은 문장 재녹음 — row 를 새로 만들지 않고 이 샘플만 교체합니다 */
    public void replace(byte[] embedding, Quality quality, Float qualityScore) {
        this.embedding = embedding;
        this.quality = quality;
        this.qualityScore = qualityScore;
    }
}