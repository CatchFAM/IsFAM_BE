package com.isfam.domain.audit.repository;

import com.isfam.domain.audit.entity.EmbeddingAccessLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 임베딩 제공 이력 조회·기록.
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않습니다.</b> 상속하면 {@code delete}·
 * {@code deleteAll}·{@code saveAll} 이 딸려 들어오는데, 이건 법정 접속기록 테이블이라
 * 애플리케이션 경로에 삭제 수단이 있으면 안 됩니다(P-13, 고시 제8조 위·변조 방지).
 * 마커 인터페이스 {@link Repository} 를 상속하고 <b>쓸 메서드만 직접 선언</b>하면
 * 그 외 연산은 존재 자체가 없어집니다.
 *
 * <p>Phase 13 파기 배치(2년 경과분)는 <b>별도의 배치 전용 리포지토리</b>에서 처리합니다.
 * 여기에 삭제 메서드를 추가하지 마세요 — "리포지토리에 메서드가 없다"가 지금 유일한
 * 통제이고, Phase 15 에서 DB 권한 회수가 붙으면 그때 이중이 됩니다.
 */
public interface EmbeddingAccessLogRepository extends Repository<EmbeddingAccessLog, Long> {

    EmbeddingAccessLog save(EmbeddingAccessLog log);

    /**
     * <b>열람권(법 제35조) 응답의 원천입니다</b> — "내 목소리가 어느 기기로 갔나".
     *
     * <p>{@code = ANY(...)} 는 JPQL 로 표현되지 않아 네이티브 쿼리입니다.
     * 2년치를 스캔하므로 {@code provided_user_ids} 에 <b>GIN 인덱스</b>가 있어야 합니다
     * (마이그레이션 참조). 인덱스가 빠지면 동작은 하되 데이터가 쌓일수록 느려지고,
     * 느려진 걸 알아차리는 시점은 보통 실제 열람 요구가 들어왔을 때입니다.
     *
     * <p>유출 사고 시 제34조 통지 대상을 뽑는 쿼리도 이것입니다.
     */
    @Query(value = """
            SELECT * FROM embedding_access_logs
             WHERE :userId = ANY(provided_user_ids)
             ORDER BY created_at DESC
            """, nativeQuery = true)
    List<EmbeddingAccessLog> findAllProvidedOf(@Param("userId") Long userId);

    /** 특정 단말이 받아간 이력. 단말 분실·초기화 신고 대응용입니다 */
    List<EmbeddingAccessLog> findByRequesterDeviceIdOrderByCreatedAtDesc(Long requesterDeviceId);

    /** 같은 요청에서 남은 기록을 모을 때 씁니다 */
    List<EmbeddingAccessLog> findByTraceId(String traceId);
}