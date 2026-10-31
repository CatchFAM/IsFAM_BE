package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.analysis.dto.VoiceAnalysisDto;
import com.isfam.domain.analysis.entity.CallEvent;
import com.isfam.domain.analysis.entity.SpoofCheck;
import com.isfam.domain.analysis.repository.CallEventRepository;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import com.isfam.domain.analysis.repository.VoiceAnalysisRepository;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.UserSettingsRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.repository.DangerShareRepository;
import com.isfam.domain.notification.repository.NotificationRepository;
import com.isfam.domain.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 알림 설정 세분화 2단계 — 판정별 알림함 생성.
 * <ul>
 *   <li>위험 → {@code danger_call}, 확인 필요 → {@code needs_check_call} — 설정과 무관하게 항상 생성</li>
 *   <li>안전 → {@code normal_call} — 안전 결과 알림이 켜져 있을 때만. 꺼져 있으면 알림함에도 없음</li>
 *   <li>통화 분석 알림 문구에 가족 이름이 들어가지 않음</li>
 *   <li>5단계 — 자동 분석이 꺼져 있으면 ANALYSIS_001, 아무것도 저장하지 않음</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VoiceAnalysisServiceNotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Long USER_ID = 10L;
    private static final Long CALL_EVENT_ID = 88L;
    private static final Long SPOOF_CHECK_ID = 77L;

    @Mock VoiceAnalysisRepository voiceAnalysisRepository;
    @Mock CallEventRepository callEventRepository;
    @Mock SpoofCheckRepository spoofCheckRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock DangerShareRepository dangerShareRepository;
    @Mock UserSettingsRepository userSettingsRepository;
    @Mock NotificationService notificationService;
    @Mock AutoAnalysisGuard autoAnalysisGuard;
    @Mock ModelVersionPolicy modelVersionPolicy;

    private VoiceAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new VoiceAnalysisService(voiceAnalysisRepository, callEventRepository,
                spoofCheckRepository, notificationRepository, dangerShareRepository,
                userSettingsRepository, notificationService, autoAnalysisGuard, modelVersionPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 위험 · 확인 필요 ───────────────────────────────────

    @Test
    @DisplayName("위험 판정 → danger_call. 설정은 보지 않는다")
    void dangerCreatesDangerCall() {
        givenSubmittable("danger");

        service.submit(USER_ID, request("danger"));

        assertThat(createdNotification().getType()).isEqualTo(Notification.Type.DANGER_CALL);
        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    @DisplayName("확인 필요 판정 → needs_check_call. 설정은 보지 않는다 (꺼도 알림함엔 남음)")
    void needsCheckCreatesNeedsCheckCall() {
        givenSubmittable("needs_check");

        service.submit(USER_ID, request("needs_check"));

        Notification created = createdNotification();
        assertThat(created.getType()).isEqualTo(Notification.Type.NEEDS_CHECK_CALL);
        assertThat(created.getTargetType()).isEqualTo(Notification.TargetType.VOICE_ANALYSIS);
        verifyNoInteractions(userSettingsRepository);
    }

    // ── 안전 ──────────────────────────────────────────────

    @Test
    @DisplayName("안전 판정 + 안전 결과 알림 ON → normal_call")
    void normalWithSettingOnCreatesNormalCall() {
        givenSubmittable("normal");
        UserSettings settings = UserSettings.defaults(USER_ID);
        settings.changeNormalNotification(true);
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));

        service.submit(USER_ID, request("normal"));

        Notification created = createdNotification();
        assertThat(created.getType()).isEqualTo(Notification.Type.NORMAL_CALL);
        // 서버는 누구 목소리인지 모릅니다 — "가족" 이라는 말이 문구에 들어가면 안 됩니다
        assertThat(created.getTitle() + created.getBody()).doesNotContain("가족");
    }

    @Test
    @DisplayName("안전 판정 + 안전 결과 알림 OFF → 알림함에도 만들지 않는다")
    void normalWithSettingOffCreatesNothing() {
        givenSubmittable("normal");
        given(userSettingsRepository.findById(USER_ID))
                .willReturn(Optional.of(UserSettings.defaults(USER_ID)));

        service.submit(USER_ID, request("normal"));

        verify(notificationService, never()).create(any());
    }

    @Test
    @DisplayName("설정 행이 없으면 기본값(안전 OFF)으로 보고 만들지 않는다")
    void normalWithoutSettingsRowCreatesNothing() {
        givenSubmittable("normal");
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        service.submit(USER_ID, request("normal"));

        verify(notificationService, never()).create(any());
    }

    // ── 자동 분석 꺼짐 (5단계) ─────────────────────────────

    @Test
    @DisplayName("자동 분석 OFF → ANALYSIS_001, 분석·알림 모두 저장하지 않는다")
    void autoAnalysisDisabledRejectsBeforeAnything() {
        doThrow(new BusinessException(ErrorCode.ANALYSIS_001))
                .when(autoAnalysisGuard).requireEnabled(USER_ID);

        VoiceAnalysisDto.SubmitRequest request = mock(VoiceAnalysisDto.SubmitRequest.class);

        assertThatThrownBy(() -> service.submit(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_001);

        verifyNoInteractions(modelVersionPolicy, callEventRepository, voiceAnalysisRepository,
                notificationService);
    }

    // ── 내부 ──────────────────────────────────────────────

    private Notification createdNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).create(captor.capture());
        return captor.getValue();
    }

    private void givenSubmittable(String decision) {
        CallEvent callEvent = mock(CallEvent.class);
        given(callEvent.getId()).willReturn(CALL_EVENT_ID);
        given(callEventRepository.findByIdAndUserId(CALL_EVENT_ID, USER_ID))
                .willReturn(Optional.of(callEvent));

        SpoofCheck spoofCheck = mock(SpoofCheck.class);
        given(spoofCheck.hasVerdict()).willReturn(true);
        given(spoofCheck.getCallEventId()).willReturn(CALL_EVENT_ID);
        given(spoofCheck.getId()).willReturn(SPOOF_CHECK_ID);
        given(spoofCheckRepository.findByIdAndUserId(SPOOF_CHECK_ID, USER_ID))
                .willReturn(Optional.of(spoofCheck));

        given(voiceAnalysisRepository.findByCallEventId(CALL_EVENT_ID)).willReturn(Optional.empty());
        given(voiceAnalysisRepository.existsBySpoofCheckId(SPOOF_CHECK_ID)).willReturn(false);
        // 저장한 엔티티를 그대로 돌려줍니다 — 응답 조립이 실제 엔티티 값으로 돌게
        given(voiceAnalysisRepository.saveAndFlush(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private VoiceAnalysisDto.SubmitRequest request(String decision) {
        VoiceAnalysisDto.SubmitRequest request = mock(VoiceAnalysisDto.SubmitRequest.class);
        given(request.callEventId()).willReturn(CALL_EVENT_ID);
        given(request.spoofCheckId()).willReturn(SPOOF_CHECK_ID);
        given(request.riskLevel()).willReturn(12.4);
        given(request.finalDecision()).willReturn(decision);
        given(request.onDeviceModelVersion()).willReturn("ecapa-tdnn-v1");
        given(request.analyzedAt()).willReturn(NOW);
        return request;
    }
}