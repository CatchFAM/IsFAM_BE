package com.isfam.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.notification.entity.Notification;

import java.time.Instant;
import java.util.List;

/** {@code /notifications} 응답 — 요청 본문이 있는 API 가 없어 응답만 있습니다 */
public final class NotificationDto {

    private NotificationDto() {
    }

    /**
     * <b>{@code @JsonInclude(ALWAYS)} 가 필수입니다.</b>
     * {@code application.properties} 에 {@code default-property-inclusion=non_null} 이 걸려 있어
     * 그냥 두면 안 읽은 알림에서 {@code read_at} 필드가 통째로 사라집니다. 명세는
     * {@code "read_at": null} 을 명시하고 있고, 앱이 "필드 없음"과 "null"을 구분해 파싱하면
     * 읽음/안읽음이 뒤집힙니다. {@code target_id} 도 같은 이유입니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(
            Long notificationId,
            Notification.Type type,
            String title,
            String body,
            Notification.TargetType targetType,
            Long targetId,
            Instant readAt,
            Instant createdAt
    ) {
        public static Item from(Notification notification) {
            return new Item(
                    notification.getId(),
                    notification.getType(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getTargetType(),
                    notification.getTargetId(),
                    notification.getReadAt(),
                    notification.getCreatedAt());
        }
    }

    /**
     * {@code GET /notifications}.
     *
     * <p>공용 {@link com.isfam.common.web.PageResponse} 를 쓰지 않은 이유는
     * {@code unread_count} 가 {@code items} 와 {@code page} 사이에 들어가야 하기 때문입니다.
     * 레코드는 선언 순서대로 직렬화되므로 형태를 맞추려면 별도 레코드가 필요합니다.
     *
     * <p>{@code unread_count} 는 <b>현재 페이지가 아니라 전체 미읽음 수</b>입니다.
     * 앱이 목록 응답 하나로 뱃지까지 갱신할 수 있게 하려는 필드라, 페이지 단위로 세면 의미가 없습니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ListResponse(
            List<Item> items,
            long unreadCount,
            int page,
            int pageSize,
            long total
    ) {
    }

    /** {@code GET /notifications/unread-count} — 앱 실행 시퀀스 ⑨ 뱃지용 */
    public record UnreadCountResponse(long unreadCount) {
    }

    /** {@code PUT /notifications/&#123;id&#125;/read} */
    public record ReadResponse(Long notificationId, Instant readAt) {
    }
}