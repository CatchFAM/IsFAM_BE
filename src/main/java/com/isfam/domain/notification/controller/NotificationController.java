package com.isfam.domain.notification.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.web.PageQuery;
import com.isfam.domain.notification.dto.NotificationDto;
import com.isfam.domain.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 내 알림함. 전부 인증이 필요하고 본인 것만 다룹니다.
 *
 * <p><b>생성 API 가 없는 게 정상입니다.</b> {@code notifications} 는 서버가 내부에서만
 * INSERT 합니다(service-flow.md 9장).
 *
 * <p>쿼리 파라미터를 {@code @RequestParam(name = ...)} 으로 하나씩 받는 이유는,
 * {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 가 <b>쿼리 파라미터 바인딩에는
 * 적용되지 않기</b> 때문입니다. Jackson 설정은 본문 직렬화에만 관여해서,
 * {@code @ModelAttribute} 로 받으면 {@code page_size} 가 {@code pageSize} 에 안 붙고
 * 조용히 기본값이 됩니다.
 */
@Tag(name = "notification", description = "앱 내 알림함")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * {@code unread_only} 는 선택이고 기본 false 입니다. 앱은 알림함 화면에서 전체를,
     * "안 읽음" 탭에서 true 를 씁니다.
     */
    @Operation(summary = "앱 내 알림목록")
    @GetMapping
    public NotificationDto.ListResponse list(
            @CurrentUser Long userId,
            @RequestParam(name = "unread_only", required = false, defaultValue = "false")
            boolean unreadOnly,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {

        return notificationService.list(userId, unreadOnly, PageQuery.of(page, pageSize));
    }

    /** 앱 실행 시퀀스 ⑨ — 뱃지 숫자만 필요할 때 목록을 통째로 받지 않게 하는 API */
    @Operation(summary = "안 읽은 알림 개수")
    @GetMapping("/unread-count")
    public NotificationDto.UnreadCountResponse unreadCount(@CurrentUser Long userId) {
        return notificationService.unreadCount(userId);
    }

    /** 푸시를 탭했을 때 앱이 호출합니다. 남의 알림이면 404 */
    @Operation(summary = "알림 읽음 처리")
    @PutMapping("/{notificationId}/read")
    public NotificationDto.ReadResponse markRead(
            @CurrentUser Long userId,
            @PathVariable Long notificationId) {

        return notificationService.markRead(userId, notificationId);
    }
}