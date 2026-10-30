package com.isfam.infra.push;

import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.infra.push.event.DangerShareEvent;
import com.isfam.infra.push.event.FamilySyncEvent;
import com.isfam.infra.push.event.UserNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 도메인 이벤트를 FCM 페이로드로 바꿔 발송합니다.
 *
 * <p><b>{@code AFTER_COMMIT} + {@code @Async} 조합이 핵심입니다.</b>
 * <ul>
 *   <li>{@code AFTER_COMMIT} — 롤백된 변경에 대해 푸시가 나가는 걸 막습니다.
 *       멤버십 저장이 실패했는데 "구성원이 참여했어요" 푸시가 가면 앱은 존재하지 않는
 *       멤버의 임베딩을 받으러 옵니다</li>
 *   <li>{@code @Async} — FCM 왕복(수백 ms)이 HTTP 응답을 붙잡지 않게 합니다.
 *       초대 수락 응답이 푸시 발송을 기다릴 이유가 없습니다</li>
 * </ul>
 *
 * <p><b>여기서 예외가 새면 아무도 모릅니다.</b> {@code @Async} void 메서드의 예외는
 * 호출부로 전파되지 않고 조용히 사라집니다. 그래서 본문 전체를 try/catch 로 감쌌습니다 —
 * 로그가 유일한 관측 지점입니다.
 *
 * <p>DB 조회는 리포지토리 메서드가 각자 트랜잭션을 열어 처리합니다. 이 메서드에
 * {@code @Transactional} 을 직접 붙이지 않은 이유는 {@code @Async} ·
 * {@code @TransactionalEventListener} 와 프록시가 3중으로 겹쳐 동작이 미묘해지기 때문입니다.
 * 무효 토큰 정리처럼 쓰기가 필요한 작업만 {@link InvalidPushTokenCleaner} 로 분리했습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher {

    private static final String TYPE_FAMILY_SYNC = "family_sync";
    private static final String TYPE_USER_NOTIFICATION = "user_notification";
    private static final String TYPE_DANGER_SHARE = "danger_share";

    private final PushSender pushSender;
    private final DeviceRepository deviceRepository;
    private final InvalidPushTokenCleaner invalidPushTokenCleaner;

    /**
     * 동기화 푸시 — service-flow.md 9장 페이로드 형식 그대로입니다.
     *
     * <pre>
     * { "type": "family_sync",
     *   "reason": "member_joined | member_removed | voiceprint_updated",
     *   "family_id": "1", "occurred_at": "2026-08-19T..." }
     * </pre>
     *
     * <p>FCM data 맵의 값은 <b>전부 문자열</b>이어야 합니다. {@code family_id} 를 숫자로
     * 넣으려다 컴파일은 되고 런타임에 터지는 실수가 잦습니다.
     *
     * <p>{@code notification_enabled} 를 보지 않습니다 — {@link PushSender#sendData} 주석 참고.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFamilySync(FamilySyncEvent event) {
        try {
            if (event.targetUserIds().isEmpty()) {
                return;
            }

            List<String> tokens = deviceRepository
                    .findByUserIdInAndPushTokenIsNotNull(event.targetUserIds())
                    .stream()
                    .map(Device::getPushToken)
                    .distinct()
                    .toList();

            if (tokens.isEmpty()) {
                log.debug("family_sync 발송 대상 없음 — 푸시 토큰이 등록된 단말이 없습니다 (familyId={})",
                        event.familyId());
                return;
            }

            Map<String, String> data = new LinkedHashMap<>();
            data.put("type", TYPE_FAMILY_SYNC);
            data.put("reason", event.reason().toPayload());
            data.put("family_id", String.valueOf(event.familyId()));
            data.put("occurred_at", event.occurredAt().toString());

            invalidPushTokenCleaner.clear(pushSender.sendData(tokens, data));

            log.debug("family_sync 발송 — {}건 (reason={}, familyId={})",
                    tokens.size(), event.reason().toPayload(), event.familyId());

        } catch (RuntimeException e) {
            log.error("family_sync 발송 중 오류 (familyId={})", event.familyId(), e);
        }
    }

    /**
     * 사용자 알림 푸시.
     *
     * <pre>
     * notification: { title, body }
     * data: { "type": "user_notification", "notification_id": "88",
     *         "target_type": "voice_analysis", "target_id": "501" }
     * </pre>
     *
     * <p>이쪽은 {@code notification_enabled=true} 인 단말에만 갑니다. 알림을 끈 사용자에게
     * 알림창을 띄우면 안 되니까요. 알림함({@code GET /notifications})에는 그대로 남습니다 —
     * 설정을 끈 건 "푸시를 받지 않겠다"지 "기록을 남기지 말라"가 아닙니다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserNotification(UserNotificationEvent event) {
        try {
            List<String> tokens = deviceRepository
                    .findByUserIdAndPushTokenIsNotNullAndNotificationEnabledTrue(event.userId())
                    .stream()
                    .map(Device::getPushToken)
                    .distinct()
                    .toList();

            if (tokens.isEmpty()) {
                log.debug("user_notification 발송 대상 없음 (userId={}, notificationId={})",
                        event.userId(), event.notificationId());
                return;
            }

            Map<String, String> data = new LinkedHashMap<>();
            data.put("type", TYPE_USER_NOTIFICATION);
            data.put("notification_id", String.valueOf(event.notificationId()));
            data.put("target_type", event.targetType());
            // null 을 넣으면 putAllData 에서 NPE 입니다. member_joined 처럼 대상이 없는 알림이 있습니다
            if (event.targetId() != null) {
                data.put("target_id", String.valueOf(event.targetId()));
            }

            invalidPushTokenCleaner.clear(
                    pushSender.sendNotification(tokens, event.title(), event.body(), data));

            log.debug("user_notification 발송 — {}건 (userId={}, notificationId={})",
                    tokens.size(), event.userId(), event.notificationId());

        } catch (RuntimeException e) {
            log.error("user_notification 발송 중 오류 (userId={}, notificationId={})",
                    event.userId(), event.notificationId(), e);
        }
    }

    /**
     * 위험 통화 공유 푸시 — <b>data-only</b>.
     *
     * <pre>
     * data: { "type": "danger_share", "share_id": "88", "notification_id": "1012",
     *         "sender_user_id": "14", "impersonated_user_id": "12",
     *         "has_phone_number": "false", "show_notification": "true",
     *         "occurred_at": "2026-10-31T..." }
     * </pre>
     *
     * <p><b>의심 번호를 싣지 않습니다.</b> FCM 은 구글 미국 서버를 거치고, 통화 상대방은
     * 회원이 아니라 국외 이전 근거가 없습니다. {@code has_phone_number} 가 "true" 면 앱이
     * {@code GET /notifications/danger-share/{share_id}} 로 가져갑니다.
     *
     * <p><b>문구도 싣지 않습니다(data-only).</b> 앱이 {@code alias}(없으면 {@code display_name})로
     * "아빠 번호로 딸에게 전화가 왔는데 목소리가 달랐어요" 같은 문구를 직접 만들어 로컬 알림으로 띄웁니다.
     * 별명은 보는 사람마다 달라 서버가 한 문구로 만들 수 없습니다.
     *
     * <p><b>알림을 끈 단말에도 보냅니다.</b> 이 푸시는 알림이기 전에 "번호를 받아가라"는 신호입니다.
     * 알림을 껐다고 안 보내면 그 폰은 번호를 받지 못하고, 7일이 지나면 서버에서 지워져
     * 영영 볼 수 없습니다. 대신 {@code show_notification} 으로 알림창을 띄울지를 알려줍니다 —
     * 앱은 번호는 항상 받아 저장하고, 알림창은 이 값이 "true" 일 때만 띄웁니다.
     *
     * <p>받는 사람마다 {@code notification_id} 가 달라서 사람별로 나눠 보냅니다. 공간 정원이
     * 8명이라 많아야 7명 × 2회입니다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDangerShare(DangerShareEvent event) {
        try {
            if (event.recipients().isEmpty()) {
                return;
            }

            Map<Long, List<Device>> devicesByUser = deviceRepository
                    .findByUserIdInAndPushTokenIsNotNull(event.recipients().keySet())
                    .stream()
                    .collect(Collectors.groupingBy(Device::getUserId));

            int sent = 0;
            for (Map.Entry<Long, Long> recipient : event.recipients().entrySet()) {
                List<Device> devices = devicesByUser.getOrDefault(recipient.getKey(), List.of());

                Map<Boolean, List<String>> tokensByShow = devices.stream()
                        .collect(Collectors.partitioningBy(
                                Device::isNotificationEnabled,
                                Collectors.mapping(Device::getPushToken,
                                        Collectors.collectingAndThen(Collectors.toList(),
                                                list -> list.stream().distinct().toList()))));

                for (Map.Entry<Boolean, List<String>> group : tokensByShow.entrySet()) {
                    if (group.getValue().isEmpty()) {
                        continue;
                    }
                    Map<String, String> data = dangerSharePayload(event, recipient.getValue(), group.getKey());
                    invalidPushTokenCleaner.clear(pushSender.sendData(group.getValue(), data));
                    sent += group.getValue().size();
                }
            }

            log.debug("danger_share 발송 — {}건 (shareId={}, recipients={})",
                    sent, event.shareId(), event.recipients().size());

        } catch (RuntimeException e) {
            log.error("danger_share 발송 중 오류 (shareId={})", event.shareId(), e);
        }
    }

    private Map<String, String> dangerSharePayload(DangerShareEvent event, Long notificationId,
                                                   boolean showNotification) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", TYPE_DANGER_SHARE);
        data.put("share_id", String.valueOf(event.shareId()));
        data.put("notification_id", String.valueOf(notificationId));
        data.put("sender_user_id", String.valueOf(event.senderUserId()));
        // 모르는 번호로 걸려온 공유는 사칭 대상이 없습니다. null 은 putAllData 에서 NPE 라 키를 뺍니다
        if (event.impersonatedUserId() != null) {
            data.put("impersonated_user_id", String.valueOf(event.impersonatedUserId()));
        }
        data.put("has_phone_number", String.valueOf(event.hasPhoneNumber()));
        data.put("show_notification", String.valueOf(showNotification));
        data.put("occurred_at", event.occurredAt().toString());
        return data;
    }
}