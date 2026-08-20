package com.isfam.domain.device.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.device.entity.Device;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** POST /devices · /devices/me/** · /model-info 의 요청·응답 */
public final class DeviceDto {

    private DeviceDto() {
    }

    // ── 단말 등록 ───────────────────────────────────────────

    /**
     * 권한 값은 <b>등록 시점 스냅샷</b>입니다. 앱 실행마다 PUT /settings/permissions 로 갱신됩니다.
     * call_recording_supported 는 서버가 기종으로 판정하므로 요청에 없습니다.
     */
    public record RegisterRequest(
            @NotBlank(message = "플랫폼 정보가 필요해요")
            String platform,

            String manufacturer,
            String deviceModel,
            String osVersion,

            /* 없으면 매번 새 단말로 등록되고 동기화 푸시가 닿지 않습니다 */
            String pushToken,

            @NotNull Boolean notificationPermission,
            @NotNull Boolean microphonePermission,
            @NotNull Boolean filePermission,
            @NotNull Boolean callRecordingEnabled
    ) {
    }

    public record RegisterResponse(Long deviceId, boolean callRecordingSupported) {
    }

    // ── 통화녹음 지원 여부 ──────────────────────────────────

    /**
     * supported / enabled 를 분리하는 이유는 안내 문구가 달라서입니다.
     * <ul>
     *   <li>supported=false → "현재 기기에서는 보호 기능을 사용할 수 없습니다"</li>
     *   <li>supported=true, enabled=false → "설정에서 통화 자동녹음을 켜주세요" + 딥링크</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CapabilityResponse(
            boolean callRecordingSupported,
            boolean callRecordingEnabled,
            boolean guidanceRequired,
            String guidanceUrl
    ) {
    }

    /** 설정 앱 딥링크 복귀 후 앱이 자체 검증한 결과 */
    public record CapabilityUpdateRequest(
            @NotNull Boolean callRecordingSupported,
            @NotNull Boolean callRecordingEnabled
    ) {
    }

    public record CapabilityUpdateResponse(
            Long deviceId,
            boolean callRecordingSupported,
            boolean callRecordingEnabled
    ) {
    }

    // ── 푸시 토큰 ───────────────────────────────────────────

    public record PushTokenRequest(
            @NotBlank(message = "푸시 토큰이 필요해요")
            String pushToken
    ) {
    }

    public record PushTokenResponse(Long deviceId, String pushToken) {
    }

    // ── 임베딩 동기화 상태 (Phase 7) ────────────────────────

    /**
     * {@code PUT /devices/me/sync-status}.
     *
     * <p><b>{@code synced_at} 은 반드시 GET /family/embeddings 응답의 값을 그대로</b> 보냅니다.
     * 클라이언트가 자기 시계로 만들면 안 됩니다 — 폰 시각이 서버보다 조금만 앞서도
     * 그 사이에 갱신된 임베딩이 다음 동기화에서 통째로 누락됩니다.
     *
     * <p>{@code synced_at} 을 {@link Instant} 로 받으면 형식이 틀렸을 때
     * COMMON_001(400)이 나갑니다. 이 경우는 앱 버그라 그대로 드러나는 게 맞습니다.
     */
    public record SyncStatusRequest(
            @NotNull(message = "synced_at 이 필요해요")
            Instant syncedAt,

            @NotBlank(message = "sync_trigger 가 필요해요")
            String syncTrigger
    ) {
    }

    public record SyncStatusResponse(
            Long deviceId,
            Instant embeddingSyncedAt,
            Device.SyncTrigger syncTrigger
    ) {
    }

    // ── 모델 정보 (비인증) ──────────────────────────────────

    /**
     * 화자 모델(ECAPA)만 관리합니다. 딥보이스는 서버에서 실행되므로 앱이 받지 않습니다.
     *
     * <p>버전 비교는 <b>앱이</b> 합니다("앱 실행 시 자기 버전과 비교해 불일치하면 업데이트 유도").
     * updateRequired 는 서버가 강제 업데이트를 걸 때 쓰는 전역 플래그입니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ModelInfoResponse(
            String latestModelVersion,
            String minSupportedVersion,
            boolean updateRequired,
            String downloadUrl
    ) {
    }
}