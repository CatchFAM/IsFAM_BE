package com.isfam.domain.device.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 설정 — 실제로는 단말 단위입니다. 항목이 전부 devices 컬럼이라
 * device 도메인에 두고 DeviceService 를 그대로 씁니다.
 */
@Tag(name = "settings", description = "알림 설정 · OS 권한 동기화")
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final DeviceService deviceService;

    @Operation(summary = "계정 설정 및 권한 상태 조회")
    @GetMapping
    public SettingsDto.SettingsResponse getSettings(@CurrentUser Long userId,
                                                    @DeviceId Long deviceId) {
        return deviceService.getSettings(userId, deviceId);
    }

    @Operation(summary = "계정 알림설정 수정")
    @PutMapping
    public SettingsDto.SettingsResponse updateNotification(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.NotificationRequest request) {
        return deviceService.updateNotificationEnabled(userId, deviceId, request.notificationEnabled());
    }

    /** 앱 실행 시마다 OS 권한을 재확인해 동기화합니다 */
    @Operation(summary = "클라이언트가 OS 권한 상태 확인 후 서버에 동기화")
    @PutMapping("/permissions")
    public SettingsDto.SettingsResponse syncPermissions(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.PermissionsRequest request) {
        return deviceService.syncPermissions(userId, deviceId, request);
    }
}