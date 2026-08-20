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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 통화 종료 · 녹음 파일 생성을 앱이 감지한 시점의 기록.
 *
 * <p><b>{@code BaseTimeEntity} 를 상속하지 않습니다.</b> ERD 상 이 테이블에는
 * {@code created_at} 만 있습니다({@code BaseTimeEntity} javadoc 이 지시하는 방식).
 *
 * <p><b>여기에는 통화 내용이 전혀 없습니다.</b> 상대방 번호도, 통화 시간도,
 * 매칭 결과도 들어오지 않습니다. 본인의 "언제 통화가 있었다"는 사실과 그 통화가
 * 분석됐는지 여부만 남습니다 — legal-compliance.md 3장의 "서버로 보내도 되는 것" 범위입니다.
 *
 * <p><b>상태 전이 규칙이 이 클래스의 핵심입니다.</b>
 * <pre>
 *   detected ──▶ analyzed_on_device   ← POST /voice-analyses 트랜잭션 안에서만
 *            ├─▶ skipped              ← PUT /call-events/{id}
 *            └─▶ failed               ← PUT /call-events/{id}
 * </pre>
 * {@code analyzed_on_device} 를 PUT 으로 받지 않는 이유는 명세 [정책] 그대로입니다 —
 * 앱이 {@code POST /voice-analyses} 와 {@code PUT /call-events} 를 둘 다 호출하면,
 * 중간에 네트워크가 끊겼을 때 "분석 결과는 저장됐는데 상태는 detected" 같은
 * 어긋난 조합이 생깁니다. 전이 지점을 한쪽으로 통일합니다.
 */
@Entity
@Getter
@Table(
        name = "call_events",
        indexes = @Index(
                name = "idx_call_events_user_detected",
                columnList = "user_id, detected_at")
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CallEvent {

    public enum LocalAnalysisStatus {

        /** 녹음 파일을 감지했고 아직 처리 전 */
        DETECTED,

        /** 온디바이스 분석까지 끝나 voice_analyses row 가 생긴 상태 */
        ANALYZED_ON_DEVICE,

        /** 사용자가 껐거나, 가족 임베딩이 없어 대조할 대상이 없거나, 음질이 미달이라 건너뜀 */
        SKIPPED,

        /** 분석 도중 오류 */
        FAILED;

        /** 응답은 "analyzed_on_device" 소문자입니다 */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }

        /**
         * {@code PUT /call-events/&#123;id&#125;} 의 입력 파싱.
         *
         * <p><b>{@code skipped}·{@code failed} 만 허용합니다.</b>
         * {@code analyzed_on_device} 나 {@code detected} 는 형식이 맞아도 거절해야 합니다 —
         * 전자는 서버가 {@code POST /voice-analyses} 에서만 찍는 값이고,
         * 후자는 되돌리기라 의미가 없습니다.
         */
        public static LocalAnalysisStatus fromClientUpdate(String value) {
            if (value != null) {
                String normalized = value.trim().toUpperCase();
                if (SKIPPED.name().equals(normalized)) {
                    return SKIPPED;
                }
                if (FAILED.name().equals(normalized)) {
                    return FAILED;
                }
            }
            throw new IllegalArgumentException("허용되지 않는 local_analysis_status: " + value);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 통화를 받은 본인 = 폰 주인. 상대방이 아닙니다 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 앱이 녹음 파일 생성을 감지한 시각 */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_analysis_status", nullable = false, length = 32)
    private LocalAnalysisStatus localAnalysisStatus;

    /**
     * 분석 결과 참조. {@code DELETE /voice-analyses/&#123;id&#125;} 로 결과만 지우면
     * 이 값은 null 로 끊기고 상태는 {@code analyzed_on_device} 로 남습니다 —
     * 분석이 일어났다는 사실 자체가 거짓이 되는 건 아니기 때문입니다.
     */
    @Column(name = "voice_analysis_id")
    private Long voiceAnalysisId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private CallEvent(Long userId, Instant detectedAt) {
        this.userId = userId;
        this.detectedAt = detectedAt;
        this.localAnalysisStatus = LocalAnalysisStatus.DETECTED;
    }

    /** {@code POST /call-events} — 항상 detected 로 시작합니다 */
    public static CallEvent detect(Long userId, Instant detectedAt) {
        return new CallEvent(userId, detectedAt);
    }

    // ── 상태 전이 ───────────────────────────────────────────

    /**
     * {@code PUT /call-events/&#123;id&#125;} — skipped · failed 만 들어옵니다.
     *
     * <p><b>이미 {@code analyzed_on_device} 면 아무 일도 하지 않고 false 를 돌려줍니다.</b>
     * 앱이 분석 제출에 성공한 뒤 응답을 못 받고 "실패했다"고 판단해 재시도하는 흐름이
     * 실제로 있습니다. 여기서 422 를 던지면 앱은 영영 재시도 루프에 갇히고,
     * 상태를 덮으면 멀쩡한 분석 결과가 failed 로 뒤집힙니다. 조용히 무시하고
     * 현재 상태를 응답하는 게 양쪽 다 피합니다.
     *
     * @return 실제로 상태가 바뀌었으면 true
     */
    public boolean updateLocalStatus(LocalAnalysisStatus status) {
        if (this.localAnalysisStatus == LocalAnalysisStatus.ANALYZED_ON_DEVICE) {
            return false;
        }
        if (this.localAnalysisStatus == status) {
            return false;
        }
        this.localAnalysisStatus = status;
        return true;
    }

    /**
     * {@code POST /voice-analyses} 트랜잭션 안에서만 호출됩니다.
     * 이 메서드가 컨트롤러에서 직접 불리는 경로를 만들지 마세요.
     */
    public void linkAnalysis(Long voiceAnalysisId) {
        this.voiceAnalysisId = voiceAnalysisId;
        this.localAnalysisStatus = LocalAnalysisStatus.ANALYZED_ON_DEVICE;
    }

    /** {@code DELETE /voice-analyses/&#123;id&#125;} — 참조만 끊고 상태는 유지합니다 */
    public void unlinkAnalysis() {
        this.voiceAnalysisId = null;
    }

    public boolean isAnalyzed() {
        return this.localAnalysisStatus == LocalAnalysisStatus.ANALYZED_ON_DEVICE;
    }
}