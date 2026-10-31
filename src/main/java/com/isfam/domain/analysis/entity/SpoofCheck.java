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
 * {@code purged_at} 만으로는 Java 쪽만 증명됩니다. 두 값이 함께 있어야
 * "어디에도 안 남는다"고 말할 수 있습니다. (상한이 6초·약 192KB 로 줄면서 FastAPI
 * {@code UploadFile} 의 디스크 스풀 기준 1MB 아래로 내려갔습니다. 그쪽에서는 메모리에만
 * 머물 가능성이 높지만, {@code purged} 가 무엇을 증명하는지는 FastAPI 와 합의 대기 중입니다.)
 * <pre>
 *   select count(*) from spoof_checks where purged_at is null;        -- 항상 0
 *   select count(*) from spoof_checks where remote_purged = false;    -- 항상 0
 * </pre>
 * 두 쿼리는 {@code PurgeMonitor} 가 5분마다 돌립니다(P-21).
 *
 * <p><b>판정하지 못한 요청도 row 를 남깁니다.</b>
 * <ul>
 *   <li>{@link AnalysisStatus#REJECTED} — 우리가 거부 (상한 초과·헤더 불량·자동 분석 꺼짐)</li>
 *   <li>{@link AnalysisStatus#FAILED} — 분석 서버 호출이 실패 (260926, P-20 재개)</li>
 * </ul>
 * 둘 다 판정값 4종이 null 입니다. 음성이 들어왔다는 사실은 판정 여부와 상관없이
 * 접속기록 대상입니다.
 *
 * <p><b>이 테이블은 {@code DELETE /voice-analyses} 로 지워지지 않습니다.</b>
 * 사용자가 분석 기록을 지우는 것과 사업자가 접속기록 보관 의무를 이행하는 것은 별개입니다.
 *
 * <p><b>여기에 화자 관련 컬럼을 추가하지 마세요.</b> 이 엔드포인트는 딥보이스 모델만
 * 실행합니다. 임베딩 추출·가족 대조가 끼어드는 순간 "합성 판별"이 "발신자 식별"로
 * 성격이 바뀌어, 동의를 받을 수 없는 상대방의 민감정보 처리가 됩니다.
 *
 * <p><b>Phase 12 — 고시 제8조 항목 5개를 여기서 전부 뽑을 수 있게 됐습니다.</b>
 * <pre>
 *   계정      → user_id
 *   일시      → requested_at
 *   접속지    → client_ip
 *   정보주체  → user_id (본인 통화이므로 계정과 같음)
 *   수행업무  → operation   ← Phase 12 에서 추가
 * </pre>
 * {@code trace_id} 도 함께 붙어, 같은 요청이 {@code embedding_access_logs} ·
 * {@code voiceprint_access_logs} · {@code data_deletion_logs} 에 남긴 row 와 이어집니다.
 *
 * <p><b>DB CHECK 제약은 {@code db/phase13-0.sql} 이 기준입니다.</b> 이 enum 에 값을 더하면
 * 그 SQL 의 {@code ck_spoof_checks_status} 도 같이 고쳐야 INSERT 가 통과합니다.
 * {@link RejectedReason} 의 값 목록 제약은 {@code db/notification-settings-5.sql} 이 최신입니다
 * (public · retired 두 테이블 모두).
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
                        columnList = "analysis_status, requested_at"),
                @Index(name = "idx_spoof_checks_trace",
                        columnList = "trace_id")
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
     * <p>{@code REJECTED} 는 우리가 판정을 거부한 요청, {@code FAILED} 는 분석 서버 호출이
     * 실패해 판정을 받지 못한 요청입니다. 둘 다 점수가 없고 API 응답으로 나가지 않습니다
     * (앱은 에러 응답을 받습니다).
     */
    public enum AnalysisStatus {
        COMPLETE, ADDITIONAL_CONFIRMATION, MORE_VOICE_REQUIRED, REJECTED, FAILED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }

        /**
         * FastAPI 응답의 analysis_status. 모르는 값이 오면 신뢰할 수 없으므로 재요청 쪽으로 붙입니다.
         *
         * <p>{@code REJECTED}·{@code FAILED} 는 우리가 정하는 값이라 여기서 나오지 않습니다 —
         * 분석 서버가 {@code "failed"} 를 보내도 {@code MORE_VOICE_REQUIRED} 입니다.
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

    /**
     * 판정을 거부한 이유. {@code analysisStatus == REJECTED} 일 때만 채웁니다.
     *
     * <p><b>값을 추가하면 DB 제약도 SQL 로 바꿔야 합니다</b> — {@code public.spoof_checks} 와
     * {@code retired.spoof_checks} 둘 다. retired 쪽을 빠뜨리면 그 사용자가 탈퇴할 때
     * 접속기록 이관 INSERT 가 실패해 탈퇴가 통째로 롤백됩니다.
     */
    public enum RejectedReason {
        /** 상한(6초) 초과. WAV 헤더 계산값 또는 FastAPI 응답값 */
        DURATION_EXCEEDED,
        /** RIFF/WAVE 헤더를 읽을 수 없음. 앱이 wav 가 아닌 것을 보냈거나 파일이 깨짐 */
        INVALID_WAV_HEADER,
        /** 업로드 상한(6초 · 16kHz mono 16bit 기준 약 192KB) 초과 */
        SIZE_EXCEEDED,
        /**
         * 사용자가 "통화 종료 후 자동 분석"을 꺼 둔 상태 (알림 설정 세분화 5단계).
         * 앱은 꺼져 있으면 부르지 않아야 하지만, 들어온 음성은 파기하고 이 사유로 기록합니다.
         */
        AUTO_ANALYSIS_DISABLED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    /**
     * 고시 제8조가 요구하는 <b>"수행업무"</b> (Phase 12).
     *
     * <p><b>{@link AnalysisStatus} 와 일부 겹칩니다.</b> {@code REJECT} 는
     * {@code analysisStatus == REJECTED} 와 같은 뜻입니다. 그런데도 컬럼을 따로 둔 이유는
     * 기록 테이블 네 개가 <b>같은 이름의 컬럼으로 감사 자료를 내보낼 수 있어야</b>
     * 하기 때문입니다.
     *
     * <p>겹치는 값이 <b>따로 놀지 않도록</b> DB CHECK 제약으로 묶어두었습니다 —
     * {@code (analysis_status = 'REJECTED') = (operation = 'REJECT')}.
     * 한쪽만 바꾸면 INSERT 가 실패합니다.
     */
    public enum Operation {
        /**
         * 통화 음성을 받아 합성 여부 판별을 수행함.
         * 분석 서버 호출이 실패한 경우({@code FAILED})도 여기입니다 — 음성을 받아
         * 판별 목적으로 분석 서버에 보냈다는 처리 사실은 같습니다.
         */
        DETECT,
        /** 통화 음성을 받았으나 상한 초과·헤더 불량·자동 분석 꺼짐으로 판별하지 않음 */
        REJECT;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 같은 요청에서 남은 다른 기록·로그와 이어 붙이는 값 (Phase 12).
     *
     * <p><b>엔티티에서는 nullable 입니다.</b> 보강 SQL 이 기존 row 를
     * {@code 'legacy'} 로 채운 뒤 NOT NULL 을 겁니다 — 그 뒤로는 DB 가 강제합니다.
     */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "call_event_id", nullable = false)
    private Long callEventId;

    /** X-Device-Id. 어느 단말에서 음성이 올라왔는지 — 접속기록의 일부입니다 */
    @Column(name = "device_id")
    private Long deviceId;

    /** 고시 제8조의 "수행업무" (Phase 12). nullable 인 이유는 {@link #traceId} 와 같습니다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", length = 20)
    private Operation operation;

    /** 거부·실패 row 에서는 null — 판정을 하지 않았으므로 값이 없습니다 */
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

    /**
     * 분석 서버 호출 실패 종류 (260926). {@code analysisStatus == FAILED} 일 때만 채웁니다.
     *
     * <p>에러 코드 이름({@code COMMON_009} 등) 또는 예외 클래스 단순명입니다.
     * <b>예외 메시지는 넣지 않습니다</b> — {@code data_deletion_logs.failure_type} 과 같은 규칙(P-24).
     * enum 이 아닌 이유도 같습니다: 실패 종류는 계속 늘어납니다.
     */
    @Column(name = "failure_type", length = 64)
    private String failureType;

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

    /** <b>null 이면 파기 누락입니다.</b> 거부·실패 row 에도 반드시 채웁니다 */
    @Column(name = "purged_at")
    private Instant purgedAt;

    /**
     * 분석 서버(FastAPI) 임시 파일 파기 여부 (P-23).
     *
     * <ul>
     *   <li>{@code true}  — 분석 서버가 지웠다고 응답</li>
     *   <li>{@code false} — 지우지 못했다고 응답. <b>파기 실패이며 알람 대상</b></li>
     *   <li>{@code null}  — 셋 중 하나. ① 보내지 않음(사전 거부) ② 이 필드를 안 보내는 버전
     *       ③ <b>보냈지만 응답을 받지 못함({@code FAILED})</b>. ③은 "지웠는지 알 수 없음"이라
     *       실패로 세지는 않되 {@code analysis_status = 'FAILED'} 로 따로 셉니다</li>
     * </ul>
     */
    @Column(name = "remote_purged")
    private Boolean remotePurged;

    private SpoofCheck(String traceId, Long userId, Long callEventId, Long deviceId,
                       Operation operation, Double spoofScore,
                       Boolean spoofed, Double threshold, AnalysisStatus analysisStatus,
                       RejectedReason rejectedReason, String failureType,
                       Integer analyzedSegments, String modelVersion, Double durationSeconds,
                       Integer audioBytes, String clientIp, Instant requestedAt,
                       Instant purgedAt, Boolean remotePurged) {
        this.traceId = traceId;
        this.userId = userId;
        this.callEventId = callEventId;
        this.deviceId = deviceId;
        this.operation = operation;
        this.spoofScore = spoofScore;
        this.spoofed = spoofed;
        this.threshold = threshold;
        this.analysisStatus = analysisStatus;
        this.rejectedReason = rejectedReason;
        this.failureType = failureType;
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
     * 채우게 하면 "저장은 됐는데 파기 기록이 빠진" row 가 생길 수 있습니다.
     *
     * <p>{@code operation} 은 받지 않고 {@link Operation#DETECT} 로 고정합니다 —
     * 어느 팩토리를 썼는지가 곧 수행업무입니다.
     */
    public static SpoofCheck of(String traceId, Long userId, Long callEventId, Long deviceId,
                                Double spoofScore, Boolean spoofed, Double threshold,
                                AnalysisStatus analysisStatus, Integer analyzedSegments,
                                String modelVersion, Double durationSeconds, Integer audioBytes,
                                String clientIp, Instant requestedAt, Instant purgedAt,
                                Boolean remotePurged) {
        return new SpoofCheck(traceId, userId, callEventId, deviceId, Operation.DETECT,
                spoofScore, spoofed, threshold, analysisStatus, null, null, analyzedSegments,
                modelVersion, durationSeconds, audioBytes, clientIp, requestedAt,
                purgedAt, remotePurged);
    }

    /**
     * 판정을 거부한 요청.
     *
     * <p>{@code remotePurged} 는 <b>거부 시점에 따라 달라집니다.</b> 헤더 파싱 단계에서
     * 막았으면 분석 서버에 보낸 적이 없으니 null, FastAPI 응답의 길이가 상한을 넘어
     * 거부했으면 이미 보낸 뒤라 그쪽 파기 여부를 기록해야 합니다.
     */
    public static SpoofCheck rejected(String traceId, Long userId, Long callEventId,
                                      Long deviceId, RejectedReason reason,
                                      Double durationSeconds, Integer audioBytes,
                                      String clientIp, Instant requestedAt,
                                      Instant purgedAt, Boolean remotePurged) {
        return new SpoofCheck(traceId, userId, callEventId, deviceId, Operation.REJECT,
                null, null, null, AnalysisStatus.REJECTED, reason, null, null, null,
                durationSeconds, audioBytes, clientIp, requestedAt, purgedAt, remotePurged);
    }

    /**
     * 분석 서버 호출이 실패한 요청 (260926, P-20 재개).
     *
     * <p>음성은 이미 우리 서버에 들어왔고 분석 서버로도 나갔습니다. 판정이 없다고 row 를
     * 만들지 않으면 "들어왔다는 기록조차 없다"(S1-d)가 이 경로로 되살아납니다.
     *
     * <p>{@code remotePurged} 는 받지 않고 null 입니다 — 응답을 못 받았으니 분석 서버가
     * 지웠는지 알 방법이 없습니다. {@code durationSeconds} 는 WAV 헤더 계산값입니다.
     */
    public static SpoofCheck failed(String traceId, Long userId, Long callEventId,
                                    Long deviceId, String failureType,
                                    Double durationSeconds, Integer audioBytes,
                                    String clientIp, Instant requestedAt, Instant purgedAt) {
        return new SpoofCheck(traceId, userId, callEventId, deviceId, Operation.DETECT,
                null, null, null, AnalysisStatus.FAILED, null, failureType, null, null,
                durationSeconds, audioBytes, clientIp, requestedAt, purgedAt, null);
    }

    /**
     * Lombok 이 {@code Boolean} 필드에는 {@code getSpoofed()} 만 만듭니다.
     * 기존 호출부가 쓰는 이름을 유지하려고 직접 둡니다.
     */
    public Boolean isSpoofed() {
        return spoofed;
    }

    public boolean isRejected() {
        return analysisStatus == AnalysisStatus.REJECTED;
    }

    /**
     * 판정값(점수)이 있는 row 인지. 분석 결과 제출({@code POST /voice-analyses})의 조건입니다.
     * 거부·실패 row 로는 제출할 수 없습니다.
     */
    public boolean hasVerdict() {
        return analysisStatus != AnalysisStatus.REJECTED
                && analysisStatus != AnalysisStatus.FAILED;
    }
}