package com.isfam.infra.push.event;

/**
 * 알림창에 표시되고 알림함에도 남는 푸시.
 * {@code notifications} row 가 <b>이미 저장된 뒤</b> 발행됩니다 — {@code notificationId} 가
 * 있어야 앱이 탭했을 때 {@code PUT /notifications/&#123;id&#125;/read} 를 부를 수 있습니다.
 *
 * <p>{@code title}/{@code body} 를 엔티티에서 그대로 실어 나릅니다. 여기서 문구를 다시
 * 조립하지 않는 이유는 알림함과 푸시가 갈리면 안 되기 때문입니다 —
 * 탭해서 들어갔더니 다른 문구가 있으면 사용자는 알림이 두 개라고 생각합니다.
 *
 * <p>{@code targetId} 는 null 일 수 있습니다({@code member_joined} 등).
 * FCM data 맵은 null 값을 못 받으므로 {@code PushDispatcher} 에서 걸러집니다.
 */
public record UserNotificationEvent(
        Long userId,
        Long notificationId,
        String title,
        String body,
        String targetType,
        Long targetId
) {
}