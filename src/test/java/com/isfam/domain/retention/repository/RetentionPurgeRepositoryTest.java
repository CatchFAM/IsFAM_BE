package com.isfam.domain.retention.repository;

import com.isfam.domain.retention.config.RetentionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Phase 13-2 — 배치 전용 삭제 문장.
 *
 * <p>실제 DB 에서의 경계값 검증은 {@code db/phase13-verify.sql} 로 합니다(이 커밋에 포함).
 * 여기서는 문장 모양과 바인딩만 봅니다.
 */
@ExtendWith(MockitoExtension.class)
class RetentionPurgeRepositoryTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks RetentionPurgeRepository repository;

    @Test
    void everyTargetUsesStrictLessThanAndLimit() {
        for (RetentionTarget target : RetentionTarget.values()) {
            String sql = RetentionPurgeRepository.statementFor(target);

            assertThat(sql)
                    .startsWith("delete from " + target.table())
                    .contains("t." + target.cutoffColumn() + " < ?")   // 원칙 R-2 — 경계는 미만
                    .contains("order by t.id")
                    .endsWith("limit ?)")
                    .doesNotContain("<=");
        }
    }

    @Test
    void callEventsSkipRowsStillReferencedByAnalysis() {
        assertThat(RetentionPurgeRepository.statementFor(RetentionTarget.CALL_EVENTS))
                .contains("not exists (select 1 from voice_analyses va where va.call_event_id = t.id)");
    }

    @Test
    void spoofChecksSkipRowsStillReferencedByAnalysis() {
        assertThat(RetentionPurgeRepository.statementFor(RetentionTarget.SPOOF_CHECKS))
                .contains("t.requested_at < ?")
                .contains("va.spoof_check_id = t.id");
    }

    @Test
    void refreshTokensUseExpiresAt() {
        assertThat(RetentionPurgeRepository.statementFor(RetentionTarget.REFRESH_TOKENS))
                .contains("t.expires_at < ?");
    }

    @Test
    void dataDeletionLogsIsNeverATarget() {
        for (RetentionTarget target : RetentionTarget.values()) {
            assertThat(target.table()).isNotEqualTo("data_deletion_logs");
        }
    }

    @Test
    void bindsCutoffAsUtcOffsetDateTime() {
        Instant cutoff = Instant.parse("2025-09-27T19:10:00Z");
        given(jdbcTemplate.update(anyString(), eq(cutoff.atOffset(ZoneOffset.UTC)), eq(1_000)))
                .willReturn(3);

        int deleted = repository.deleteChunk(RetentionTarget.NOTIFICATIONS, cutoff, 1_000);

        assertThat(deleted).isEqualTo(3);
        verify(jdbcTemplate).update(
                eq(RetentionPurgeRepository.statementFor(RetentionTarget.NOTIFICATIONS)),
                eq(OffsetDateTime.of(2025, 9, 27, 19, 10, 0, 0, ZoneOffset.UTC)),
                eq(1_000));
    }
}