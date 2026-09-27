package com.isfam.domain.retention.config;

import com.isfam.domain.audit.service.DataDeletionLogger.Tables;

/**
 * 보관기간 파기 배치의 대상 테이블 (P-03 · P-27).
 *
 * <p><b>선언 순서가 실행 순서입니다.</b> legal-compliance.md 4-3 의 순서를 그대로 옮겼습니다.
 * 순서를 바꾸려면 문서를 먼저 고치세요.
 *
 * <p><b>테이블·컬럼 이름이 SQL 에 문자열로 들어갑니다.</b> 그래서 이 값들은 반드시 여기
 * 상수로만 존재해야 하고, 설정값이나 요청값에서 들어오면 안 됩니다. enum 으로 닫아둔
 * 이유가 이것입니다 — 파기 배치는 DELETE 를 만드는 코드라, 이름 한 줄이 밖에서 들어오는
 * 순간 임의 테이블을 지우는 경로가 됩니다.
 *
 * <p>여기에 없는 테이블은 배치가 건드리지 않습니다. 특히 {@code data_deletion_logs} 는
 * 의도적으로 빠져 있습니다 — 지운 데이터가 사라진 뒤에도 남아야 의미가 있습니다.
 */
public enum RetentionTarget {

    /** 전화번호가 들어 있는 테이블 (L4-c). 30일 */
    PHONE_VERIFICATIONS(Tables.PHONE_VERIFICATIONS, "created_at", null, false),

    /**
     * 만료 후 90일. 기산점이 {@code created_at} 이 아니라 {@code expires_at} 인 유일한 대상입니다 —
     * 발급 시각 기준이면 TTL 을 90일 넘게 늘리는 순간 살아 있는 세션을 지웁니다.
     */
    REFRESH_TOKENS(Tables.REFRESH_TOKENS, "expires_at", null, false),

    /** 90일 (P-27) */
    NOTIFICATIONS(Tables.NOTIFICATIONS, "created_at", null, false),

    /** 1년. {@code call_events} 보다 먼저 — 회원 탈퇴와 같은 순서입니다 */
    VOICE_ANALYSES(Tables.VOICE_ANALYSES, "created_at", null, false),

    /**
     * 1년. <b>분석이 남아 있는 이벤트는 건너뜁니다.</b> 분석은 이벤트보다 몇 분 늦게 생기므로
     * 이벤트만 먼저 기간을 넘는 날이 있습니다. 그날은 두고 다음 날 함께 지워집니다.
     */
    CALL_EVENTS(Tables.CALL_EVENTS, "created_at",
            "not exists (select 1 from voice_analyses va where va.call_event_id = t.id)", false),

    /**
     * 2년 — 접속기록 (고시 제8조). 분석이 참조 중이면 건너뜁니다. 평소에는 분석(1년)이 먼저
     * 지워져서 걸리지 않지만, 설정이 뒤집혀도 참조를 끊지 않게 둡니다.
     */
    SPOOF_CHECKS(Tables.SPOOF_CHECKS, "requested_at",
            "not exists (select 1 from voice_analyses va where va.spoof_check_id = t.id)", true),

    /**
     * 2년 — 탈퇴자의 {@code spoof_checks} (Phase 13-4, P-04).
     * <b>기산점은 이관 시각이 아니라 원래 요청 시각</b>입니다 — 탈퇴했다고 보관기간이 늘어나지 않습니다.
     * 기간은 {@code spoof-checks} 설정을 그대로 씁니다.
     */
    RETIRED_SPOOF_CHECKS(Tables.RETIRED_SPOOF_CHECKS, "requested_at", null, true),

    /** 2년 — 제3자 제공 이력 (고시 제8조) */
    EMBEDDING_ACCESS_LOGS(Tables.EMBEDDING_ACCESS_LOGS, "created_at", null, true),

    /** 2년 — 생체정보 수집·파기 이력 (고시 제8조) */
    VOICEPRINT_ACCESS_LOGS(Tables.VOICEPRINT_ACCESS_LOGS, "created_at", null, true),

    /**
     * 2년 — 탈퇴자의 {@code voiceprint_access_logs} (Phase 13-4, P-04).
     * 기산점은 원래 기록 시각, 기간은 {@code voiceprint-access-logs} 설정을 그대로 씁니다.
     */
    RETIRED_VOICEPRINT_ACCESS_LOGS(Tables.RETIRED_VOICEPRINT_ACCESS_LOGS, "created_at", null, true);

    private final String table;
    private final String cutoffColumn;
    private final String extraCondition;
    private final boolean accessLog;

    RetentionTarget(String table, String cutoffColumn, String extraCondition, boolean accessLog) {
        this.table = table;
        this.cutoffColumn = cutoffColumn;
        this.extraCondition = extraCondition;
        this.accessLog = accessLog;
    }

    /** {@code data_deletion_logs.target_table} 에 들어가는 값과 같습니다 */
    public String table() {
        return table;
    }

    /** 기산점 컬럼. 서버가 찍은 시각만 씁니다 (원칙 R-1) */
    public String cutoffColumn() {
        return cutoffColumn;
    }

    /**
     * 추가 조건. 대상 테이블 별칭은 {@code t} 입니다. 없으면 null.
     */
    public String extraCondition() {
        return extraCondition;
    }

    /**
     * 접속기록인가 — 그렇다면 법정 최소 보관 기간(2년) 아래로 설정할 수 없습니다 (원칙 R-7).
     */
    public boolean isAccessLog() {
        return accessLog;
    }
}