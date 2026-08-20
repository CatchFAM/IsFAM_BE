package com.isfam.domain.analysis.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;

/**
 * 딥보이스 판정 1회의 결과 <b>겸 접속기록</b>.
 *
 * <p>legal-compliance.md 4장 ②의 {@code spoof_check_logs} 가 이 테이블입니다.
 * <b>SKT 에이닷이 개인정보위 시정권고를 받은 사유가 정확히 이 기록의 미비였습니다.</b>
 * 상대방 동의 문제가 아니었습니다. 통화 음성을 서버로 받는 순간 이 row 가 없으면
 * 같은 지적을 받습니다.
 *
 * <p><b>{@code purged_at} 이 파기 증빙입니다.</b> null 인 row 가 있으면 그건
 * "원본을 지웠는지 알 수 없다"는 뜻이고, 운영에서 감시해야 할 유일한 지표입니다.
 * <pre>
 *   select count(*) from spoof_checks where purged_at is null;  -- 항상 0 이어야 합니다
 * </pre>
 *
 * <p><b>이 테이블은 {@code DELETE /voice-analyses} 로 지워지지 않습니다.</b>
 * 사용자가 분석 기록을 지우는 것과 사업자가 접속기록 보관 의무를 이행하는 것은 별개입니다.
 * Phase 9 의 회원 탈퇴 삭제 목록(voice_analyses·call_events·notifications·demo_sessions)에도
 * 이 테이블이 없는 게 그 근거입니다.
 *
 * <p><b>여기에 화자 관련 컬럼을 추가하지 마세요.</b> 이 엔드포인트는 딥보이스 모델만
 * 실행합니다. 임베딩 추출·가족 대조가 끼어드는 순간 "합성 판별"이 "발신자 식별"로
 * 성격이 바뀌어, 동의를 받을 수 없는 상대방의 민감정보 처리가 됩니다
 * (legal-compliance.md 5장).
 */
@Entity
@Getter
@Table(
        name = "spoof_checks",
        indexes = {
                @Index(name = "idx_spoof_checks_user_requested",
                        columnList = "user_id, requested_at"),
                @Index(name = "idx_spoof_checks_call_event",
                        columnList = "call_event_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpoofCheck {

    /**
     * FastAPI 가 내려주는 판정 상태.
     *
     * <p>{@code MORE_VOICE_REQUIRED} 는 음질 게이트 미달입니다 —
     * 길이 &lt; 2.0초, RMS &lt; 0.005, 발화 비율 &lt; 0.25 중 하나라도 걸리면 여기로 떨어집니다.
     * (1.0초 미만은 이 상태가 아니라 FastAPI 400 입니다)
     *
     * <p><b>이 상태에서도 {@code spoof_score}·{@code is_spoofed} 에 값은 채워져 옵니다.</b>
     * 신뢰할 수 없는 값이라 앱은 경고 UI 를 띄우면 안 되고, 다음 세그먼트를 모아
     * 재요청해야 합니다. 그래서 row 에는 그대로 저장하되(접속기록이므로 받은 값을
     * 왜곡하지 않습니다) 앱이 상태로 분기하도록 응답에 함께 실어 보냅니다.
     */
    public enum AnalysisStatus {
        COMPLETE, MORE_VOICE_REQUIRED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }

        /** FastAPI 응답의 analysis_status. 모르는 값이 오면 신뢰할 수 없으므로 재요청 쪽으로 붙입니다 */
        public static AnalysisStatus from(String value) {
            if (value != null && COMPLETE.name().equalsIgnoreCase(value.trim())) {
                return COMPLETE;
            }
            return MORE_VOICE_REQUIRED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "call_event_id", nullable = false)
    private Long callEventId;

    /** X-Device-Id. 어느 단말에서 음성이 올라왔는지 — 접속기록의 일부입니다 */
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "spoof_score", nullable = false)
    private double spoofScore;

    /** FastAPI 가 {@code spoof_score >= threshold} 로 이미 계산해서 줍니다. 재계산하지 않습니다 */
    @Column(name = "is_spoofed", nullable = false)
    private boolean spoofed;

    /** 하드코딩 금지 — 모델이 바뀌면 임계값도 바뀌고, 과거 판정은 그때의 값으로 해석돼야 합니다 */
    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 32)
    private AnalysisStatus analysisStatus;

    @Column(name = "analyzed_segments")
    private Integer analyzedSegments;

    /** FastAPI 응답의 {@code model_name} 을 그대로 보관합니다 */
    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    /** 업로드된 바이트 수. 접속기록에 "무엇이 얼마나 들어왔는가"를 남기는 항목입니다 */
    @Column(name = "audio_bytes")
    private Integer audioBytes;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** <b>null 이면 파기 누락입니다.</b> 클래스 javadoc 참고 */
    @Column(name = "purged_at")
    private Instant purgedAt;

    private SpoofCheck(Long userId, Long callEventId, Long deviceId, double spoofScore,
                       boolean spoofed, double threshold, AnalysisStatus analysisStatus,
                       Integer analyzedSegments, String modelVersion, Double durationSeconds,
                       Integer audioBytes, String clientIp, Instant requestedAt, Instant purgedAt) {
        this.userId = userId;
        this.callEventId = callEventId;
        this.deviceId = deviceId;
        this.spoofScore = spoofScore;
        this.spoofed = spoofed;
        this.threshold = threshold;
        this.analysisStatus = analysisStatus;
        this.analyzedSegments = analyzedSegments;
        this.modelVersion = modelVersion;
        this.durationSeconds = durationSeconds;
        this.audioBytes = audioBytes;
        this.clientIp = clientIp;
        this.requestedAt = requestedAt;
        this.purgedAt = purgedAt;
    }

    /**
     * <b>{@code purgedAt} 을 생성자에서 받습니다.</b> 나중에 채우게 하면
     * "저장은 됐는데 파기 기록이 빠진" row 가 생길 수 있습니다. 원본 파기가 끝난 뒤에야
     * 이 객체를 만들 수 있도록 강제하는 게 이 시그니처의 목적입니다.
     */
    public static SpoofCheck of(Long userId, Long callEventId, Long deviceId, double spoofScore,
                                boolean spoofed, double threshold, AnalysisStatus analysisStatus,
                                Integer analyzedSegments, String modelVersion,
                                Double durationSeconds, Integer audioBytes, String clientIp,
                                Instant requestedAt, Instant purgedAt) {
        return new SpoofCheck(userId, callEventId, deviceId, spoofScore, spoofed, threshold,
                analysisStatus, analyzedSegments, modelVersion, durationSeconds, audioBytes,
                clientIp, requestedAt, purgedAt);
    }
}