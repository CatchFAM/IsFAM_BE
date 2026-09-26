package com.isfam.domain.analysis.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SpoofCheckStatusTest {

    @Test
    void preservesAdditionalConfirmationFromAnalysisServer() {
        assertThat(SpoofCheck.AnalysisStatus.from("additional_confirmation"))
                .isEqualTo(SpoofCheck.AnalysisStatus.ADDITIONAL_CONFIRMATION);
    }

    @Test
    void unknownStatusFailsClosedAsMoreVoiceRequired() {
        assertThat(SpoofCheck.AnalysisStatus.from("new_unknown_status"))
                .isEqualTo(SpoofCheck.AnalysisStatus.MORE_VOICE_REQUIRED);
    }

    /** rejected·failed 는 우리가 정하는 값입니다. 분석 서버가 보내도 받아들이지 않습니다 */
    @Test
    void analysisServerCannotSetServerOwnedStatuses() {
        assertThat(SpoofCheck.AnalysisStatus.from("rejected"))
                .isEqualTo(SpoofCheck.AnalysisStatus.MORE_VOICE_REQUIRED);
        assertThat(SpoofCheck.AnalysisStatus.from("failed"))
                .isEqualTo(SpoofCheck.AnalysisStatus.MORE_VOICE_REQUIRED);
    }

    @Test
    void failedRowHasNoVerdictAndCountsAsDetect() {
        SpoofCheck failed = SpoofCheck.failed("t", 1L, 2L, 3L, "COMMON_009",
                5.0, 160_044, "127.0.0.1", Instant.EPOCH, Instant.EPOCH);

        assertThat(failed.getAnalysisStatus()).isEqualTo(SpoofCheck.AnalysisStatus.FAILED);
        assertThat(failed.getOperation()).isEqualTo(SpoofCheck.Operation.DETECT);
        assertThat(failed.getFailureType()).isEqualTo("COMMON_009");
        assertThat(failed.getSpoofScore()).isNull();
        assertThat(failed.isSpoofed()).isNull();
        assertThat(failed.getThreshold()).isNull();
        assertThat(failed.getModelVersion()).isNull();
        assertThat(failed.getRejectedReason()).isNull();
        assertThat(failed.getRemotePurged()).isNull();
        assertThat(failed.hasVerdict()).isFalse();
        assertThat(failed.isRejected()).isFalse();
    }

    @Test
    void additionalConfirmationStillHasVerdict() {
        SpoofCheck check = SpoofCheck.of("t", 1L, 2L, 3L, 0.3, false, 0.5,
                SpoofCheck.AnalysisStatus.ADDITIONAL_CONFIRMATION, 1, "m", 5.0, 160_044,
                "127.0.0.1", Instant.EPOCH, Instant.EPOCH, true);

        assertThat(check.hasVerdict()).isTrue();
        assertThat(check.getFailureType()).isNull();
    }
}