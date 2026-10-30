package com.isfam.domain.device.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.device.dto.DeviceDto;
import com.isfam.domain.device.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "device", description = "단말 등록 · 상태 · 모델 정보")
@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 가입·재설치·기기변경 때만. 로컬에 device_id 가 있으면 앱이 부르지 않습니다.
     *
     * <p>같은 push_token 이면 기존 row 를 갱신하므로 201 이 아니라 200 입니다.
     */
    @Operation(summary = "가입·로그인 완료 후 단말 정보 등록")
    @PostMapping("/devices")
    public DeviceDto.RegisterResponse register(@CurrentUser Long userId,
                                               @Valid @RequestBody DeviceDto.RegisterRequest request) {
        return deviceService.register(userId, request);
    }

    /** 보호 기능을 쓸 수 있는 단말인지. supported / enabled 로 안내가 갈립니다 */
    @Operation(summary = "단말의 통화 자동녹음 + 온디바이스 분석 지원 여부 조회")
    @GetMapping("/devices/me/capability")
    public DeviceDto.CapabilityResponse getCapability(@CurrentUser Long userId,
                                                      @DeviceId Long deviceId) {
        return deviceService.getCapability(userId, deviceId);
    }

    /** 설정 앱 딥링크 복귀 후 앱이 자체 검증한 결과 반영 */
    @Operation(summary = "단말 지원 결과 서버 반영 (설정 앱 딥링크 복귀 후 검증)")
    @PutMapping("/devices/me/capability")
    public DeviceDto.CapabilityUpdateResponse updateCapability(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody DeviceDto.CapabilityUpdateRequest request) {
        return deviceService.updateCapability(userId, deviceId, request);
    }

    /** FCM 토큰 갱신. 빠뜨리면 동기화 푸시가 통째로 안 닿습니다 */
    @Operation(summary = "푸시 토큰 갱신")
    @PutMapping("/devices/me/push-token")
    public DeviceDto.PushTokenResponse updatePushToken(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody DeviceDto.PushTokenRequest request) {
        return deviceService.updatePushToken(userId, deviceId, request.pushToken());
    }

    /**
     * 임베딩을 로컬에 <b>저장까지</b> 마쳤다는 보고 (Phase 7).
     *
     * <p>{@code GET /family/embeddings} 성공만으로는 폰에 저장됐는지 서버가 알 수 없어,
     * 이 호출이 와야 동기화 완료로 간주합니다. {@code synced_at} 은 GET 응답의 값을
     * 그대로 되돌려주세요 — 폰 로컬 시각을 쓰면 임베딩이 조용히 누락됩니다.
     */
    @Operation(summary = "동기화 시각 서버에 기록")
    @PutMapping("/devices/me/sync-status")
    public DeviceDto.SyncStatusResponse updateSyncStatus(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @Valid @RequestBody DeviceDto.SyncStatusRequest request) {
        return deviceService.updateSyncStatus(userId, deviceId, request);
    }

    /** 온디바이스 화자 모델 버전. 스플래시(비인증)에서 부를 수 있는 두 API 중 하나 */
    @Operation(summary = "서버가 지원하는 온디바이스 모델의 최신 버전 정보 조회")
    @GetMapping("/model-info")
    public DeviceDto.ModelInfoResponse getModelInfo() {
        return deviceService.getModelInfo();
    }
}