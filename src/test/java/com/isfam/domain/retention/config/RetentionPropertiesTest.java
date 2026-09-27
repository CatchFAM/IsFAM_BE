package com.isfam.domain.retention.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13-2 — 보관 기간 설정.
 * 기본값이 legal-compliance.md 4-2 표와 같은지, 법정 최소치 아래로 내려가지 않는지.
 */
class RetentionPropertiesTest {

    private static RetentionProperties.Days days(Integer spoofChecks) {
        return new RetentionProperties.Days(null, null, null, null, null, spoofChecks, null, null);
    }

    @Test
    void defaultsMatchPolicyTable() {
        RetentionProperties props = new RetentionProperties(null, null, null, null, null);

        assertThat(props.daysFor(RetentionTarget.PHONE_VERIFICATIONS)).isEqualTo(30);
        assertThat(props.daysFor(RetentionTarget.REFRESH_TOKENS)).isEqualTo(90);
        assertThat(props.daysFor(RetentionTarget.NOTIFICATIONS)).isEqualTo(90);
        assertThat(props.daysFor(RetentionTarget.CALL_EVENTS)).isEqualTo(365);
        assertThat(props.daysFor(RetentionTarget.VOICE_ANALYSES)).isEqualTo(365);
        assertThat(props.daysFor(RetentionTarget.SPOOF_CHECKS)).isEqualTo(730);
        assertThat(props.daysFor(RetentionTarget.EMBEDDING_ACCESS_LOGS)).isEqualTo(730);
        assertThat(props.daysFor(RetentionTarget.VOICEPRINT_ACCESS_LOGS)).isEqualTo(730);

        assertThat(props.cron()).isEqualTo("0 10 4 * * *");
        assertThat(props.zone()).isEqualTo("Asia/Seoul");
        assertThat(props.chunkSize()).isEqualTo(1_000);
        assertThat(props.maxChunksPerRun()).isEqualTo(100);
    }

    /** 대상이 늘었는데 Days 에 필드를 안 붙이면 여기서 걸립니다 */
    @Test
    void everyTargetHasDays() {
        RetentionProperties props = new RetentionProperties(null, null, null, null, null);

        Arrays.stream(RetentionTarget.values())
                .forEach(target -> assertThat(props.daysFor(target)).isPositive());
    }

    @Test
    void accessLogBelowLegalMinimumIsRejected() {
        assertThatThrownBy(() -> days(729))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spoof_checks");
    }

    @Test
    void accessLogAtLegalMinimumIsAccepted() {
        assertThat(days(730).spoofChecks()).isEqualTo(730);
    }

    @Test
    void longerThanMinimumIsAccepted() {
        assertThat(days(1_095).spoofChecks()).isEqualTo(1_095);
    }

    @Test
    void nonAccessLogCanBeShort() {
        RetentionProperties.Days days =
                new RetentionProperties.Days(7, null, null, null, null, null, null, null);
        assertThat(days.phoneVerifications()).isEqualTo(7);
    }

    @Test
    void zeroDaysIsRejected() {
        assertThatThrownBy(() ->
                new RetentionProperties.Days(0, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chunkSizeOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new RetentionProperties(null, null, 0, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RetentionProperties(null, null, 10_001, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidZoneIsRejected() {
        assertThatThrownBy(() -> new RetentionProperties(null, "Asia/Seul", null, null, null))
                .isInstanceOf(RuntimeException.class);
    }

    /** 실행 순서 — call_events 는 voice_analyses 뒤, spoof_checks 는 voice_analyses 뒤 */
    @Test
    void executionOrderKeepsReferencesSafe() {
        assertThat(RetentionTarget.VOICE_ANALYSES.ordinal())
                .isLessThan(RetentionTarget.CALL_EVENTS.ordinal())
                .isLessThan(RetentionTarget.SPOOF_CHECKS.ordinal());
    }
}