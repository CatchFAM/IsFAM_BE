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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 화자 임베딩 제3자 제공 이력 (P-16 · S2-①②).
 *
 * <p><b>이 테이블이 없으면 답할 수 없는 질문이 둘입니다.</b>
 * <ul>
 *   <li>법 제35조 열람권 — "제 목소리가 어느 기기로 갔는지 알려주세요"(L12-a)</li>
 *   <li>법 제34조 유출 통지 — 임베딩은 민감정보라 1건만 새도 신고 대상인데,
 *       "누구 것이 어디로 갔는가"를 모르면 통지문·신고서를 쓸 수 없습니다(L11-b·c)</li>
 * </ul>
 * 지금까지 남는 건 {@code devices.embedding_synced_at} 과 {@code last_sync_trigger} 둘뿐인데
 * 다음 동기화에서 덮어써집니다. 그래서 이력이 <b>0건</b>이었습니다(S2-a·b).
 *
 * <p><b>{@link #providedUserIds} 가 이 테이블의 본체입니다.</b> 나머지 컬럼은 맥락이고,
 * 위 두 질문에 답하는 건 이 배열 하나입니다 — "당신의 임베딩은 이 기기들로 갔습니다".
 * 조회는 {@code :userId = ANY(provided_user_ids)} 이고, 2년치를 뒤지므로
 * <b>GIN 인덱스가 필요합니다</b>(JPA 로는 선언할 수 없어 마이그레이션에 있습니다).
 *
 * <p><b>row 는 수정되지 않습니다.</b> {@link Immutable} 은 애플리케이션 쪽 통제이고,
 * 진짜 통제는 Phase 15 에서 이 테이블의 {@code UPDATE}·{@code DELETE} 권한을
 * 애플리케이션 계정에서 회수하는 것입니다(P-13, 고시 제8조 위·변조 방지).
 * 그래서 {@code updated_at} 이 없고 {@code BaseTimeEntity} 도 상속하지 않습니다 —
 * 변경되지 않는 row 에 수정 시각 컬럼이 있으면 그 자체가 오해를 만듭니다.
 *
 * <p><b>보관 2년.</b> 민감정보 처리 시스템의 접속기록이라 고시 제8조 기준을 따릅니다
 * ({@code spoof_checks} 와 같은 근거). Phase 13 파기 배치의 대상입니다.
 */
@Entity
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_access_logs",
        indexes = {
                @Index(name = "ix_eal_requester", columnList = "requester_user_id, created_at"),
                @Index(name = "ix_eal_family", columnList = "family_id, created_at"),
                @Index(name = "ix_eal_trace", columnList = "trace_id")
        })
