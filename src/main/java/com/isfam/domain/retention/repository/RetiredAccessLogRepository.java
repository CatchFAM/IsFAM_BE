package com.isfam.domain.retention.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 탈퇴자 접속기록을 {@code retired} 스키마로 옮깁니다 (P-04, 법 제21조 제3항).
 *
 * <p><b>왜 옮기나.</b> 접속기록은 고시 제8조 때문에 탈퇴해도 2년을 보관해야 합니다. 법 제21조
 * 제3항은 이렇게 다른 법령 때문에 남기는 개인정보를 <b>다른 개인정보와 분리해</b> 보관하라고
 * 합니다. 서비스용 테이블에 섞여 있으면 "보관 의무 때문에 남긴 것"과 "서비스에 쓰는 것"이
 * 구분되지 않습니다.
 *
 * <p><b>대상은 두 테이블입니다.</b>
 * <ul>
 *   <li>{@code spoof_checks} — {@code user_id} 기준</li>
 *   <li>{@code voiceprint_access_logs} — {@code subject_user_id}(목소리의 주인) 기준</li>
 * </ul>
 * {@code embedding_access_logs} 는 옮기지 않습니다. 한 row 에 받은 사람과 임베딩이 나간
 * 사람들이 함께 들어 있어, 한 명 기준으로 옮기면 남은 가족의 제공 이력까지 따라갑니다.
 * (legal-compliance.md 4-5 미결 항목)
 *
 * <p><b>이관 = 복사 + 원본 삭제, 한 트랜잭션.</b> 복사한 건수와 지운 건수가 다르면 예외를 던져
 * 탈퇴 전체를 롤백합니다. 둘 사이에 같은 사용자의 기록이 새로 들어오는 경우(탈퇴 중 동시 요청)에
 * 복사 안 된 row 가 지워지는 걸 막습니다.
 *
 * <p><b>원칙 R-8 의 예외입니다.</b> 접속기록 삭제 수단은 파기 배치에만 두기로 했지만, 여기는
 * 파기가 아니라 <b>자리 옮기기</b>입니다 — 지우기 전에 같은 트랜잭션에서 retired 에 복사됩니다.
 * 이 클래스를 쓰는 곳은 {@code WithdrawalService} 하나뿐입니다.
 *
 * <p>테이블 구조는 {@code db/phase13-retired.sql} 이 만듭니다(원본과 같은 컬럼 + 끝에
 * {@code retired_at}). 원본에 컬럼이 늘면 retired 에도 같은 위치에 추가해야 하고, 빠뜨리면
 * INSERT 가 실패해 탈퇴가 롤백됩니다 — 조용히 누락되지 않습니다.
 */
@Repository
@RequiredArgsConstructor
public class RetiredAccessLogRepository {

    static final String COPY_SPOOF_CHECKS =
            "insert into retired.spoof_checks select s.*, ? from spoof_checks s where s.user_id = ?";
    static final String DELETE_SPOOF_CHECKS =
            "delete from spoof_checks where user_id = ?";

    static final String COPY_VOICEPRINT_ACCESS_LOGS =
            "insert into retired.voiceprint_access_logs select v.*, ? from voiceprint_access_logs v"
                    + " where v.subject_user_id = ?";
    static final String DELETE_VOICEPRINT_ACCESS_LOGS =
            "delete from voiceprint_access_logs where subject_user_id = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 한 사용자의 접속기록을 전부 retired 로 옮깁니다.
     *
     * <p><b>탈퇴 트랜잭션 안에서만</b> 부릅니다({@link Propagation#MANDATORY}). 이관이 실패하면
     * 탈퇴도 롤백되어야 합니다 — 탈퇴는 됐는데 기록이 서비스 테이블에 남는 상태를 만들지 않습니다.
     *
     * @param retiredAt 이관 시각. 보관기간 기산점은 이 값이 아니라 원래 기록 시각입니다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Moved moveWithdrawnUser(Long userId, Instant retiredAt) {
        Object at = retiredAt.atOffset(ZoneOffset.UTC);

        int spoofChecks = move(COPY_SPOOF_CHECKS, DELETE_SPOOF_CHECKS, at, userId, "spoof_checks");
        int voiceprintAccessLogs = move(COPY_VOICEPRINT_ACCESS_LOGS, DELETE_VOICEPRINT_ACCESS_LOGS,
                at, userId, "voiceprint_access_logs");

        return new Moved(spoofChecks, voiceprintAccessLogs);
    }

    private int move(String copySql, String deleteSql, Object retiredAt, Long userId, String table) {
        int copied = jdbcTemplate.update(copySql, retiredAt, userId);
        int deleted = jdbcTemplate.update(deleteSql, userId);

        if (copied != deleted) {
            // 값은 넣지 않습니다 — 테이블명과 건수만 (P-24)
            throw new IllegalStateException(
                    "접속기록 이관 건수 불일치 — " + table + " 복사 " + copied + " / 삭제 " + deleted);
        }
        return copied;
    }

    /** 옮긴 건수 */
    public record Moved(int spoofChecks, int voiceprintAccessLogs) {
    }
}