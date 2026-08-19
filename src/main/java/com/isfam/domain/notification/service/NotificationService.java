package com.isfam.domain.notification.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.web.PageQuery;
import com.isfam.domain.notification.dto.NotificationDto;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.repository.NotificationRepository;
import com.isfam.infra.push.event.UserNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;

/**
 * 알림함 조회 · 읽음 처리 · <b>내부 전용</b> 생성.
 *
 * <p>{@code POST /notifications} 같은 API 는 없습니다. 있으면 아무나 남의 알림함에
 * 가짜 위험 알림을 꽂을 수 있습니다. {@link #create} 는 다른 도메인 서비스가
 * 자기 트랜잭션 안에서 부르는 메서드입니다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ── 생성 (내부 전용) ────────────────────────────────────

    /**
     * 호출부의 트랜잭션에 <b>참여</b>합니다({@code REQUIRED}). 멤버십 변경과 알림 INSERT 가
     * 한 트랜잭션에 묶여야, 롤백됐는데 알림만 남는 상황이 생기지 않습니다.
     *
     * <p>반대로 <b>푸시는 커밋 후에</b> 나갑니다 — 여기서 발행하는 이벤트를
     * {@code PushDispatcher} 가 {@code AFTER_COMMIT} 으로 받습니다.
     *
     * <p>{@code saveAndFlush} 인 이유는 {@code notification_id} 가 필요해서입니다.
     * 앱이 푸시를 탭했을 때 {@code PUT /notifications/&#123;id&#125;/read} 를 부르려면
     * 페이로드에 ID 가 들어 있어야 하는데, flush 전에는 ID 가 null 입니다.
     */
    @Transactional
    public Notification create(Notification notification) {
        Notification saved = notificationRepository.saveAndFlush(notification);

        eventPublisher.publishEvent(new UserNotificationEvent(
                saved.getUserId(),
                saved.getId(),
                saved.getTitle(),
                saved.getBody(),
                saved.getTargetType().toJson(),
                saved.getTargetId()));

        return saved;
    }

    /** 가족 멤버 전원처럼 여러 건을 한 번에. 각각 푸시 이벤트가 발행됩니다 */
    @Transactional
    public void createAll(Collection<Notification> notifications) {
        notifications.forEach(this::create);
    }

    // ── 조회 ────────────────────────────────────────────────

    /**
     * {@code GET /notifications} — created_at 내림차순.
     *
     * <p>{@code unreadOnly=true} 일 때는 별도 count 쿼리를 돌리지 않습니다.
     * 그 조건에서는 {@code page.getTotalElements()} 가 곧 전체 미읽음 수라 같은 값입니다.
     */
    @Transactional(readOnly = true)
    public NotificationDto.ListResponse list(Long userId, boolean unreadOnly, PageQuery query) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(
                userId, query.toPageable())
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, query.toPageable());

        long unreadCount = unreadOnly
                ? page.getTotalElements()
                : notificationRepository.countByUserIdAndReadAtIsNull(userId);

        List<NotificationDto.Item> items = page.getContent().stream()
                .map(NotificationDto.Item::from)
                .toList();

        return new NotificationDto.ListResponse(
                items, unreadCount, query.page(), query.pageSize(), page.getTotalElements());
    }

    /** {@code GET /notifications/unread-count} — 앱 실행 시퀀스 ⑨ 뱃지 */
    @Transactional(readOnly = true)
    public NotificationDto.UnreadCountResponse unreadCount(Long userId) {
        return new NotificationDto.UnreadCountResponse(
                notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    // ── 읽음 처리 ───────────────────────────────────────────

    /**
     * 남의 알림이면 404 입니다 — 존재 여부까지 숨깁니다.
     * 403 으로 돌려주면 "그 ID 의 알림은 존재한다"는 사실이 새어 나갑니다.
     *
     * <p>이미 읽은 알림에 다시 호출해도 200 이고 최초 읽은 시각이 유지됩니다
     * ({@link Notification#markRead} 참고). 푸시를 두 번 탭하는 건 흔한 일입니다.
     */
    @Transactional
    public NotificationDto.ReadResponse markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository
                .findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004));

        notification.markRead(clock.instant());

        return new NotificationDto.ReadResponse(notification.getId(), notification.getReadAt());
    }
}