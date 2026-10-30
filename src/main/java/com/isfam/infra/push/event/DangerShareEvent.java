package com.isfam.infra.push.event;

import java.time.Instant;
import java.util.Map;

/**
 * 위험 통화 공유 푸시. {@code danger_shares}·{@code notifications} 가 저장된 뒤 발행됩니다.
 *
 * <p><b>의심 번호를 여기 넣지 마세요.</b> 이 이벤트의 값은 그대로 FCM 페이로드가 되고,
 * FCM 은 구글 미국 서버를 거칩니다. 통화 상대방은 회원이 아니라 국외 이전 근거가 없습니다.
 * 번호가 있다는 사실({@code hasPhoneNumber})만 싣고, 번호는 받는 폰이
 * {@code GET /notifications/danger-share/{shareId}} 로 가져갑니다.
 *
 * <p>문구도 싣지 않습니다. 받는 폰이 {@code alias}(없으면 {@code display_name})로 만듭니다.
 *
 * @param recipients 받는 사람 userId → 그 사람 알림함의 notification_id.
 *                   앱이 탭했을 때 {@code PUT /notifications/{id}/read} 를 부르는 데 씁니다
 */
public record DangerShareEvent(
        Long shareId,
        Long senderUserId,
        Long impersonatedUserId,
        boolean hasPhoneNumber,
        Map<Long, Long> recipients,
        Instant occurredAt
) {
}