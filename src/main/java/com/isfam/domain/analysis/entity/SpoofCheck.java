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
 * <p><b>파기 증빙이 두 컬럼입니다.</b>
 * <ul>
 *   <li>{@code purged_at} — 우리 서버(tmpfs) 원본을 지운 시각</li>
 *   <li>{@code remote_purged} — 분석 서버(FastAPI) 임시 파일 파기 여부 (P-23)</li>
 * </ul>
 * {@code purged_at} 만으로는 Java 쪽만 증명됩니다. FastAPI 의 {@code UploadFile} 은
 * 1MB 를 넘으면 디스크로 스풀되고 30초 오디오가 정확히 그 구간이라, 그쪽에도 통화
 * 음성이 남습니다. 두 값이 함께 있어야 "어디에도 안 남는다"고 말할 수 있습니다.
 * <pre>
 *   select count(*) from spoof_checks where purged_at is null;        -- 항상 0
 *   select count(*) from spoof_checks where remote_purged = false;    -- 항상 0
 * </pre>
 * 두 쿼리는 {@code PurgeMonitor} 가 5분마다 돌리고, 0 이 아니면 알람 +
 * {@code /actuator/health} DOWN 입니다(P-21).
 *
 * <p><b>판정을 거부한 요청도 row 를 남깁니다</b>({@link AnalysisStatus#REJECTED}).
 * 상한을 넘는 음성이 들어와도 그 사실이 기록에 남지 않으면 "30초만 받는다"는
 * 통제를 만든 의미가 없습니다(P-20). 거부 row 는 판정값 4종이 null 이고
 * {@code rejectedReason} 이 채워집니다.
 *
 * <p><b>이 테이블은 {@code DELETE /voice-analyses} 로 지워지지 않습니다.</b>
 * 사용자가 분석 기록을 지우는 것과 사업자가 접속기록 보관 의무를 이행하는 것은 별개입니다.
 *
 * <p><b>여기에 화자 관련 컬럼을 추가하지 마세요.</b> 이 엔드포인트는 딥보이스 모델만
 * 실행합니다. 임베딩 추출·가족 대조가 끼어드는 순간 "합성 판별"이 "발신자 식별"로
 * 성격이 바뀌어, 동의를 받을 수 없는 상대방의 민감정보 처리가 됩니다.
 */
@Entity
@Getter
@Table(
        name = "spoof_checks",
        indexes = {
                @Index(name = "idx_spoof_checks_user_requested",
                        columnList = "user_id, requested_at"),
                @Index(name = "idx_spoof_checks_call_event",
                        columnList = "call_event_id"),
                @Index(name = "idx_spoof_checks_status_requested",
                        columnList = "analysis_status, requested_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpoofCheck {

    /**
     * 판정 상태.
     *
     * <p>{@code ADDITIONAL_CONFIRMATION} 은 전화 강건 모델은 통과했지만 보조 모델이
     * 경고한 상태입니다. 위험으로 확정하지도, 안전으로 신뢰하지도 않고 앱에서
     * 사용자 추가 확인을 요청해야 합니다.
     *
     * <p>{@code MORE_VOICE_REQUIRED} 는 음질 게이트 미달입니다 —
     * 길이, RMS, 발화 비율, 추정 SNR 중 하나라도 걸리면 여기로 떨어집니다.
     * 이 상태에서도 점수는 채워져 오지만 신뢰할 수 없는 값이라 앱은 재요청해야 합니다.
     *
     * <p>{@code REJECTED} 는 판정 자체를 하지 않은 요청입니다. 점수가 없습니다.
     */
    public enum AnalysisStatus {
        COMPLETE, ADDITIONAL_CONFIRMATION, MORE_VOICE_REQUIRED, REJECTED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }

        /**
         * FastAPI 응답의 analysis_status. 모르는 값이 오면 신뢰할 수 없으므로 재요청 쪽으로 붙입니다.
         *
         * <p>{@code REJECTED} 는 우리가 정하는 값이라 여기서 나오지 않습니다.
         */
        public static AnalysisStatus from(String value) {
            if (value == null) {
                return MORE_VOICE_REQUIRED;
            }
            String normalized = value.trim();
            if (COMPLETE.name().equalsIgnoreCase(normalized)) {
                return COMPLETE;
            }
            if (ADDITIONAL_CONFIRMATION.name().equalsIgnoreCase(normalized)) {
                return ADDITIONAL_CONFIRMATION;
            }
            return MORE_VOICE_REQUIRED;
        }
    }

    /** 판정을 거부한 이유. {@code analysisStatus == REJECTED} 일 때만 채웁니다 */
    public enum RejectedReason {
        /** 상한(35초) 초과. WAV 헤더 계산값 또는 FastAPI 응답값 */
        DURATION_EXCEEDED,
        /** RIFF/WAVE 헤더를 읽을 수 없음. 앱이 wav 가 아닌 것을 보냈거나 파일이 깨짐 */
        INVALID_WAV_HEADER,
        /** 업로드 상한(1.5MB) 초과 */
        SIZE_EXCEEDED;

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

    @Column(name = "call_event_id", nullable = false)
    private Long callEventId;

    /** X-Device-Id. 어느 단말에서 음성이 올라왔는지 — 접속기록의 일부입니다 */
    @Column(name = "device_id")
    private Long deviceId;

    /** 거부 row 에서는 null — 판정을 하지 않았으므로 값이 없습니다 */
    @Column(name = "spoof_score")
    private Double spoofScore;

    /** FastAPI 가 {@code spoof_score >= threshold} 로 이미 계산해서 줍니다. 재계산하지 않습니다 */
    @Column(name = "is_spoofed")
    private Boolean spoofed;

    /** 하드코딩 금지 — 모델이 바뀌면 임계값도 바뀌고, 과거 판정은 그때의 값으로 해석돼야 합니다 */
    @Column(name = "threshold")
    private Double threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 32)
    private AnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejected_reason", length = 32)
    private RejectedReason rejectedReason;

    @Column(name = "analyzed_segments")
    private Integer analyzedSegments;

    /** FastAPI 응답의 {@code model_name} 을 그대로 보관합니다 */
    @Column(name = "model_version", length = 100)
    private String modelVersion;

    /** 거부 row 에서는 이 값이 기록의 본체입니다 — 무엇이 상한을 넘었는지 */
    @Column(name = "duration_seconds")
    private Double durationSeconds;

    /** 업로드된 바이트 수. 접속기록에 "무엇이 얼마나 들어왔는가"를 남기는 항목입니다 */
    @Column(name = "audio_bytes")
    private Integer audioBytes;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** <b>null 이면 파기 누락입니다.</b> 거부 row 에도 반드시 채웁니다 */
    @Column(name = "purged_at")
    private Instant purgedAt;

    /**
     * 분석 서버(FastAPI) 임시 파일 파기 여부 (P-23).
     *
     * <p><b>세 가지 상태를 구별합니다.</b>
     * <ul>
     *   <li>{@code true}  — 분석 서버가 지웠다고 응답</li>
     *   <li>{@code false} — 지우지 못했다고 응답. <b>파기 실패이며 알람 대상</b></li>
     *   <li>{@code null}  — 분석 서버에 보내지 않았거나(사전 거부),
     *       아직 이 필드를 안 보내는 버전. 실패로 세지 않습니다</li>
     * </ul>
     * null 과 false 를 합치면 배포 과도기의 공백이 파기 실패로 집계돼
     * 진짜 실패가 묻힙니다. 대신 FastAPI 배포가 끝난 뒤에도 null 이 계속 나오면
     * 그건 그것대로 확인해야 합니다.
     */
    @Column(name = "remote_purged")
    private Boolean remotePurged;

    private SpoofCheck(Long userId, Long callEventId, Long deviceId, Double spoofScore,
                       Boolean spoofed, Double threshold, AnalysisStatus analysisStatus,
                       RejectedReason rejectedReason, Integer analyzedSegments,
                       String modelVersion, Double durationSeconds, Integer audioBytes,
                       String clientIp, Instant requestedAt, Instant purgedAt,
                       Boolean remotePurged) {
        this.userId = userId;
        this.callEventId = callEventId;
        this.deviceId = deviceId;
        this.spoofScore = spoofScore;
        this.spoofed = spoofed;
        this.threshold = threshold;
        this.analysisStatus = analysisStatus;
        this.rejectedReason = rejectedReason;
        this.analyzedSegments = analyzedSegments;
        this.modelVersion = modelVersion;
        this.durationSeconds = durationSeconds;
        this.audioBytes = audioBytes;
        this.clientIp = clientIp;
        this.requestedAt = requestedAt;
        this.purgedAt = purgedAt;
        this.remotePurged = remotePurged;
    }

    /**
     * 판정이 끝난 요청.
     *
     * <p><b>{@code purgedAt} 과 {@code remotePurged} 를 생성자에서 받습니다.</b> 나중에
     * 채우게 하면 "저장은 됐는데 파기 기록이 빠진" row 가 생길 수 있습니다. 양쪽 파기가
     * 끝난 뒤에야 이 객체를 만들 수 있도록 강제하는 게 이 시그니처의 목적입니다.
     */
    public static SpoofCheck of(Long userId, Long callEventId, Long deviceId, Double spoofScore,
                                Boolean spoofed, Double threshold, AnalysisStatus analysisStatus,
                                Integer analyzedSegments, String modelVersion,
                                Double durationSeconds, Integer audioBytes, String clientIp,
                                Instant requestedAt, Instant purgedAt, Boolean remotePurged) {
        return new SpoofCheck(userId, callEventId, deviceId, spoofScore, spoofed, threshold,
                analysisStatus, null, analyzedSegments, modelVersion, durationSeconds,
                audioBytes, clientIp, requestedAt, purgedAt, remotePurged);
    }

    /**
     * 판정을 거부한 요청.
     *
     * <p>판정값 4종({@code spoofScore}·{@code spoofed}·{@code threshold}·{@code modelVersion})은
     * null 입니다. 거부했는데 점수가 있는 상태가 생기지 않도록 DB CHECK 제약으로도 막습니다.
     *
     * <p>{@code remotePurged} 는 <b>거부 시점에 따라 달라집니다.</b> 헤더 파싱 단계에서
     * 막았으면 분석 서버에 보낸 적이 없으니 null, FastAPI 응답의 길이가 상한을 넘어
     * 거부했으면 이미 보낸 뒤라 그쪽 파기 여부를 기록해야 합니다.
     */
    public static SpoofCheck rejected(Long userId, Long callEventId, Long deviceId,
                                      RejectedReason reason, Double durationSeconds,
                                      Integer audioBytes, String clientIp,
                                      Instant requestedAt, Instant purgedAt,
                                      Boolean remotePurged) {
        return new SpoofCheck(userId, callEventId, deviceId, null, null, null,
                AnalysisStatus.REJECTED, reason, null, null, durationSeconds,
                audioBytes, clientIp, requestedAt, purgedAt, remotePurged);
    }

    /**
     * Lombok 이 {@code Boolean} 필드에는 {@code getSpoofed()} 만 만듭니다.
     * 기존 호출부가 쓰는 이름을 유지하려고 직접 둡니다.
     */
    public Boolean isSpoofed() {
        return spoofed;
    }

    /** 판정 거부된 요청으로는 분석 결과를 제출할 수 없습니다 */
    public boolean isRejected() {
        return analysisStatus == AnalysisStatus.REJECTED;
    }
}
