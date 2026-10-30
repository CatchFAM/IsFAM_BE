package com.isfam.infra.push;

import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.infra.push.event.DangerShareEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushDispatcherDangerShareTest {

    private static final Instant NOW = Instant.parse("2026-10-31T01:00:00Z");
    private static final Long DAD = 12L;
    private static final Long MOM = 13L;
    private static final Long DAUGHTER = 14L;

    @Mock PushSender pushSender;
    @Mock DeviceRepository deviceRepository;
    @Mock InvalidPushTokenCleaner invalidPushTokenCleaner;

    PushDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new PushDispatcher(pushSender, deviceRepository, invalidPushTokenCleaner);
        given(pushSender.sendData(anyList(), anyMap())).willReturn(List.of());
    }

    @Test
    @DisplayName("data-only 로 보내고, 일반 알림 푸시는 쓰지 않는다")
    void dataOnly() {
        givenDevices(device(DAD, "dad-token", true));

        dispatcher.onDangerShare(event(DAD, null, true, Map.of(DAD, 1012L)));

        verify(pushSender).sendData(anyList(), anyMap());
        verify(pushSender, never()).sendNotification(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("모르는 번호 공유 — 페이로드에 번호가 없고 impersonated_user_id 키도 없다")
    void unknownNumberPayload() {
        givenDevices(device(DAD, "dad-token", true));

        dispatcher.onDangerShare(event(null, null, true, Map.of(DAD, 1012L)));

        Map<String, String> data = sentPayloads().get(0);
        assertThat(data)
                .containsEntry("type", "danger_share")
                .containsEntry("share_id", "88")
                .containsEntry("notification_id", "1012")
                .containsEntry("sender_user_id", "14")
                .containsEntry("has_phone_number", "true")
                .containsEntry("show_notification", "true")
                .doesNotContainKey("impersonated_user_id")
                .doesNotContainKey("phone_number");
        assertThat(data.values()).noneMatch(value -> value.contains("8842"));
    }

    @Test
    @DisplayName("가족 위장 공유 — impersonated_user_id 가 실리고 has_phone_number 는 false")
    void impersonatedPayload() {
        givenDevices(device(DAD, "dad-token", true));

        dispatcher.onDangerShare(event(DAD, null, false, Map.of(DAD, 1012L)));

        assertThat(sentPayloads().get(0))
                .containsEntry("impersonated_user_id", "12")
                .containsEntry("has_phone_number", "false");
    }

    @Test
    @DisplayName("받는 사람마다 자기 notification_id 로 따로 보낸다")
    void perRecipientNotificationId() {
        givenDevices(device(DAD, "dad-token", true), device(MOM, "mom-token", true));

        Map<Long, Long> recipients = new LinkedHashMap<>();
        recipients.put(DAD, 1012L);
        recipients.put(MOM, 1013L);
        dispatcher.onDangerShare(event(null, null, true, recipients));

        assertThat(sentPayloads())
                .extracting(data -> data.get("notification_id"))
                .containsExactlyInAnyOrder("1012", "1013");
    }

    @Test
    @DisplayName("알림을 끈 단말에도 보내되 show_notification=false")
    void notificationDisabledStillReceives() {
        givenDevices(device(MOM, "mom-phone", true), device(MOM, "mom-tablet", false));

        dispatcher.onDangerShare(event(null, null, true, Map.of(MOM, 1013L)));

        ArgumentCaptor<List<String>> tokens = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(pushSender, times(2)).sendData(tokens.capture(), data.capture());

        for (int i = 0; i < 2; i++) {
            String expected = tokens.getAllValues().get(i).contains("mom-phone") ? "true" : "false";
            assertThat(data.getAllValues().get(i)).containsEntry("show_notification", expected);
        }
    }

    @Test
    @DisplayName("푸시 토큰이 없으면 아무것도 보내지 않는다")
    void noDevices() {
        givenDevices();

        dispatcher.onDangerShare(event(null, null, true, Map.of(DAD, 1012L)));

        verify(pushSender, never()).sendData(anyList(), anyMap());
    }

    @Test
    @DisplayName("발송 중 예외가 나도 밖으로 새지 않는다")
    void swallowsException() {
        given(deviceRepository.findByUserIdInAndPushTokenIsNotNull(any()))
                .willThrow(new RuntimeException("db down"));

        dispatcher.onDangerShare(event(null, null, true, Map.of(DAD, 1012L)));
    }

    // ── 도우미 ──────────────────────────────────────────────

    private DangerShareEvent event(Long impersonated, Object unused, boolean hasPhone,
                                   Map<Long, Long> recipients) {
        return new DangerShareEvent(88L, DAUGHTER, impersonated, hasPhone, recipients, NOW);
    }

    private Device device(Long userId, String token, boolean notificationEnabled) {
        Device device = mock(Device.class);
        given(device.getUserId()).willReturn(userId);
        given(device.getPushToken()).willReturn(token);
        given(device.isNotificationEnabled()).willReturn(notificationEnabled);
        return device;
    }

    private void givenDevices(Device... devices) {
        given(deviceRepository.findByUserIdInAndPushTokenIsNotNull(any()))
                .willReturn(List.of(devices));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> sentPayloads() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pushSender, org.mockito.Mockito.atLeastOnce()).sendData(anyList(), captor.capture());
        return captor.getAllValues();
    }
}