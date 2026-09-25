package com.isfam.domain.audit.entity;

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
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 음성(생체정보)의 수집·파기 이력 (P-28).
 *
 * <p><b>왜 필요한가.</b> 화자 임베딩은 민감정보(생체인식 특징정보)입니다. 고시 제8조는
 * 민감정보를 처리하는 시스템의 접속기록을 <b>2년</b> 보관하라고 하고, 법 제21조는
 * 파기 의무를 지웁니다. 그런데 Phase 12 전까지 우리는 "언제 이 사람의 목소리가 등록됐고
 * 언제 지워졌는가"를 {@code voiceprints.created_at} / row 존재 여부로만 알 수 있었습니다.
 * <b>지워진 뒤에는 아무것도 남지 않습니다</b> — 파기했다는 사실 자체가 증발합니다.
 *
 * <p>그 상태에서 "정말 지웠습니까"라는 질문을 받으면 답이 "row 가 없으니 지운 겁니다"인데,
 * 그건 <b>처음부터 없었던 것과 구분되지 않습니다.</b> 이 테이블이 그 차이를 만듭니다.
 *
 * <p><b>{@code embedding_access_logs} 와 역할이 다릅니다.</b>
 * <ul>
 *   <li>{@code embedding_access_logs} — 내 목소리가 <b>남의 기기로 나간</b> 이력 (제3자 제공)</li>
 *   <li>이 테이블 — 내 목소리가 서버에 <b>들어오고 지워진</b> 이력 (수집·파기)</li>
 * </ul>
 * 둘을 합치지 않은 이유는 조회 축이 다르기 때문입니다. 저쪽은 "누구에게 갔나",
 * 이쪽은 "언제 들어와서 언제 사라졌나"입니다.
 *
 * <p><b>열람(READ)은 현재 발생하지 않습니다.</b> 임베딩 평문이 서버 밖으로 나가는 경로는
 * {@code GET /family/embeddings} 하나뿐이고 그건 {@code embedding_access_logs} 가 잡습니다.
 * {@code GET /me/voiceprint} 은 등록 <i>상태</i>만 돌려주고 임베딩을 싣지 않습니다.
 * Phase 15 에서 개인정보취급자가 DB 를 직접 조회하는 경로는 pgaudit 이 담당합니다(P-12) —
 * 그건 애플리케이션이 볼 수 없는 영역이라 이 테이블로 잡을 수 없습니다.
 *
 * <p>{@code embedding_access_logs} 와 마찬가지로 <b>수정되지 않는 row</b> 이고,
 * Phase 15 에서 {@code UPDATE}·{@code DELETE} 권한이 회수됩니다(P-13).
 */
@Entity
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "voiceprint_access_logs",
        indexes = {
                @Index(name = "ix_val_subject", columnList = "subject_user_id, created_at"),
                @Index(name = "ix_val_operation", columnList = "operation, created_at"),
                @Index(name = "ix_val_trace", columnList = "trace_id")
        })
