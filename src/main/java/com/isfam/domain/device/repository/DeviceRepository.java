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
     * {@code family_sync} · {@code danger_share} 발송 대상.
     *
     * <p>가족 8명 × 단말 여러 대를 한 번에 가져옵니다. 멤버마다 {@code findByUserId} 를 돌면
     * 쿼리가 멤버 수만큼 나가고, 그게 커밋 직후 비동기 스레드에서 벌어집니다.
     *
     * <p><b>알림 설정을 보지 않습니다.</b> {@code family_sync} 는 알림이 아니라 임베딩
     * 동기화 신호라, 사용자가 알림을 껐다고 막으면 강퇴당한 사람 폰에 남의 생체정보가 남습니다.
     */
    List<Device> findByUserIdInAndPushTokenIsNotNull(Collection<Long> userIds);

    /**
     * {@code user_notification} 발송 대상 — 그 사용자의 푸시 토큰 있는 단말 전부.
     *
     * <p><b>알림 설정은 여기서 거르지 않습니다</b> (알림 설정 세분화 3단계). 설정이 계정 단위
     * ({@code user_settings})로 옮겨가면서, {@code PushDispatcher} 가 알림 종류별 토글을 먼저 보고
     * 보낼 때만 이 메서드를 부릅니다. 예전의 {@code ...AndNotificationEnabledTrue} 는 단말 컬럼과 함께 없앴습니다.
     */
    List<Device> findByUserIdAndPushTokenIsNotNull(Long userId);

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

    // ── Phase 12 추가 ───────────────────────────────────────

    /**
     * 회원 탈퇴 시 삭제 (명세 ⑥).
     *
     * <p><b>위 {@code clearPushTokens} 와 목적이 다릅니다.</b> 그쪽은 죽은 토큰만 비우고
     * row 는 남깁니다(사용자가 돌아올 수 있으므로). 이쪽은 계정이 사라지는 경우라
     * row 자체를 지웁니다 — 단말 모델·OS 버전·권한 스냅샷은 사람과 연결된 정보이고,
     * 계정이 없어지면 보유 근거도 사라집니다.
     *
     * <p>순서상 ⑨의 동기화 푸시보다 먼저 실행되므로, 탈퇴자 본인 폰에는 푸시가 가지
     * 않습니다. 의도한 동작입니다 — 본인은 이미 탈퇴 응답(204)을 받고 로컬을 정리합니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Device d where d.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}