package com.isfam.domain.notification.repository;

import com.isfam.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 명세 [정책] created_at 내림차순 */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** {@code unread_only=true} */
    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * <b>{@code userId} 조건이 핵심입니다.</b> {@code findById} 로 찾고 나중에 소유자를
     * 비교하면, 비교를 빠뜨린 순간 남의 알림을 읽음 처리할 수 있게 됩니다.
     * 조회 단계에서 막으면 그럴 여지가 없습니다.
     */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);
}