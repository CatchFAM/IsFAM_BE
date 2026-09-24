package com.isfam.domain.analysis.entity;

import org.junit.jupiter.api.Test;

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
}
