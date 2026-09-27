package com.isfam.domain.retention.repository;

import com.isfam.domain.retention.config.RetentionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

/**
 * 보관기간 경과 데이터 삭제 — <b>파기 배치 전용</b> (P-03, 원칙 R-8).
 *
 * <p><b>왜 일반 리포지토리에 메서드를 추가하지 않았나.</b> {@code SpoofCheckRepository} 에
 * {@code deleteByRequestedAtBefore} 를 붙이면 그 순간부터 어느 서비스든 주입받아 부를 수
 * 있습니다. 접속기록을 지울 수 있는 수단이 일반 API 경로 옆에 놓이는 셈입니다. 삭제는 이
 * 클래스 하나에만 있고, 이 클래스를 쓰는 곳은 {@code RetentionPurgeService} 하나뿐입니다.
 * Phase 15 에서 접속기록 테이블의 DELETE 권한을 애플리케이션 계정에서 회수하고 배치 전용
 * 계정으로 옮기면(P-13) 여기만 데이터소스를 바꾸면 됩니다.
 *
 * <p><b>왜 JPA 가 아니라 JDBC 인가.</b> 대상이 8개 테이블이고 문장 모양이 같습니다. JPQL 로
 * 쓰면 엔티티마다 같은 쿼리를 8번 복사해야 하고, 파생 삭제는 엔티티를 전부 읽은 뒤 한 건씩
 * 지웁니다. 또 {@code PostgreSQL} 의 DELETE 에는 LIMIT 가 없어 서브쿼리가 필요한데, JPQL 은
 * 서브쿼리 안의 LIMIT 를 표현하지 못합니다.
 *
 * <p><b>SQL 에 들어가는 이름은 {@link RetentionTarget} 상수뿐입니다.</b> 값만 바인딩하고,
 * 테이블·컬럼 이름은 enum 에서 만든 문장을 기동 시 한 번 조립해 캐시합니다. 밖에서 받은
 * 문자열이 문장에 들어갈 경로가 없습니다.
 *
 * <p>영속성 컨텍스트를 거치지 않으므로 같은 트랜잭션에서 삭제 대상 엔티티를 미리 읽어두면
 * 안 됩니다. 배치는 읽지 않고 지우기만 하므로 해당 없습니다.
 */
@Repository
@RequiredArgsConstructor
public class RetentionPurgeRepository {

    private static final Map<RetentionTarget, String> STATEMENTS = buildStatements();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 기산점이 {@code cutoff} <b>미만</b>인 행을 최대 {@code limit} 건 지웁니다 (원칙 R-2).
     *
     * <p><b>호출자의 트랜잭션 안에서만 부릅니다</b>({@link Propagation#MANDATORY}). 삭제와 파기
     * 기록({@code DataDeletionLogger})이 같은 트랜잭션이어야 "지웠는데 기록이 없다"가
     * 생기지 않습니다 (원칙 R-5). 트랜잭션 없이 부르면 청크마다 자동 커밋되어 그 보장이
     * 사라지므로, 아예 예외로 막습니다.
     *
     * <p>{@code order by id} 는 청크가 매번 같은 순서로 잘리게 하려는 것입니다. 순서가 없으면
     * PostgreSQL 이 실행 계획에 따라 아무 행이나 고르고, 동시에 도는 요청과 락 순서가
     * 엇갈릴 여지가 생깁니다.
     *
     * @return 실제로 지운 행 수. {@code limit} 보다 작으면 이번 실행에서 지울 게 끝났다는 뜻
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteChunk(RetentionTarget target, Instant cutoff, int limit) {
        // Instant 는 pgjdbc 가 직접 받지 않습니다. UTC 오프셋을 붙여 timestamptz 로 보내면
        // JVM 기본 시간대(KST 일 수 있음)와 무관하게 같은 시각으로 비교됩니다.
        return jdbcTemplate.update(STATEMENTS.get(target),
                cutoff.atOffset(ZoneOffset.UTC), limit);
    }

    /** 테스트·점검용 — 실제로 나가는 문장 */
    static String statementFor(RetentionTarget target) {
        return STATEMENTS.get(target);
    }

    private static Map<RetentionTarget, String> buildStatements() {
        Map<RetentionTarget, String> statements = new EnumMap<>(RetentionTarget.class);
        for (RetentionTarget target : RetentionTarget.values()) {
            String extra = target.extraCondition() == null
                    ? ""
                    : "\n           and " + target.extraCondition();

            statements.put(target, """
                    delete from %1$s
                     where id in (
                            select t.id
                              from %1$s t
                             where t.%2$s < ?%3$s
                             order by t.id
                             limit ?)"""
                    .formatted(target.table(), target.cutoffColumn(), extra));
        }
        return Map.copyOf(statements);
    }
}