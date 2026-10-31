package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.dto.CallEventDto;
import com.isfam.domain.analysis.repository.CallEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 알림 설정 세분화 5단계 — 자동 분석이 꺼져 있으면 통화 이벤트도 받지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CallEventServiceTest {

    private static final Long USER_ID = 10L;

    @Mock CallEventRepository callEventRepository;
    @Mock AutoAnalysisGuard autoAnalysisGuard;

    private CallEventService service;

    @BeforeEach
    void setUp() {
        service = new CallEventService(callEventRepository, autoAnalysisGuard);
    }

    @Test
    @DisplayName("자동 분석 OFF → ANALYSIS_001, 이벤트를 저장하지 않는다")
    void createIsRejectedWhenAutoAnalysisDisabled() {
        doThrow(new BusinessException(ErrorCode.ANALYSIS_001))
                .when(autoAnalysisGuard).requireEnabled(USER_ID);

        assertThatThrownBy(() -> service.create(USER_ID, mock(CallEventDto.CreateRequest.class)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_001);

        verify(autoAnalysisGuard).requireEnabled(USER_ID);
        verifyNoInteractions(callEventRepository);
    }
}