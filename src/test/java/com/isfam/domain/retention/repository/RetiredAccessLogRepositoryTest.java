package com.isfam.domain.retention.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

/**
 * Phase 13-4 — 탈퇴자 접속기록 이관.
 */
@ExtendWith(MockitoExtension.class)
class RetiredAccessLogRepositoryTest {

    private static final Long USER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-27T11:00:00Z");

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks RetiredAccessLogRepository repository;

    @Test
    void copiesThenDeletesEachTable() {
        Object at = NOW.atOffset(ZoneOffset.UTC);
        given(jdbcTemplate.update(RetiredAccessLogRepository.COPY_SPOOF_CHECKS, at, USER_ID)).willReturn(3);
        given(jdbcTemplate.update(RetiredAccessLogRepository.DELETE_SPOOF_CHECKS, USER_ID)).willReturn(3);
        given(jdbcTemplate.update(RetiredAccessLogRepository.COPY_VOICEPRINT_ACCESS_LOGS, at, USER_ID)).willReturn(2);
        given(jdbcTemplate.update(RetiredAccessLogRepository.DELETE_VOICEPRINT_ACCESS_LOGS, USER_ID)).willReturn(2);

        RetiredAccessLogRepository.Moved moved = repository.moveWithdrawnUser(USER_ID, NOW);

        assertThat(moved.spoofChecks()).isEqualTo(3);
        assertThat(moved.voiceprintAccessLogs()).isEqualTo(2);

        // 복사가 삭제보다 먼저 — 반대면 복사할 게 없어집니다
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update(RetiredAccessLogRepository.COPY_SPOOF_CHECKS, at, USER_ID);
        order.verify(jdbcTemplate).update(RetiredAccessLogRepository.DELETE_SPOOF_CHECKS, USER_ID);
        order.verify(jdbcTemplate).update(RetiredAccessLogRepository.COPY_VOICEPRINT_ACCESS_LOGS, at, USER_ID);
        order.verify(jdbcTemplate).update(RetiredAccessLogRepository.DELETE_VOICEPRINT_ACCESS_LOGS, USER_ID);
    }

    /** 복사와 삭제 건수가 다르면 예외 → 탈퇴 전체 롤백 */
    @Test
    void countMismatchThrows() {
        Object at = NOW.atOffset(ZoneOffset.UTC);
        given(jdbcTemplate.update(RetiredAccessLogRepository.COPY_SPOOF_CHECKS, at, USER_ID)).willReturn(3);
        given(jdbcTemplate.update(eq(RetiredAccessLogRepository.DELETE_SPOOF_CHECKS), eq(USER_ID))).willReturn(4);

        assertThatThrownBy(() -> repository.moveWithdrawnUser(USER_ID, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spoof_checks");
    }

    @Test
    void statementsTargetRetiredSchemaAndFilterBySubject() {
        assertThat(RetiredAccessLogRepository.COPY_SPOOF_CHECKS)
                .startsWith("insert into retired.spoof_checks")
                .contains("where s.user_id = ?");
        assertThat(RetiredAccessLogRepository.COPY_VOICEPRINT_ACCESS_LOGS)
                .startsWith("insert into retired.voiceprint_access_logs")
                .contains("where v.subject_user_id = ?");
        // embedding_access_logs 는 이관 대상이 아님
        assertThat(RetiredAccessLogRepository.COPY_SPOOF_CHECKS
                + RetiredAccessLogRepository.COPY_VOICEPRINT_ACCESS_LOGS)
                .doesNotContain("embedding_access_logs");
    }
}