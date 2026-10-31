package com.isfam.infra.push;

import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.domain.device.repository.UserSettingsRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.infra.push.event.UserNotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 알림 설정 세분화 3단계 — 알림 종류별 푸시 필터링.
 * <ul>
 *   <li>위험·확인필요·안전 → 통화분석 알림 하위 토글</li>
 *   <li>구성원 참여·탈퇴 → 가족 소식 알림</li>
 *   <li>꺼져 있으면 푸시만 안 감 (알림함은 NotificationService 가 이미 저장)</li>
 *   <li>설정은 계정 단위 — 그 사람의 모든 단말에 같은 결정</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushDispatcherUserNotificationTest {

    private static final Long USER_ID = 10L;

    @Mock PushSender pushSender;
    @Mock DeviceRepository deviceRepository;
    @Mock UserSettingsRepository userSettingsRepository;
    @Mock InvalidPushTokenCleaner invalidPushTokenCleaner;

    PushDispatcher dispatcher;
    UserSettings settings;

    @BeforeEach
    void setUp() {
        dispatcher = new PushDispatcher(pushSender, deviceRepository, userSettingsRepository,
                invalidPushTokenCleaner);
        settings = UserSettings.defaults(USER_ID);
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));
        given(pushSender.sendNotification(anyList(), anyString(), anyString(), anyMap()))
                .willReturn(List.of());

        Device phone = mock(Device.class);
        given(phone.getPushToken()).willReturn("phone");
        Device tablet = mock(Device.class);
        given(tablet.getPushToken()).willReturn("tablet");
        given(deviceRepository.findByUserIdAndPushTokenIsNotNull(USER_ID))
                .willReturn(List.of(phone, tablet));
    }

    // ── 통화분석 알림 ───────────────────────────────────────

    @Test
    @DisplayName("위험 ON → 모든 단말에 푸시")
    void dangerOnSendsToAllDevices() {
        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));

        verify(pushSender).sendNotification(eq(List.of("phone", "tablet")),
                anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("위험 OFF → 푸시 안 감")
    void dangerOffSkips() {
        settings.changeDangerNotification(false);

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));

        assertNotSent();
    }

    @Test
    @DisplayName("확인 필요 OFF → 푸시 안 감, 위험은 그대로")
    void needsCheckOffSkipsOnlyNeedsCheck() {
        settings.changeNeedsCheckNotification(false);

        dispatcher.onUserNotification(event(Notification.Type.NEEDS_CHECK_CALL));
        assertNotSent();

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));
        verify(pushSender).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("안전 ON → 푸시 (기본값은 OFF)")
    void normalOnSends() {
        settings.changeNormalNotification(true);

        dispatcher.onUserNotification(event(Notification.Type.NORMAL_CALL));

        verify(pushSender).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("마스터 OFF → 세 종류 모두 안 감")
    void masterOffSkipsAllAnalysis() {
        settings.changeAnalysisNotification(false);

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));
        dispatcher.onUserNotification(event(Notification.Type.NEEDS_CHECK_CALL));
        dispatcher.onUserNotification(event(Notification.Type.NORMAL_CALL));

        assertNotSent();
    }

    // ── 가족 소식 ──────────────────────────────────────────

    @Test
    @DisplayName("가족 소식 OFF → 참여·탈퇴 푸시 안 감, 통화분석은 그대로")
    void familyNewsOffSkipsMemberEventsOnly() {
        settings.changeFamilyNews(false);

        dispatcher.onUserNotification(event(Notification.Type.MEMBER_JOINED));
        dispatcher.onUserNotification(event(Notification.Type.MEMBER_LEFT));
        assertNotSent();

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));
        verify(pushSender).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("가족 소식 ON → 참여 푸시, target_id 가 없으면 키도 없음")
    void familyNewsOnSends() {
        dispatcher.onUserNotification(new UserNotificationEvent(USER_ID, 5L,
                Notification.Type.MEMBER_JOINED, "새 구성원이 참여했어요", "길동님이 참여했어요",
                "family", null));

        verify(pushSender).sendNotification(anyList(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(data -> !data.containsKey("target_id")));
    }

    // ── 기타 ──────────────────────────────────────────────

    @Test
    @DisplayName("설정 행이 없으면 기본값 — 위험은 가고 안전은 안 감")
    void missingSettingsUsesDefaults() {
        given(userSettingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        dispatcher.onUserNotification(event(Notification.Type.NORMAL_CALL));
        assertNotSent();

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));
        verify(pushSender).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("꺼져 있으면 단말 조회도 하지 않는다")
    void skippedDoesNotQueryDevices() {
        settings.changeDangerNotification(false);

        dispatcher.onUserNotification(event(Notification.Type.DANGER_CALL));

        verify(deviceRepository, never()).findByUserIdAndPushTokenIsNotNull(USER_ID);
    }

    @Test
    @DisplayName("allowsPush — 모든 알림 종류가 어느 토글에 묶이는지 정해져 있다")
    void everyTypeIsMapped() {
        for (Notification.Type type : Notification.Type.values()) {
            // switch 가 빠짐없이 작성돼 있어 예외 없이 값이 나와야 합니다
            settings.allowsPush(type);
        }
        assertThat(settings.allowsPush(Notification.Type.PROTECTION_WARNING)).isTrue();
    }

    // ── 도우미 ──────────────────────────────────────────────

    private UserNotificationEvent event(Notification.Type type) {
        return new UserNotificationEvent(USER_ID, 1L, type, "제목", "본문", "voice_analysis", 501L);
    }

    private void assertNotSent() {
        verify(pushSender, never()).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }
}