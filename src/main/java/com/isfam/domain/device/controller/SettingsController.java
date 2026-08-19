package com.isfam.domain.device.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.service.DeviceService;
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
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final DeviceService deviceService;

    @GetMapping
    public SettingsDto.SettingsResponse getSettings(@CurrentUser Long userId,
                                                    @DeviceId Long deviceId) {
        return deviceService.getSettings(userId, deviceId);
    }

    @PutMapping
    public SettingsDto.SettingsResponse updateNotification(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.NotificationRequest request) {
        return deviceService.updateNotificationEnabled(userId, deviceId, request.notificationEnabled());
    }

    /** 앱 실행 시마다 OS 권한을 재확인해 동기화합니다 */
    @PutMapping("/permissions")
    public SettingsDto.SettingsResponse syncPermissions(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.PermissionsRequest request) {
        return deviceService.syncPermissions(userId, deviceId, request);
    }
}