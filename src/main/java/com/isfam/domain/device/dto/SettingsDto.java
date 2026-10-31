package com.isfam.domain.device.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.entity.UserSettings;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * /settings/** 의 요청·응답.
 *
 * <p><b>섞여 있는 세 출처.</b> 알림·자동분석은 계정 단위({@code user_settings}),
 * 마케팅 수신은 동의 이력({@code user_agreements} 의 최신 MARKETING 행),
 * OS 권한 3종은 기기 단위({@code devices})입니다. 응답은 셋을 합쳐 설정 화면 한 장 분량으로 내려줍니다.
 */
public final class SettingsDto {

    private SettingsDto() {
    }

    /**
     * GET /settings · PUT /settings · PUT /settings/marketing · PUT /settings/permissions 공용 응답.
     *
     * <p>{@code analysis_notification_enabled}(마스터)는 저장값이 아니라 계산값입니다 —
     * 위험·확인필요·안전 중 하나라도 켜져 있으면 true.
     *
     * <p>{@code danger_share_enabled} 는 앱 화면에 노출하지 않는 항목입니다. 응답에는 싣지만
     * 앱은 무시하면 됩니다.
     *
     * <p>{@code marketing_changed_at} 은 마케팅 수신을 마지막으로 정한 시각입니다 —
     * 가입 때 정했으면 가입 시각, 설정에서 바꿨으면 그 시각. 화면의 "2026.07.12 수신 거부" 날짜입니다.
     * 기록이 없는 오래된 계정이면 null 이고, {@code @JsonInclude(ALWAYS)} 라 키는 항상 실립니다.
     *
     * <p>{@code battery_optimization_ignored} 는 저장만 하고 응답에는 넣지 않습니다(명세 형태 유지).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingsResponse(
            boolean autoAnalysisEnabled,
            boolean analysisNotificationEnabled,
            boolean dangerNotificationEnabled,
            boolean needsCheckNotificationEnabled,
            boolean normalNotificationEnabled,
            boolean familyNewsEnabled,
            boolean dangerShareEnabled,
            boolean marketingAgreed,
            Instant marketingChangedAt,
            Permissions permissions
    ) {

        public static SettingsResponse of(UserSettings settings, Marketing marketing, Device device) {
            return new SettingsResponse(
                    settings.isAutoAnalysisEnabled(),
                    settings.isAnalysisNotificationEnabled(),
                    settings.isDangerNotificationEnabled(),
                    settings.isNeedsCheckNotificationEnabled(),
                    settings.isNormalNotificationEnabled(),
                    settings.isFamilyNewsEnabled(),
                    settings.isDangerShareEnabled(),
                    marketing.agreed(),
                    marketing.changedAt(),
                    new Permissions(
                            device.isNotificationPermission(),
                            device.isMicrophonePermission(),
                            device.isFilePermission()));
        }
    }

    /** 마케팅 수신 현재 상태. 기록이 없으면 {@link #none()} — 거부로 봅니다(옵트인) */
    public record Marketing(boolean agreed, Instant changedAt) {

        public static Marketing none() {
            return new Marketing(false, null);
        }
    }

    public record Permissions(
            boolean notificationPermission,
            boolean microphonePermission,
            boolean filePermission
    ) {
    }

    /**
     * PUT /settings — <b>보낸 항목만 바뀝니다</b>(null = 그대로).
     *
     * <p>화면에서 토글 하나를 누를 때마다 그 항목 하나만 보내는 흐름을 전제로 합니다.
     * 전체를 보내게 하면 두 기기에서 동시에 다른 토글을 눌렀을 때 한쪽 변경이 덮입니다.
     *
     * <p><b>마스터와 하위 3개는 같이 보낼 수 없습니다.</b> "마스터 OFF + 안전 ON" 처럼 서로 어긋난
     * 요청을 어느 쪽 우선으로 처리할지 정하는 대신 422 로 거절합니다 — 화면에서는 한 번에 하나만 누릅니다.
     *
     * <p>마케팅 수신은 여기 없습니다 — 동의 이력이라 {@code PUT /settings/marketing} 으로 따로 받습니다.
     */
    public record UpdateRequest(
            Boolean autoAnalysisEnabled,
            Boolean analysisNotificationEnabled,
            Boolean dangerNotificationEnabled,
            Boolean needsCheckNotificationEnabled,
            Boolean normalNotificationEnabled,
            Boolean familyNewsEnabled,
            Boolean dangerShareEnabled
    ) {

        public boolean isEmpty() {
            return Stream.of(autoAnalysisEnabled, analysisNotificationEnabled,
                            dangerNotificationEnabled, needsCheckNotificationEnabled,
                            normalNotificationEnabled, familyNewsEnabled, dangerShareEnabled)
                    .allMatch(value -> value == null);
        }

        public boolean mixesMasterWithSub() {
            return analysisNotificationEnabled != null
                    && (dangerNotificationEnabled != null
                    || needsCheckNotificationEnabled != null
                    || normalNotificationEnabled != null);
        }
    }

    /** PUT /settings/marketing — 마케팅 정보 수신 동의·철회 */
    public record MarketingRequest(
            @NotNull(message = "마케팅 수신 여부가 필요해요")
            Boolean marketingAgreed
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