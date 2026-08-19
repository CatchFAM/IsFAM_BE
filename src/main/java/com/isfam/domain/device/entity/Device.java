package com.isfam.domain.device.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 단말 1대.
 *
 * <p>주의 — <b>/settings 의 항목이 전부 이 테이블의 컬럼입니다.</b>
 * 알림 on/off, OS 권한 3종, 배터리 최적화 예외가 계정이 아니라 단말 단위로 관리됩니다.
 * 한 계정이 폰 두 대를 쓰면 각각 따로 설정됩니다.
 *
 * <p><b>(user_id, push_token) 은 유니크입니다.</b> POST /devices 의 중복 판정 키인데
 * 인덱스만 걸려 있으면 동시 호출에서 조회-후-저장이 겹쳐 같은 조합의 row 가 둘 생깁니다.
 * 그 뒤로는 {@code findByUserIdAndPushToken} 이 Optional 을 못 만들어
 * <b>그 사용자의 POST /devices 가 영구히 500</b> 이 됩니다 — 복구도 안 됩니다.
 * 제약이 있으면 경합에서 진 쪽만 한 번 실패하고, 앱이 재시도하면 조회 경로로 붙어
 * 정상 처리됩니다(앱은 device_id 가 없으면 실행 시마다 이 API 를 부릅니다).
 * 그래서 서비스 계층에 별도 재시도 로직을 두지 않았습니다.
 *
 * <p>Postgres 유니크 인덱스는 NULL 을 서로 다른 값으로 취급하므로
 * "push_token 없는 요청은 매번 새로 등록됨" 정책과 충돌하지 않습니다.
 *
 * <p>ERD 의 on_device_model_version · embedding_synced_at · last_sync_trigger 는
 * 아직 매핑하지 않았습니다. PUT /devices/me/sync-status(Phase 7)와 MODEL_001 판정(Phase 8)에서
 * 처음 쓰이며, 그때 필드를 추가하면 ddl-auto=update 가 컬럼을 붙입니다.
 */
@Getter
@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(name = "uq_devices_user_push_token",
                columnNames = {"user_id", "push_token"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device extends BaseTimeEntity {

    public enum Platform {
        ANDROID, IOS;

        /** 요청 본문은 "android" 소문자로 옵니다 */
        public static Platform from(String value) {
            for (Platform platform : values()) {
                if (platform.name().equalsIgnoreCase(value)) {
                    return platform;
                }
            }
            throw new IllegalArgumentException("지원하지 않는 플랫폼: " + value);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Platform platform;

    @Column(length = 50)
    private String manufacturer;

    @Column(name = "device_model", length = 100)
    private String deviceModel;

    @Column(name = "os_version", length = 30)
    private String osVersion;

    /** 없으면 동기화 푸시가 안 닿습니다 */
    @Column(name = "push_token")
    private String pushToken;

    /** 기종이 통화녹음을 지원하는가 — 서버가 판정 */
    @Column(name = "call_recording_supported", nullable = false)
    private boolean callRecordingSupported;

    /** 사용자가 OS 설정에서 실제로 켰는가 — 앱이 확인해서 보고 */
    @Column(name = "call_recording_enabled", nullable = false)
    private boolean callRecordingEnabled;

    /** 앱 내 알림 on/off (PUT /settings) */
    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    @Column(name = "notification_permission", nullable = false)
    private boolean notificationPermission;

    @Column(name = "microphone_permission", nullable = false)
    private boolean microphonePermission;

    @Column(name = "file_permission", nullable = false)
    private boolean filePermission;

    @Column(name = "battery_optimization_ignored", nullable = false)
    private boolean batteryOptimizationIgnored;

    private Device(Long userId, Platform platform, String manufacturer, String deviceModel,
                   String osVersion, String pushToken, boolean callRecordingSupported) {
        this.userId = userId;
        this.platform = platform;
        this.manufacturer = manufacturer;
        this.deviceModel = deviceModel;
        this.osVersion = osVersion;
        this.pushToken = pushToken;
        this.callRecordingSupported = callRecordingSupported;
        this.notificationEnabled = true;
    }

    public static Device register(Long userId, Platform platform, String manufacturer,
                                  String deviceModel, String osVersion, String pushToken,
                                  boolean callRecordingSupported) {
        return new Device(userId, platform, manufacturer, deviceModel, osVersion,
                pushToken, callRecordingSupported);
    }

    /**
     * 재등록 시 기기 정보 갱신.
     *
     * <p><b>call_recording_supported 는 건드리지 않습니다.</b> PUT /devices/me/capability 로
     * 앱이 실기기에서 검증한 값이 이미 들어와 있을 수 있어, 기종 판정으로 덮으면 퇴행합니다.
     */
    public void updateRegistration(Platform platform, String manufacturer, String deviceModel,
                                   String osVersion, String pushToken) {
        this.platform = platform;
        this.manufacturer = manufacturer;
        this.deviceModel = deviceModel;
        this.osVersion = osVersion;
        this.pushToken = pushToken;
    }

    /** 등록 시점 권한 스냅샷. 이후에는 PUT /settings/permissions 로 재동기화됩니다 */
    public void updatePermissions(boolean notificationPermission, boolean microphonePermission,
                                  boolean filePermission) {
        this.notificationPermission = notificationPermission;
        this.microphonePermission = microphonePermission;
        this.filePermission = filePermission;
    }

    public void updatePermissions(boolean notificationPermission, boolean microphonePermission,
                                  boolean filePermission, boolean batteryOptimizationIgnored) {
        updatePermissions(notificationPermission, microphonePermission, filePermission);
        this.batteryOptimizationIgnored = batteryOptimizationIgnored;
    }

    public void updateCapability(boolean callRecordingSupported, boolean callRecordingEnabled) {
        this.callRecordingSupported = callRecordingSupported;
        this.callRecordingEnabled = callRecordingEnabled;
    }

    public void updateCallRecordingEnabled(boolean callRecordingEnabled) {
        this.callRecordingEnabled = callRecordingEnabled;
    }

    public void updatePushToken(String pushToken) {
        this.pushToken = pushToken;
    }

    public void updateNotificationEnabled(boolean notificationEnabled) {
        this.notificationEnabled = notificationEnabled;
    }
}