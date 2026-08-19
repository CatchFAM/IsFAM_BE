package com.isfam.domain.device.repository;

import com.isfam.domain.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** POST /devices 중복 판정 키 */
    Optional<Device> findByUserIdAndPushToken(Long userId, String pushToken);

    /** X-Device-Id 소유권 확인 — 남의 단말이면 비어서 돌아옵니다 */
    Optional<Device> findByIdAndUserId(Long id, Long userId);

    List<Device> findByUserId(Long userId);

    // ── Phase 5 푸시 대상 조회 ──────────────────────────────

    /**
     * {@code family_sync} 발송 대상.
     *
     * <p>가족 8명 × 단말 여러 대를 한 번에 가져옵니다. 멤버마다 {@code findByUserId} 를 돌면
     * 쿼리가 멤버 수만큼 나가고, 그게 커밋 직후 비동기 스레드에서 벌어집니다.
     *
     * <p><b>{@code notification_enabled} 를 보지 않습니다.</b> 이건 알림이 아니라 임베딩
     * 동기화 신호라, 사용자가 알림을 껐다고 막으면 강퇴당한 사람 폰에 남의 생체정보가 남습니다.
     */
    List<Device> findByUserIdInAndPushTokenIsNotNull(Collection<Long> userIds);

    /**
     * {@code user_notification} 발송 대상 — 이쪽은 앱 내 알림 설정을 존중합니다.
     *
     * <p>알림함({@code GET /notifications})에는 그대로 남습니다. 설정을 끈 건
     * "푸시를 받지 않겠다"이지 "기록을 남기지 말라"가 아닙니다.
     */
    List<Device> findByUserIdAndPushTokenIsNotNullAndNotificationEnabledTrue(Long userId);

    /**
     * FCM 이 {@code UNREGISTERED}/{@code INVALID_ARGUMENT} 로 돌려준 죽은 토큰을 비웁니다.
     *
     * <p><b>row 를 지우지 않습니다.</b> 단말 row 에는 권한 스냅샷·통화녹음 지원 여부·
     * 동기화 시각이 함께 들어 있어서, 지우면 사용자가 앱을 다시 열 때 온보딩을 처음부터
     * 다시 하게 됩니다.
     *
     * <p>호출은 {@code InvalidPushTokenCleaner} 를 통해서만 하세요 —
     * {@code @Modifying} 은 트랜잭션 없이 실행되지 않습니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Device d set d.pushToken = null where d.pushToken in :tokens")
    int clearPushTokens(@Param("tokens") Collection<String> tokens);
}