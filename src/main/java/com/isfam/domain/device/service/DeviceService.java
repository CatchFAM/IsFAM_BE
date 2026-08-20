package com.isfam.domain.device.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.device.dto.DeviceDto;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 단말 등록 · 통화녹음 지원 여부 · 설정 · 동기화 상태 · 모델 정보.
 *
 * <p>/settings 항목이 devices 컬럼이라 설정도 여기서 함께 다룹니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    /**
     * 통화 자동녹음을 지원하는 제조사.
     *
     * <p>iOS 는 OS 차원에서 통화녹음 API 가 없어 전부 false 입니다.
     * 안드로이드도 제조사·국가별로 갈려서 이 목록은 <b>1차 판정</b>일 뿐이며,
     * 실기기에서 확인한 결과는 PUT /devices/me/capability 로 덮어씁니다.
     */
    private static final Set<String> CALL_RECORDING_MANUFACTURERS =
            Set.of("samsung", "lg", "xiaomi", "oppo", "vivo", "realme", "oneplus");

    private static final String GUIDANCE_URL_FORMAT = "https://isfam.app/guide/%s-call-recording";

    /**
     * 서버 시각보다 이만큼까지 앞선 synced_at 은 시계 오차로 보고 받아줍니다.
     * 그 이상은 클라이언트가 값을 직접 만든 것으로 간주하고 현재 시각으로 끌어내립니다.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final DeviceRepository deviceRepository;
    private final ModelVersionPolicy modelVersionPolicy;
    private final Clock clock;

    /** 서버가 강제 업데이트를 걸 때만 true. 평상시 버전 비교는 앱이 합니다 */
    @Value("${isfam.model.update-required:false}")
    private boolean modelUpdateRequired;

    @Value("${isfam.model.download-url:}")
    private String modelDownloadUrl;

    // ── 단말 등록 ───────────────────────────────────────────

    /**
     * 가입·재설치·기기변경 때만 호출됩니다. 로컬에 device_id 가 있으면 앱이 부르지 않습니다.
     *
     * <p>같은 (본인, push_token) 조합이면 기존 row 를 갱신합니다. 앱이 여러 번 불러도
     * row 가 늘지 않아야, 가입 2단계(signup → devices)가 실패 후 재시도돼도 안전합니다.
     * 갱신도 생성도 200 으로 응답하는 이유가 이것입니다 — 앱은 둘을 구분할 필요가 없습니다.
     *
     * <p>push_token 이 없으면 판정 키가 없어 매번 새로 등록됩니다. 이 단말에는 동기화 푸시가
     * 닿지 않으므로 앱이 토큰을 확보하면 PUT /devices/me/push-token 을 불러야 합니다.
     */
    @Transactional
    public DeviceDto.RegisterResponse register(Long userId, DeviceDto.RegisterRequest request) {
        Device.Platform platform = parsePlatform(request.platform());

        Device device = StringUtils.hasText(request.pushToken())
                ? deviceRepository.findByUserIdAndPushToken(userId, request.pushToken()).orElse(null)
                : null;

        if (device == null) {
            device = deviceRepository.save(Device.register(
                    userId, platform, request.manufacturer(), request.deviceModel(),
                    request.osVersion(), request.pushToken(),
                    detectCallRecordingSupport(platform, request.manufacturer())));
        } else {
            // call_recording_supported 는 유지 — 앱이 실기기에서 검증한 값을 덮으면 퇴행합니다
            device.updateRegistration(platform, request.manufacturer(), request.deviceModel(),
                    request.osVersion(), request.pushToken());
        }

        device.updatePermissions(request.notificationPermission(), request.microphonePermission(),
                request.filePermission());
        device.updateCallRecordingEnabled(request.callRecordingEnabled());

        return new DeviceDto.RegisterResponse(device.getId(), device.isCallRecordingSupported());
    }

    // ── 통화녹음 지원 여부 ──────────────────────────────────

    @Transactional(readOnly = true)
    public DeviceDto.CapabilityResponse getCapability(Long userId, Long deviceId) {
        Device device = findMyDevice(userId, deviceId);

        // 지원은 하는데 안 켠 경우에만 안내가 필요합니다.
        // 미지원 기기는 안내해도 할 수 있는 게 없어 딥링크를 주지 않습니다.
        boolean guidanceRequired =
                device.isCallRecordingSupported() && !device.isCallRecordingEnabled();

        return new DeviceDto.CapabilityResponse(
                device.isCallRecordingSupported(),
                device.isCallRecordingEnabled(),
                guidanceRequired,
                guidanceRequired ? guidanceUrl(device.getManufacturer()) : null);
    }

    /** 설정 앱 딥링크에서 돌아온 뒤 앱이 자체 확인한 결과를 반영합니다 */
    @Transactional
    public DeviceDto.CapabilityUpdateResponse updateCapability(
            Long userId, Long deviceId, DeviceDto.CapabilityUpdateRequest request) {

        Device device = findMyDevice(userId, deviceId);
        device.updateCapability(request.callRecordingSupported(), request.callRecordingEnabled());

        return new DeviceDto.CapabilityUpdateResponse(device.getId(),
                device.isCallRecordingSupported(), device.isCallRecordingEnabled());
    }

    // ── 푸시 토큰 ───────────────────────────────────────────

    /**
     * FCM 토큰은 앱 재설치·데이터 삭제·장기 미사용으로 재발급됩니다.
     * 이 호출을 빠뜨리면 동기화 푸시가 통째로 안 닿습니다.
     */
    @Transactional
    public DeviceDto.PushTokenResponse updatePushToken(Long userId, Long deviceId, String pushToken) {
        Device device = findMyDevice(userId, deviceId);
        device.updatePushToken(pushToken);
        return new DeviceDto.PushTokenResponse(device.getId(), device.getPushToken());
    }

    // ── 임베딩 동기화 상태 (Phase 7) ────────────────────────

    /**
     * {@code PUT /devices/me/sync-status} — 폰이 임베딩을 <b>저장까지</b> 마쳤다는 보고.
     *
     * <p>GET 성공만으로는 로컬 저장 여부를 서버가 알 수 없어서 이 호출이 와야
     * 동기화 완료로 간주합니다. 이 값이 오래 갱신되지 않는 단말은 푸시가 안 닿고 있다는
     * 신호이며, 그 단말에는 이미 나간 가족의 임베딩이 남아 있을 수 있습니다.
     *
     * <p><b>미래 시각은 현재 시각으로 끌어내립니다.</b> {@code synced_at} 은 GET 응답을
     * 그대로 되돌려주게 돼 있지만, 앱이 실수로 자기 시계를 쓰면 폰이 조금만 앞서도
     * 그 사이 갱신분이 다음 since 에서 통째로 누락됩니다. 422 로 거절하면 단말이
     * 영영 "미동기화" 로 남아 관측이 안 되므로, 보정하고 경고를 남깁니다.
     */
    @Transactional
    public DeviceDto.SyncStatusResponse updateSyncStatus(
            Long userId, Long deviceId, DeviceDto.SyncStatusRequest request) {

        Device device = findMyDevice(userId, deviceId);
        Device.SyncTrigger trigger = parseSyncTrigger(request.syncTrigger());

        Instant now = clock.instant();
        Instant syncedAt = request.syncedAt();

        if (syncedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            log.warn("미래 시각의 synced_at 보정 — deviceId={}, 받은 값={}, 보정={}",
                    deviceId, syncedAt, now);
            syncedAt = now;
        }

        device.recordEmbeddingSync(syncedAt, trigger);

        return new DeviceDto.SyncStatusResponse(
                device.getId(), device.getEmbeddingSyncedAt(), device.getLastSyncTrigger());
    }

    // ── 설정 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SettingsDto.SettingsResponse getSettings(Long userId, Long deviceId) {
        return SettingsDto.SettingsResponse.from(findMyDevice(userId, deviceId));
    }

    @Transactional
    public SettingsDto.SettingsResponse updateNotificationEnabled(
            Long userId, Long deviceId, boolean notificationEnabled) {

        Device device = findMyDevice(userId, deviceId);
        device.updateNotificationEnabled(notificationEnabled);
        return SettingsDto.SettingsResponse.from(device);
    }

    /** 앱 실행 시마다 호출됩니다. 권한이 회수됐으면 앱이 홈 화면에 경고 배너를 띄웁니다 */
    @Transactional
    public SettingsDto.SettingsResponse syncPermissions(
            Long userId, Long deviceId, SettingsDto.PermissionsRequest request) {

        Device device = findMyDevice(userId, deviceId);
        device.updatePermissions(request.notificationPermission(), request.microphonePermission(),
                request.filePermission(), request.batteryOptimizationIgnored());
        return SettingsDto.SettingsResponse.from(device);
    }

    // ── 모델 정보 (비인증) ──────────────────────────────────

    /**
     * 스플래시에서 부를 수 있는 두 API 중 하나입니다.
     * 앱이 자기 버전과 비교하므로 서버는 값만 내려줍니다.
     *
     * <p>버전 문자열은 {@link ModelVersionPolicy} 에서 가져옵니다 — MODEL_001 을 판정하는
     * 쪽과 값을 내려주는 쪽이 갈리면, 앱이 "지원한다고 들은 버전"으로 요청했는데 거절당합니다.
     */
    @Transactional(readOnly = true)
    public DeviceDto.ModelInfoResponse getModelInfo() {
        return new DeviceDto.ModelInfoResponse(
                modelVersionPolicy.getLatestVersion(),
                modelVersionPolicy.getMinSupportedVersion(),
                modelUpdateRequired,
                StringUtils.hasText(modelDownloadUrl) ? modelDownloadUrl : null);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 본인 단말인지 확인합니다.
     *
     * <p>없는 id 든 남의 단말이든 같은 DEVICE_002(401)를 냅니다. 존재 여부를 흘리지 않고,
     * 앱의 복구 동작(POST /devices 재등록)도 어느 쪽이든 동일하기 때문입니다.
     */
    private Device findMyDevice(Long userId, Long deviceId) {
        return deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_002,
                        "단말 정보를 확인할 수 없어요. 앱을 다시 실행해 주세요"));
    }

    private Device.Platform parsePlatform(String platform) {
        try {
            return Device.Platform.from(platform);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_001, "지원하지 않는 플랫폼이에요");
        }
    }

    private Device.SyncTrigger parseSyncTrigger(String syncTrigger) {
        try {
            return Device.SyncTrigger.from(syncTrigger);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_001,
                    "sync_trigger 는 push, joined, periodic, foreground 중 하나여야 해요");
        }
    }

    private boolean detectCallRecordingSupport(Device.Platform platform, String manufacturer) {
        if (platform != Device.Platform.ANDROID || !StringUtils.hasText(manufacturer)) {
            return false;
        }
        return CALL_RECORDING_MANUFACTURERS.contains(manufacturer.trim().toLowerCase());
    }

    private String guidanceUrl(String manufacturer) {
        String slug = StringUtils.hasText(manufacturer) ? manufacturer.trim().toLowerCase() : "android";
        return GUIDANCE_URL_FORMAT.formatted(slug);
    }
}