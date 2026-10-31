package com.isfam.domain.device.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.service.SettingsService;
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
 * 설정 — 알림·자동분석은 계정 단위, 마케팅 수신은 동의 이력, OS 권한은 기기 단위입니다.
 * 응답은 둘을 합쳐 설정 화면 한 장 분량으로 내려줍니다.
 */
@Tag(name = "settings", description = "알림 · 자동분석 · 마케팅 수신 설정 · OS 권한 동기화")
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @Operation(summary = "설정 및 권한 상태 조회")
    @GetMapping
    public SettingsDto.SettingsResponse getSettings(@CurrentUser Long userId,
                                                    @DeviceId Long deviceId) {
        return settingsService.get(userId, deviceId);
    }

    /** 보낸 항목만 바뀝니다. 통화분석 알림 전체와 세부 항목은 함께 보낼 수 없습니다 */
    @Operation(summary = "알림 · 자동분석 설정 수정")
    @PutMapping
    public SettingsDto.SettingsResponse updateSettings(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @RequestBody SettingsDto.UpdateRequest request) {
        return settingsService.update(userId, deviceId, request);
    }

    /**
     * 마케팅 정보 수신 동의·철회. 동의 이력이라 {@code PUT /settings} 와 따로 받습니다.
     * 같은 값을 다시 보내면 이력을 추가하지 않습니다.
     */
    @Operation(summary = "마케팅 정보 수신 동의·철회")
    @PutMapping("/marketing")
    public SettingsDto.SettingsResponse updateMarketing(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.MarketingRequest request) {
        return settingsService.updateMarketing(userId, deviceId, request.marketingAgreed());
    }

    /** 앱 실행 시마다 OS 권한을 재확인해 동기화합니다 */
    @Operation(summary = "클라이언트가 OS 권한 상태 확인 후 서버에 동기화")
    @PutMapping("/permissions")
    public SettingsDto.SettingsResponse syncPermissions(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody SettingsDto.PermissionsRequest request) {
        return settingsService.syncPermissions(userId, deviceId, request);
    }
}