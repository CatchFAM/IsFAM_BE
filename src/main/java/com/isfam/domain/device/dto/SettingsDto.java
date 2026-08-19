package com.isfam.domain.device.dto;

import com.isfam.domain.device.entity.Device;
import jakarta.validation.constraints.NotNull;

/**
 * /settings/** 의 요청·응답.
 *
 * <p>설정 항목이 devices 테이블 컬럼이라 실제로는 단말 단위 설정입니다.
 * 폰 두 대를 쓰면 각각 따로 잡힙니다.
 */
public final class SettingsDto {

    private SettingsDto() {
    }

    /**
     * GET /settings · PUT /settings · PUT /settings/permissions 공용 응답.
     * battery_optimization_ignored 는 저장만 하고 응답에는 넣지 않습니다(명세 형태 유지).
     */
    public record SettingsResponse(boolean notificationEnabled, Permissions permissions) {

        public static SettingsResponse from(Device device) {
            return new SettingsResponse(
                    device.isNotificationEnabled(),
                    new Permissions(
                            device.isNotificationPermission(),
                            device.isMicrophonePermission(),
                            device.isFilePermission()));
        }
    }

    public record Permissions(
            boolean notificationPermission,
            boolean microphonePermission,
            boolean filePermission
    ) {
    }

    public record NotificationRequest(
            @NotNull(message = "알림 설정 값이 필요해요")
            Boolean notificationEnabled
    ) {
    }

    /** 앱 실행 시마다 OS 권한을 다시 확인해 보내옵니다 */
    public record PermissionsRequest(
            @NotNull Boolean notificationPermission,
            @NotNull Boolean microphonePermission,
            @NotNull Boolean filePermission,
            @NotNull Boolean batteryOptimizationIgnored
    ) {
    }
}