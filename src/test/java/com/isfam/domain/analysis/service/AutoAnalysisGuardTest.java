package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AutoAnalysisGuardTest {

    private static final Long USER_ID = 10L;

    @Mock UserSettingsRepository userSettingsRepository;

    private AutoAnalysisGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AutoAnalysisGuard(userSettingsRepository);
    }

    @Test
    @DisplayName("켜져 있으면 통과")
    void enabledPasses() {
        given(userSettingsRepository.findById(USER_ID))
                .willReturn(Optional.of(UserSettings.defaults(USER_ID)));

        assertThatCode(() -> guard.requireEnabled(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("꺼져 있으면 ANALYSIS_001")
    void disabledThrows() {
        UserSettings settings = UserSettings.defaults(USER_ID);
        settings.changeAutoAnalysis(false);
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));

        assertThatThrownBy(() -> guard.requireEnabled(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_001);
    }

    @Test
    @DisplayName("설정 행이 없으면 기본값(ON)")
    void missingRowDefaultsToEnabled() {
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThat(guard.isEnabled(USER_ID)).isTrue();
    }
}