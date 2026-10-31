package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.device.entity.UserSettings;
import com.isfam.domain.device.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * "통화 종료 후 자동 분석" 설정을 서버에서 강제합니다 (알림 설정 세분화 5단계).
 *
 * <p><b>알림이 아니라 분석 자체를 끄는 설정입니다.</b> 앱은 꺼져 있으면 분석 API 를
 * 부르지 않아야 하지만, 서버가 그걸 믿기만 하면 "사용자가 끈 처리가 실제로 멈췄다"를
 * 보장할 수 없습니다. 그래서 분석 경로 세 곳이 모두 이 검사를 거칩니다.
 * <ul>
 *   <li>{@code POST /call-events} — 통화가 있었다는 기록부터 받지 않음</li>
 *   <li>{@code POST /call-events/&#123;id&#125;/spoof-check} — 음성이 들어오는 지점.
 *       이미 올라온 음성은 파기하고 거부 기록을 남김</li>
 *   <li>{@code POST /voice-analyses} — 결과 제출</li>
 * </ul>
 * {@code PUT /call-events/&#123;id&#125;}(skipped·failed 처리)는 막지 않습니다 — 분석을
 * 그만두는 쪽의 요청이고, 막으면 이벤트가 detected 로 남습니다.
 *
 * <p>설정 행이 없으면 기본값(ON)입니다. 가입·마이그레이션이 채우므로 정상이면 없을 일이 없습니다.
 */
@Component
@RequiredArgsConstructor
public class AutoAnalysisGuard {

    private final UserSettingsRepository userSettingsRepository;

    public boolean isEnabled(Long userId) {
        return userSettingsRepository.findById(userId)
                .orElseGet(() -> UserSettings.defaults(userId))
                .isAutoAnalysisEnabled();
    }

    /** 꺼져 있으면 ANALYSIS_001(409) */
    public void requireEnabled(Long userId) {
        if (!isEnabled(userId)) {
            throw new BusinessException(ErrorCode.ANALYSIS_001);
        }
    }
}