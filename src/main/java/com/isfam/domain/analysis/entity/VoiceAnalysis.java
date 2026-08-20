package com.isfam.domain.analysis.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 통화 1건의 최종 판정.
 *
 * <p><b>{@code similarity_score}·{@code matched_user_id} 컬럼을 추가하지 마세요.</b>
 * 이 두 값은 발신자 식별 결과라 민감정보이고, 통화 상대방에게 동의를 받을 방법이 없어
 * 법 제23조의 예외 어디에도 걸리지 않습니다(legal-compliance.md 3장·7장).
 * <b>온디바이스에서 계산하는 건 문제없습니다 — 그 결과를 서버로 올리는 것이 위반입니다.</b>
 * 매칭 정보는 폰 로컬 DB에만 존재하며, 기기를 바꾸면 사라지고 판정만 복구됩니다.
 * 그건 버그가 아니라 의도된 동작입니다.
 *
 * <p>{@code spoof_score} 도 여기 없습니다. 앱이 다시 올려보내지 않고 서버가
 * {@code spoof_check_id} 로 조회합니다(명세 [정책]) — 같은 값을 두 군데 두면
 * 언젠가 갈라지고, 그때 어느 쪽이 진짜인지 판단할 근거가 없습니다.
 *
 * <p><b>{@code call_event_id}·{@code spoof_check_id} 는 각각 유니크입니다.</b>
 * ERD 의 {@code 1:1} 관계를 제약으로 옮긴 것이고, 실무적으로는 앱이 제출 응답을 못 받고
 * 재시도했을 때 분석이 두 건 쌓이는 걸 막습니다. 그게 쌓이면 {@code danger_call} 알림도
 * 두 번 갑니다.
 */
@Entity
@Getter
@Table(
        name = "voice_analyses",
        indexes = @Index(
                name = "idx_voice_analyses_user_submitted",
                columnList = "user_id, submitted_at"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_voice_analyses_call_event",
                        columnNames = "call_event_id"),
                @UniqueConstraint(name = "uq_voice_analyses_spoof_check",
                        columnNames = "spoof_check_id")
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoiceAnalysis {

    /**
     * 앱이 온디바이스 화자 유사도와 서버 {@code spoof_score} 를 융합해 내린 결론.
     * <b>서버는 이 값을 다시 계산하지 않습니다</b> — 재계산하려면 유사도가 필요한데,
     * 그걸 받는 순간 위반입니다.
     */
    public enum FinalDecision {
        NORMAL, NEEDS_CHECK, DANGER;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }

        public static FinalDecision from(String value) {
            if (value != null) {
                String normalized = value.trim().toUpperCase();
                for (FinalDecision decision : values()) {
                    if (decision.name().equals(normalized)) {
                        return decision;
                    }
                }
            }
            throw new IllegalArgumentException("지원하지 않는 final_decision: " + value);
        }

        /** {@code normal} 이 아니면 danger_call 알림 대상입니다(명세 [정책]) */
        public boolean requiresNotification() {
            return this != NORMAL;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "call_event_id", nullable = false)
    private Long callEventId;

    @Column(name = "spoof_check_id", nullable = false)
    private Long spoofCheckId;

    /** 0 ~ 100 */
    @Column(name = "risk_level", nullable = false)
    private double riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_decision", nullable = false, length = 32)
    private FinalDecision finalDecision;

    /** 화자 모델(ECAPA) 버전만. 딥보이스 모델 버전은 spoof_checks 쪽입니다 */
    @Column(name = "on_device_model_version", nullable = false, length = 50)
    private String onDeviceModelVersion;

    /** 앱이 분석을 끝낸 시각 */
    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    /** 서버가 제출을 받은 시각. 히스토리 정렬·필터 기준입니다 */
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private VoiceAnalysis(Long userId, Long callEventId, Long spoofCheckId, double riskLevel,
                          FinalDecision finalDecision, String onDeviceModelVersion,
                          Instant analyzedAt, Instant submittedAt) {
        this.userId = userId;
        this.callEventId = callEventId;
        this.spoofCheckId = spoofCheckId;
        this.riskLevel = riskLevel;
        this.finalDecision = finalDecision;
        this.onDeviceModelVersion = onDeviceModelVersion;
        this.analyzedAt = analyzedAt;
        this.submittedAt = submittedAt;
    }

    public static VoiceAnalysis submit(Long userId, Long callEventId, Long spoofCheckId,
                                       double riskLevel, FinalDecision finalDecision,
                                       String onDeviceModelVersion, Instant analyzedAt,
                                       Instant submittedAt) {
        return new VoiceAnalysis(userId, callEventId, spoofCheckId, riskLevel, finalDecision,
                onDeviceModelVersion, analyzedAt, submittedAt);
    }
}