public class VoiceprintAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    /**
     * <b>목소리의 주인</b> = 고시 제8조의 "정보주체".
     *
     * <p>열람 요구가 들어오면 이 컬럼으로 조회합니다 — "제 음성 정보가 언제 등록되고
     * 언제 파기됐나요".
     */
    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    /**
     * <b>작업을 수행한 계정</b> = 고시 제8조의 "계정".
     *
     * <p>지금은 항상 {@link #subjectUserId} 와 같습니다. 본인 목소리를 본인이 등록하고
     * 본인이 탈퇴하는 경로밖에 없기 때문입니다. 그런데도 컬럼을 나눈 이유는, 고시가 요구하는
     * 접속기록의 항목이 원래 둘이고(누가 / 누구의 것을), 나중에 둘이 갈라지는 순간
     * — 개인정보취급자가 지우거나, 파기 배치가 도는 순간 — 컬럼을 추가하려면 과거 row 를
     * 어떻게 해석할지가 애매해지기 때문입니다.
     *
     * <p>배치·시스템 작업이면 null 입니다. 사람이 한 일이 아니라는 뜻입니다.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 고시 제8조가 요구하는 "수행업무" */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    private Operation operation;

    /**
     * 파기 사유. {@link Operation#DESTROY} 일 때만 채웁니다.
     *
     * <p>법 제21조는 "목적 달성 시 지체 없이" 파기를 요구합니다. 파기했다는 사실만으로는
     * 부족하고 <b>왜 그 시점에 파기했는가</b>가 있어야 의무 이행이 설명됩니다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30)
    private Reason reason;

    /**
     * 등록한 문장 번호. {@link Operation#REGISTER} 일 때만 채웁니다.
     *
     * <p>같은 문장을 다시 녹음하면 샘플이 교체되는데, 그것도 "새 생체정보를 수집한 것"이라
     * 매번 row 를 남깁니다.
     */
    @Column(name = "sentence_id")
    private Integer sentenceId;

    /** 이 작업으로 생기거나 사라진 {@code voiceprint_samples} 건수 */
    @Column(name = "affected_samples", nullable = false)
    private int affectedSamples;

    /**
     * 이 작업으로 사라진 {@code voiceprints} 건수. 0 또는 1 입니다.
     *
     * <p>{@link Reason#MODEL_CHANGED} 처럼 샘플만 버리고 평균 벡터는 곧바로 다시 만드는
     * 경우가 있어서, 샘플 건수와 따로 셉니다.
     */
    @Column(name = "affected_voiceprints", nullable = false)
    private int affectedVoiceprints;

    @Column(name = "embedding_model_version", length = 50)
    private String embeddingModelVersion;

    /** 고시 제8조의 "접속지" */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /** {@code Clock} 으로 받은 값을 그대로 넣습니다 — 같은 요청의 다른 기록과 시각을 맞추려고 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Operation {
        /** {@code POST /me/voiceprint} — 샘플 등록·교체 */
        REGISTER,
        /** 생체정보 파기 */
        DESTROY
    }

    public enum Reason {
        /** 회원 탈퇴 — {@code DELETE /auth/me} */
        WITHDRAWAL,
        /**
         * 모델 계열 변경으로 기존 샘플 폐기.
         *
         * <p>다른 모델로 뽑은 벡터끼리 평균을 내면 의미 없는 값이 나오는데 등록은 성공하고
         * 탐지만 조용히 망가집니다. 그래서 버리는 건데, 버린 것도 생체정보 파기입니다.
         */
        MODEL_CHANGED,
        /**
         * 정보주체의 파기 요구 — {@code DELETE /me/voiceprint}.
         *
         * <p>Phase 14(P-18)에서 엔드포인트가 생기면 쓰입니다. 미리 정의해 두는 이유는
         * 이 enum 이 DB 에 문자열로 저장돼서, 나중에 값을 추가해도 과거 row 와 충돌이
         * 없기 때문입니다.
         */
        USER_REQUEST
    }

    public static VoiceprintAccessLog register(
            String traceId, Long subjectUserId, Long actorUserId,
            Integer sentenceId, int affectedSamples,
            String embeddingModelVersion, String clientIp, Instant createdAt) {

        VoiceprintAccessLog log = new VoiceprintAccessLog();
        log.traceId = traceId;
        log.subjectUserId = subjectUserId;
        log.actorUserId = actorUserId;
        log.operation = Operation.REGISTER;
        log.sentenceId = sentenceId;
        log.affectedSamples = affectedSamples;
        log.affectedVoiceprints = 0;
        log.embeddingModelVersion = embeddingModelVersion;
        log.clientIp = clientIp;
        log.createdAt = createdAt;
        return log;
    }

    public static VoiceprintAccessLog destroy(
            String traceId, Long subjectUserId, Long actorUserId, Reason reason,
            int affectedSamples, int affectedVoiceprints,
            String embeddingModelVersion, String clientIp, Instant createdAt) {

        VoiceprintAccessLog log = new VoiceprintAccessLog();
        log.traceId = traceId;
        log.subjectUserId = subjectUserId;
        log.actorUserId = actorUserId;
        log.operation = Operation.DESTROY;
        log.reason = reason;
        log.affectedSamples = affectedSamples;
        log.affectedVoiceprints = affectedVoiceprints;
        log.embeddingModelVersion = embeddingModelVersion;
        log.clientIp = clientIp;
        log.createdAt = createdAt;
        return log;
    }
}