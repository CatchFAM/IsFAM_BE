package com.isfam.domain.device.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.domain.device.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 알림 설정 세분화 1단계 — 계정 단위 설정.
 * <ul>
 *   <li>기본값 = 설정 화면 기본 상태 (안전만 OFF)</li>
 *   <li>마스터는 계산값 — 하위 셋 중 하나라도 ON 이면 ON</li>
 *   <li>마스터 OFF → 셋 다 OFF, 마스터 ON → 셋 다 ON (이전 값 복원 없음)</li>
 *   <li>보낸 항목만 바뀜, 마스터와 하위를 같이 보내면 422</li>
 *   <li>4단계 — 마케팅 수신은 동의 이력에 행 추가, 같은 값이면 추가하지 않음, 최신 행이 현재 상태</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Long USER_ID = 10L;
    private static final Long DEVICE_ID = 5L;

    @Mock UserSettingsRepository userSettingsRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock DeviceRepository deviceRepository;

    private SettingsService service;
    private UserSettings settings;

    @BeforeEach
    void setUp() {
        service = new SettingsService(userSettingsRepository, userAgreementRepository,
                deviceRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        settings = UserSettings.defaults(USER_ID);
    }

    // ── 기본값 ─────────────────────────────────────────────

    @Test
    void defaultsMatchSettingsScreen() {
        assertThat(settings.isAutoAnalysisEnabled()).isTrue();
        assertThat(settings.isDangerNotificationEnabled()).isTrue();
        assertThat(settings.isNeedsCheckNotificationEnabled()).isTrue();
        assertThat(settings.isNormalNotificationEnabled()).isFalse();
        assertThat(settings.isFamilyNewsEnabled()).isTrue();
        assertThat(settings.isDangerShareEnabled()).isTrue();
        assertThat(settings.isAnalysisNotificationEnabled()).isTrue();
    }

    /** 조회는 쓰기를 일으키지 않습니다 — 행이 없으면 기본값으로만 응답 */
    @Test
    void getWithoutRowReturnsDefaultsWithoutCreating() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        SettingsDto.SettingsResponse response = service.get(USER_ID, DEVICE_ID);

        assertThat(response.autoAnalysisEnabled()).isTrue();
        assertThat(response.normalNotificationEnabled()).isFalse();
        assertThat(response.analysisNotificationEnabled()).isTrue();
        verify(userSettingsRepository, never()).insertDefaultsIfAbsent(any(), any());
    }

    // ── 마스터 ─────────────────────────────────────────────

    @Test
    void masterOffTurnsAllThreeOff() {
        givenExistingSettings();

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().master(false).build());

        assertThat(response.analysisNotificationEnabled()).isFalse();
        assertThat(response.dangerNotificationEnabled()).isFalse();
        assertThat(response.needsCheckNotificationEnabled()).isFalse();
        assertThat(response.normalNotificationEnabled()).isFalse();
    }

    /** 켤 때 이전 값을 복원하지 않습니다 — 기본 OFF 였던 안전도 켜집니다 */
    @Test
    void masterOnTurnsAllThreeOnIncludingNormal() {
        givenExistingSettings();
        settings.changeAnalysisNotification(false);

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().master(true).build());

        assertThat(response.dangerNotificationEnabled()).isTrue();
        assertThat(response.needsCheckNotificationEnabled()).isTrue();
        assertThat(response.normalNotificationEnabled()).isTrue();
    }

    @Test
    void turningOneSubOnTurnsMasterOn() {
        givenExistingSettings();
        settings.changeAnalysisNotification(false);

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().normal(true).build());

        assertThat(response.analysisNotificationEnabled()).isTrue();
        assertThat(response.normalNotificationEnabled()).isTrue();
        assertThat(response.dangerNotificationEnabled()).isFalse();
    }

    @Test
    void turningLastSubOffTurnsMasterOff() {
        givenExistingSettings();
        settings.changeNeedsCheckNotification(false); // 위험만 남은 상태

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().danger(false).build());

        assertThat(response.analysisNotificationEnabled()).isFalse();
    }

    // ── 부분 수정 ──────────────────────────────────────────

    @Test
    void onlySentFieldsChange() {
        givenExistingSettings();

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().familyNews(false).build());

        assertThat(response.familyNewsEnabled()).isFalse();
        assertThat(response.autoAnalysisEnabled()).isTrue();
        assertThat(response.dangerNotificationEnabled()).isTrue();
        assertThat(response.dangerShareEnabled()).isTrue();
    }

    @Test
    void autoAnalysisAndHiddenDangerShareCanBeChanged() {
        givenExistingSettings();

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().autoAnalysis(false).dangerShare(false).build());

        assertThat(response.autoAnalysisEnabled()).isFalse();
        assertThat(response.dangerShareEnabled()).isFalse();
    }

    /** 마이그레이션 빈틈 — 행이 없으면 기본값으로 만든 뒤 바꿉니다 */
    @Test
    void updateWithoutRowCreatesDefaultsFirst() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(settings));

        SettingsDto.SettingsResponse response = service.update(USER_ID, DEVICE_ID,
                request().familyNews(false).build());

        verify(userSettingsRepository).insertDefaultsIfAbsent(USER_ID, NOW);
        assertThat(response.familyNewsEnabled()).isFalse();
    }

    // ── 거절 ──────────────────────────────────────────────

    @Test
    void masterAndSubTogetherIsRejected() {
        givenMyDevice();

        assertThatThrownBy(() -> service.update(USER_ID, DEVICE_ID,
                request().master(false).normal(true).build()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COMMON_002);

        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    void emptyRequestIsRejected() {
        givenMyDevice();

        assertThatThrownBy(() -> service.update(USER_ID, DEVICE_ID, request().build()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COMMON_002);
    }

    /** 남의 단말이면 형식 검사보다 먼저 DEVICE_002 — 형식이 틀려도 같은 응답 */
    @Test
    void otherUsersDeviceIsRejectedBeforeValidation() {
        given(deviceRepository.findByIdAndUserId(DEVICE_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER_ID, DEVICE_ID, request().build()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEVICE_002);

        verifyNoInteractions(userSettingsRepository);
    }

    // ── 마케팅 수신 (4단계) ────────────────────────────────

    private static final Instant SIGNUP = Instant.parse("2026-07-12T03:00:00Z");

    @Test
    void marketingStateIsLatestAgreementRow() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
        givenLatestMarketing(false, SIGNUP);

        SettingsDto.SettingsResponse response = service.get(USER_ID, DEVICE_ID);

        assertThat(response.marketingAgreed()).isFalse();
        assertThat(response.marketingChangedAt()).isEqualTo(SIGNUP);
    }

    /** 기록이 없는 오래된 계정 — 거부로 보고 날짜는 null (옵트인) */
    @Test
    void marketingWithoutRecordIsNotAgreed() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));

        SettingsDto.SettingsResponse response = service.get(USER_ID, DEVICE_ID);

        assertThat(response.marketingAgreed()).isFalse();
        assertThat(response.marketingChangedAt()).isNull();
    }

    /** 바꾸면 행을 고치지 않고 새로 쌓습니다 — 시각·현재 약관 버전과 함께 */
    @Test
    void changingMarketingAppendsAgreementRow() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
        givenLatestMarketing(false, SIGNUP);
        given(userAgreementRepository.save(any(UserAgreement.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        SettingsDto.SettingsResponse response = service.updateMarketing(USER_ID, DEVICE_ID, true);

        org.mockito.ArgumentCaptor<UserAgreement> saved =
                org.mockito.ArgumentCaptor.forClass(UserAgreement.class);
        verify(userAgreementRepository).save(saved.capture());
        assertThat(saved.getValue().getAgreementType()).isEqualTo(UserAgreement.Type.MARKETING);
        assertThat(saved.getValue().isAgreed()).isTrue();
        assertThat(saved.getValue().getAgreedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getAgreementVersion()).isEqualTo(UserAgreement.CURRENT_VERSION);

        assertThat(response.marketingAgreed()).isTrue();
        assertThat(response.marketingChangedAt()).isEqualTo(NOW);
    }

    /** 같은 값이면 이력을 추가하지 않고 처음 정한 날짜를 그대로 돌려줍니다 */
    @Test
    void sameMarketingValueIsIdempotent() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
        givenLatestMarketing(false, SIGNUP);

        SettingsDto.SettingsResponse response = service.updateMarketing(USER_ID, DEVICE_ID, false);

        verify(userAgreementRepository, never()).save(any());
        assertThat(response.marketingChangedAt()).isEqualTo(SIGNUP);
    }

    /** 기록이 없던 계정은 거부를 보내도 첫 기록으로 남깁니다 — "물었는데 거부했다"를 남기기 위해 */
    @Test
    void firstMarketingDecisionIsRecordedEvenIfRefused() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
        given(userAgreementRepository.save(any(UserAgreement.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.updateMarketing(USER_ID, DEVICE_ID, false);

        verify(userAgreementRepository).save(any(UserAgreement.class));
    }

    @Test
    void marketingOnOtherUsersDeviceIsRejected() {
        given(deviceRepository.findByIdAndUserId(DEVICE_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMarketing(USER_ID, DEVICE_ID, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEVICE_002);

        verifyNoInteractions(userAgreementRepository);
    }

    private void givenLatestMarketing(boolean agreed, Instant at) {
        given(userAgreementRepository.findFirstByUserIdAndAgreementTypeOrderByAgreedAtDescIdDesc(
                eq(USER_ID), eq(UserAgreement.Type.MARKETING)))
                .willReturn(Optional.of(UserAgreement.of(
                        USER_ID, UserAgreement.Type.MARKETING, agreed, "v1.2", at)));
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenMyDevice() {
        Device device = mock(Device.class);
        given(deviceRepository.findByIdAndUserId(DEVICE_ID, USER_ID)).willReturn(Optional.of(device));
    }

    private void givenExistingSettings() {
        givenMyDevice();
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
    }

    private static RequestBuilder request() {
        return new RequestBuilder();
    }

    private static final class RequestBuilder {
        private Boolean autoAnalysis;
        private Boolean master;
        private Boolean danger;
        private Boolean needsCheck;
        private Boolean normal;
        private Boolean familyNews;
        private Boolean dangerShare;

        RequestBuilder autoAnalysis(boolean value) { this.autoAnalysis = value; return this; }
        RequestBuilder master(boolean value) { this.master = value; return this; }
        RequestBuilder danger(boolean value) { this.danger = value; return this; }
        RequestBuilder normal(boolean value) { this.normal = value; return this; }
        RequestBuilder familyNews(boolean value) { this.familyNews = value; return this; }
        RequestBuilder dangerShare(boolean value) { this.dangerShare = value; return this; }

        SettingsDto.UpdateRequest build() {
            return new SettingsDto.UpdateRequest(
                    autoAnalysis, master, danger, needsCheck, normal, familyNews, dangerShare);
        }
    }
}