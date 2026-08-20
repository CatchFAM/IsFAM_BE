package com.isfam.domain.notification.repository;

import com.isfam.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // ── Phase 8 추가 ────────────────────────────────────────

    /**
     * 대상이 사라진 알림 정리 — {@code DELETE /voice-analyses/&#123;id&#125;} 에서 씁니다.
     *
     * <p>분석 기록을 지웠는데 {@code danger_call} 알림이 남으면, 사용자가 탭했을 때
     * 404 를 만납니다. 열리지 않는 항목이 알림함에 남는 것보다 함께 사라지는 편이 낫습니다.
     *
     * <p><b>{@code userId} 조건을 빼지 마세요.</b> {@code target_id} 는 analysis_id 라
     * 사용자마다 독립적이지만, 조건이 없으면 우연히 같은 번호를 가진 남의 알림까지 지웁니다.
     *
     * <p>파생 삭제 메서드 대신 벌크 delete 인 이유는 전자가 엔티티를 전부 로딩한 뒤
     * 한 건씩 지우기 때문입니다({@code VoiceprintSampleRepository} 와 같은 이유).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from Notification n
            where n.userId = :userId
              and n.targetType = :targetType
              and n.targetId = :targetId
            """)
    int deleteByTarget(@Param("userId") Long userId,
                       @Param("targetType") Notification.TargetType targetType,
                       @Param("targetId") Long targetId);
}