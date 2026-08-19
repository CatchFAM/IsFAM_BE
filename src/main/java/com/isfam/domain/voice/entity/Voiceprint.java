package com.isfam.domain.voice.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자당 1개인 화자 임베딩. <b>Phase 3 시점에는 아무도 INSERT 하지 않습니다.</b>
 *
 * <p>지금 만들어두는 이유는 GET /family 응답의 {@code voiceprint_registered} 때문입니다.
 * 하드코딩 false 로 두면 Phase 6 에서 응답 조립부까지 되짚어야 하는데, 껍데기만 있으면
 * {@code existsByUserId} 하나로 끝납니다. 대신 Phase 6 까지 빈 테이블이 하나 놀게 됩니다.
 *
 * <p><b>회원 탈퇴 시 물리 삭제입니다</b>(service-flow.md 10장 ③).
 * 생체정보는 soft delete 가 허용되지 않습니다 — {@code deleted_at} 컬럼을 두지 마세요.
 *
 * <p>{@code speaker_embedding} 은 암호화된 192차원 float32 임베딩(샘플 평균)입니다.
 * 암호화 방식은 Phase 6 에서 정합니다.
 */
@Getter
@Entity
@Table(name = "voiceprints")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Voiceprint extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "speaker_embedding", nullable = false)
    private byte[] speakerEmbedding;

    @Column(name = "embedding_model_version", nullable = false, length = 50)
    private String embeddingModelVersion;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    private Voiceprint(Long userId, byte[] speakerEmbedding, String embeddingModelVersion,
                       int sampleCount, Instant registeredAt) {
        this.userId = userId;
        this.speakerEmbedding = speakerEmbedding;
        this.embeddingModelVersion = embeddingModelVersion;
        this.sampleCount = sampleCount;
        this.registeredAt = registeredAt;
    }

    public static Voiceprint register(Long userId, byte[] speakerEmbedding,
                                      String embeddingModelVersion, int sampleCount,
                                      Instant registeredAt) {
        return new Voiceprint(userId, speakerEmbedding, embeddingModelVersion,
                sampleCount, registeredAt);
    }

    /** 샘플이 추가·교체되면 평균을 다시 계산해 갱신합니다(Phase 6) */
    public void updateEmbedding(byte[] speakerEmbedding, String embeddingModelVersion,
                                int sampleCount, Instant registeredAt) {
        this.speakerEmbedding = speakerEmbedding;
        this.embeddingModelVersion = embeddingModelVersion;
        this.sampleCount = sampleCount;
        this.registeredAt = registeredAt;
    }
}