package com.isfam.domain.device.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.device.dto.DeviceDto;
import com.isfam.domain.device.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단말 등록과 단말 상태.
 *
 * <p>/model-info 도 여기 둡니다. 온디바이스 모델 버전이라 단말과 짝이고,
 * 컨트롤러를 하나 더 만들 분량이 아닙니다. 이것만 비인증입니다.
 */
@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 가입·재설치·기기변경 때만. 로컬에 device_id 가 있으면 앱이 부르지 않습니다.
     *
     * <p>같은 push_token 이면 기존 row 를 갱신하므로 201 이 아니라 200 입니다.
     */
    @PostMapping("/devices")
    public DeviceDto.RegisterResponse register(@CurrentUser Long userId,
                                               @Valid @RequestBody DeviceDto.RegisterRequest request) {
        return deviceService.register(userId, request);
    }

    /** 보호 기능을 쓸 수 있는 단말인지. supported / enabled 로 안내가 갈립니다 */
    @GetMapping("/devices/me/capability")
    public DeviceDto.CapabilityResponse getCapability(@CurrentUser Long userId,
                                                      @DeviceId Long deviceId) {
        return deviceService.getCapability(userId, deviceId);
    }

    /** 설정 앱 딥링크 복귀 후 앱이 자체 검증한 결과 반영 */
    @PutMapping("/devices/me/capability")
    public DeviceDto.CapabilityUpdateResponse updateCapability(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody DeviceDto.CapabilityUpdateRequest request) {
        return deviceService.updateCapability(userId, deviceId, request);
    }

    /** FCM 토큰 갱신. 빠뜨리면 동기화 푸시가 통째로 안 닿습니다 */
    @PutMapping("/devices/me/push-token")
    public DeviceDto.PushTokenResponse updatePushToken(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody DeviceDto.PushTokenRequest request) {
        return deviceService.updatePushToken(userId, deviceId, request.pushToken());
    }

    /** 온디바이스 화자 모델 버전. 스플래시(비인증)에서 부를 수 있는 두 API 중 하나 */
    @GetMapping("/model-info")
    public DeviceDto.ModelInfoResponse getModelInfo() {
        return deviceService.getModelInfo();
    }
}