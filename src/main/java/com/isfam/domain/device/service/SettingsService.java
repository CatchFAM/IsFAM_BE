package com.isfam.domain.device.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.device.dto.SettingsDto;
import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.domain.device.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * {@code /settings/**} — 계정 설정(알림·자동분석) + 마케팅 수신 + 기기 OS 권한.
 *
 * <p>예전에는 항목이 전부 {@code devices} 컬럼이라 {@code DeviceService} 가 맡았습니다.
 * 알림·자동분석을 계정 단위({@code user_settings})로 옮기면서 분리했습니다.
 * 권한 동기화가 여기 있는 건 응답이 계정 설정과 권한을 함께 내려주기 때문입니다.
 *
 * <p><b>X-Device-Id 는 여전히 필수입니다.</b> 계정 설정만 바꾸는 요청이라도 응답의 권한이
 * 그 기기 값이고, 남의 단말 id 로 들어오면 DEVICE_002 로 막아야 합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final DeviceRepository deviceRepository;
    private final Clock clock;

    /**
     * {@code GET /settings}.
     *
     * <p>행이 없으면 <b>만들지 않고</b> 기본값으로 응답합니다. 가입·마이그레이션이 행을 채우므로
     * 정상이면 없을 일이 없고, 조회가 쓰기를 일으키지 않게 둡니다.
     */
    @Transactional(readOnly = true)
    public SettingsDto.SettingsResponse get(Long userId, Long deviceId) {
        Device device = findMyDevice(userId, deviceId);
        UserSettings settings = userSettingsRepository.findById(userId)
                .orElseGet(() -> UserSettings.defaults(userId));
        return SettingsDto.SettingsResponse.of(settings, currentMarketing(userId), device);
    }

    /**
     * {@code PUT /settings} — 보낸 항목만 바꿉니다.
     *
     * <p><b>검사 순서</b> — 단말 소유(DEVICE_002) → 요청 형식(COMMON_002). 남의 단말 id 로
     * 들어온 요청은 형식이 맞든 틀리든 같은 401 을 받아야 합니다.
     *
     * <p>마스터({@code analysis_notification_enabled})는 하위 3개를 한꺼번에 바꿉니다.
     * 켤 때 이전 값을 복원하지 않고 전부 켭니다 — 안전 결과도 켜집니다.
     */
    @Transactional
    public SettingsDto.SettingsResponse update(Long userId, Long deviceId,
                                               SettingsDto.UpdateRequest request) {
        Device device = findMyDevice(userId, deviceId);

        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_002, "변경할 설정이 없어요");
        }
        if (request.mixesMasterWithSub()) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "통화분석 알림 전체와 세부 항목은 함께 바꿀 수 없어요");
        }

        UserSettings settings = findOrCreate(userId);

        if (request.autoAnalysisEnabled() != null) {
            settings.changeAutoAnalysis(request.autoAnalysisEnabled());
        }
        if (request.analysisNotificationEnabled() != null) {
            settings.changeAnalysisNotification(request.analysisNotificationEnabled());
        }
        if (request.dangerNotificationEnabled() != null) {
            settings.changeDangerNotification(request.dangerNotificationEnabled());
        }
        if (request.needsCheckNotificationEnabled() != null) {
            settings.changeNeedsCheckNotification(request.needsCheckNotificationEnabled());
        }
        if (request.normalNotificationEnabled() != null) {
            settings.changeNormalNotification(request.normalNotificationEnabled());
        }
        if (request.familyNewsEnabled() != null) {
            settings.changeFamilyNews(request.familyNewsEnabled());
        }
        if (request.dangerShareEnabled() != null) {
            settings.changeDangerShare(request.dangerShareEnabled());
        }

        log.debug("설정 변경 — userId={}", userId);
        return SettingsDto.SettingsResponse.of(settings, currentMarketing(userId), device);
    }

    /**
     * {@code PUT /settings/marketing} — 마케팅 정보 수신 동의·철회 (L14-b, P-19 일부).
     *
     * <p><b>설정 컬럼이 아니라 동의 이력입니다.</b> 정보통신망법 제50조는 광고성 정보 수신에
     * 사전 동의를 요구하고 철회를 즉시 반영하라고 합니다. 언제 동의했고 언제 철회했는지를
     * 증명할 수 있어야 해서 {@code user_agreements} 에 <b>행을 추가</b>합니다 — 고치지 않습니다.
     * 가입 때 남긴 MARKETING 행이 첫 기록이고, 이후 변경은 그 뒤로 쌓입니다.
     *
     * <p><b>같은 값이면 행을 추가하지 않습니다(멱등).</b> 앱이 재시도하거나 두 번 눌러도
     * 이력이 같은 결정으로 도배되지 않고, 화면의 처리일도 처음 정한 날로 유지됩니다.
     *
     * <p>응답의 {@code marketing_changed_at} 이 처리 결과의 일자입니다 — 앱은 이 값으로
     * "2026.10.31 수신 거부 처리됐어요" 같은 처리 결과 안내를 띄울 수 있습니다.
     *
     * <p>야간(21~08시) 수신 동의 · 2년 재확인은 아직 없습니다 — 실제 마케팅 발송을 붙일 때(P-19).
     */
    @Transactional
    public SettingsDto.SettingsResponse updateMarketing(Long userId, Long deviceId, boolean agreed) {
        Device device = findMyDevice(userId, deviceId);

        SettingsDto.Marketing current = currentMarketing(userId);
        boolean hasRecord = current.changedAt() != null;

        if (!hasRecord || current.agreed() != agreed) {
            UserAgreement saved = userAgreementRepository.save(UserAgreement.of(
                    userId, UserAgreement.Type.MARKETING, agreed,
                    UserAgreement.CURRENT_VERSION, clock.instant()));
            current = new SettingsDto.Marketing(saved.isAgreed(), saved.getAgreedAt());
            log.info("마케팅 수신 변경 — userId={}, agreed={}", userId, agreed);
        }

        UserSettings settings = userSettingsRepository.findById(userId)
                .orElseGet(() -> UserSettings.defaults(userId));
        return SettingsDto.SettingsResponse.of(settings, current, device);
    }

    /**
     * {@code PUT /settings/permissions} — 앱 실행 시마다 OS 권한을 동기화합니다.
     * 권한이 회수됐으면 앱이 홈 화면에 경고 배너를 띄웁니다.
     *
     * <p>권한은 기기 단위라 {@code devices} 에 씁니다. 계정 설정은 읽기만 합니다.
     */
    @Transactional
    public SettingsDto.SettingsResponse syncPermissions(Long userId, Long deviceId,
                                                        SettingsDto.PermissionsRequest request) {
        Device device = findMyDevice(userId, deviceId);
        device.updatePermissions(request.notificationPermission(), request.microphonePermission(),
                request.filePermission(), request.batteryOptimizationIgnored());

        UserSettings settings = userSettingsRepository.findById(userId)
                .orElseGet(() -> UserSettings.defaults(userId));
        return SettingsDto.SettingsResponse.of(settings, currentMarketing(userId), device);
    }

    // ── 내부 ────────────────────────────────────────────────

    /** 마케팅 수신 현재 상태 = MARKETING 의 최신 행. 없으면 거부(옵트인) */
    private SettingsDto.Marketing currentMarketing(Long userId) {
        Optional<UserAgreement> latest = userAgreementRepository
                .findFirstByUserIdAndAgreementTypeOrderByAgreedAtDescIdDesc(
                        userId, UserAgreement.Type.MARKETING);
        return latest
                .map(row -> new SettingsDto.Marketing(row.isAgreed(), row.getAgreedAt()))
                .orElseGet(SettingsDto.Marketing::none);
    }

    /**
     * 행이 없으면 기본값으로 만든 뒤 읽습니다. 동시 요청이 겹쳐도 안전합니다 —
     * {@link UserSettingsRepository#insertDefaultsIfAbsent} 참고.
     */
    private UserSettings findOrCreate(Long userId) {
        return userSettingsRepository.findById(userId).orElseGet(() -> {
            userSettingsRepository.insertDefaultsIfAbsent(userId, clock.instant());
            return userSettingsRepository.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("user_settings 생성 직후 조회 실패"));
        });
    }

    /** {@code DeviceService} 와 같은 규칙 — 없는 id 든 남의 단말이든 DEVICE_002 */
    private Device findMyDevice(Long userId, Long deviceId) {
        return deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_002,
                        "단말 정보를 확인할 수 없어요. 앱을 다시 실행해 주세요"));
    }
}