public class EmbeddingAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 같은 요청에서 남은 다른 기록·로그와 이어 붙이는 값입니다.
     * 형식과 생성 규칙은 {@code MdcContext} · {@code TraceIdFilter} 에 있습니다(12-1).
     */
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    /**
     * 임베딩을 받아간 사람 = <b>제공받는 자</b>(법 제17조).
     *
     * <p>{@link #providedUserIds} 와 헷갈리기 쉽습니다. 이쪽은 "가져간 사람" 하나고,
     * 저쪽은 "목소리를 내준 사람들" 여럿입니다. 열람권 조회는 저쪽으로 겁니다.
     */
    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    /**
     * <b>"어느 기기로 갔는가"의 답입니다</b>(L12-a).
     *
     * <p>nullable 로 둔 이유는 단말 확인이 실패하면 제공 자체가 일어나지 않아
     * 정상 흐름에서는 항상 채워지지만, 장래에 배치나 다른 경로가 생겼을 때
     * "기기가 없는 제공"을 null 로 정직하게 남기기 위해서입니다.
     */
    @Column(name = "requester_device_id")
    private Long requesterDeviceId;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 고시 제8조가 요구하는 "수행업무" 항목 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    private Operation operation;

    /**
     * <b>이번 응답에 임베딩이 실제로 실린 사람들.</b>
     *
     * <p>회수 대상({@link #removedUserIds})은 여기 들어가지 않습니다 — 회수는 제공이 아닙니다.
     * 증분 조회라 같은 가족이라도 매번 달라지고, 그게 정상입니다.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "provided_user_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] providedUserIds;

    /** 배열 길이를 그대로 둡니다. 집계·대시보드에서 배열을 펼치지 않으려고 둔 값입니다 */
    @Column(name = "provided_count", nullable = false)
    private int providedCount;

    /**
     * 같은 응답에서 회수를 지시한 멤버.
     *
     * <p>제공 이력은 아니지만 함께 남깁니다 — 탈퇴자가 "내 목소리를 언제 회수했느냐"를
     * 물으면 근거가 되고, 제공과 회수가 한 응답에서 일어나므로 분리할 이유가 없습니다.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "removed_user_ids", columnDefinition = "bigint[]")
    private Long[] removedUserIds;

    @Column(name = "removed_count", nullable = false)
    private int removedCount;

    @Column(name = "embedding_model_version", length = 50)
    private String embeddingModelVersion;

    /** 요청의 {@code since}. null 이면 전량 조회(최초·재설치)입니다 */
    @Column(name = "since_at")
    private Instant sinceAt;

    /** 응답의 {@code synced_at}. 다음 요청의 {@code since} 가 되는 값입니다 */
    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** 고시 제8조의 "접속지". 프록시 전제는 {@code ClientIpResolver} 에 적어 두었습니다 */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /**
     * {@code @CreatedDate} 를 쓰지 않습니다 — 이 값은 {@code Clock} 으로 받은 시각을
     * 그대로 넣습니다. 같은 요청에서 만들어지는 다른 기록과 <b>시각이 정확히 같아야</b>
     * 조사 시 한 덩어리로 묶이고, 테스트에서 고정 시계를 쓸 수 있습니다.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Operation {
        /** {@code GET /family/embeddings} 로 임베딩이 단말에 복제됨 */
        PROVIDE
    }

    /**
     * @param providedUserIds 비어 있으면 안 됩니다 — 제공이 없으면 row 를 만들지 않습니다.
     *                        이유는 {@code EmbeddingAccessLogger} 에 적었습니다.
     */
    public static EmbeddingAccessLog provide(
            String traceId,
            Long requesterUserId,
            Long requesterDeviceId,
            Long familyId,
            List<Long> providedUserIds,
            List<Long> removedUserIds,
            String embeddingModelVersion,
            Instant sinceAt,
            Instant syncedAt,
            String clientIp,
            Instant createdAt) {

        EmbeddingAccessLog log = new EmbeddingAccessLog();
        log.traceId = traceId;
        log.requesterUserId = requesterUserId;
        log.requesterDeviceId = requesterDeviceId;
        log.familyId = familyId;
        log.operation = Operation.PROVIDE;
        log.providedUserIds = toArray(providedUserIds);
        log.providedCount = providedUserIds == null ? 0 : providedUserIds.size();
        log.removedUserIds = toArray(removedUserIds);
        log.removedCount = removedUserIds == null ? 0 : removedUserIds.size();
        log.embeddingModelVersion = embeddingModelVersion;
        log.sinceAt = sinceAt;
        log.syncedAt = syncedAt;
        log.clientIp = clientIp;
        log.createdAt = createdAt;
        return log;
    }

    /** 조회 편의. 엔티티 밖으로 배열을 그대로 내보내지 않습니다 */
    public List<Long> providedUserIdList() {
        return providedUserIds == null ? List.of() : List.of(providedUserIds);
    }

    public List<Long> removedUserIdList() {
        return removedUserIds == null ? List.of() : List.of(removedUserIds);
    }

    private static Long[] toArray(List<Long> values) {
        return values == null ? null : values.toArray(new Long[0]);
    }
}