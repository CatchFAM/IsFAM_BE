package com.isfam.common.model;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 온디바이스 화자 모델(ECAPA) 버전 판정 — MODEL_001.
 *
 * <p>두 곳에서 씁니다.
 * <ul>
 *   <li>{@code POST /me/voiceprint} 의 {@code embedding_model_version} (Phase 6)</li>
 *   <li>{@code POST /voice-analyses} 의 {@code on_device_model_version} (Phase 8)</li>
 * </ul>
 * 같은 판정을 두 서비스에 복붙하면 한쪽만 기준이 바뀌는 사고가 납니다.
 *
 * <p><b>딥보이스 모델(wav2vec2)은 여기서 다루지 않습니다.</b> 서버에서 실행되므로
 * 앱이 다운로드하지 않고, 버전 협상 대상도 아닙니다(legal-compliance.md 6장).
 *
 * <p>버전 문자열은 {@code <모델이름>-v<숫자>} 형식을 가정합니다 — {@code ecapa-tdnn-v1}.
 * <b>모델 이름이 다르면 숫자와 무관하게 거부</b>합니다. {@code resnet-v9} 가
 * {@code ecapa-tdnn-v1} 보다 "높다"고 통과하면, 서로 대조 불가능한 임베딩이
 * 같은 테이블에 섞입니다. 형식이 안 맞으면 문자열 완전 일치만 통과시킵니다.
 */
@Component
public class ModelVersionPolicy {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(.+)-v(\\d+)$");

    /** 앱이 자기 버전과 비교하는 기준값 */
    @Getter
    private final String latestVersion;

    /** 이 값보다 낮으면 MODEL_001 */
    @Getter
    private final String minSupportedVersion;

    public ModelVersionPolicy(
            @Value("${isfam.model.latest-version}") String latestVersion,
            @Value("${isfam.model.min-supported-version}") String minSupportedVersion) {
        this.latestVersion = latestVersion;
        this.minSupportedVersion = minSupportedVersion;
    }

    /**
     * 지원 범위 밖이면 MODEL_001(422). 앱은 이 코드를 받으면 업데이트를 유도합니다.
     */
    public void requireSupported(String version) {
        if (!isSupported(version)) {
            throw new BusinessException(ErrorCode.MODEL_001,
                    "앱의 음성 모델 버전(%s)이 낮아요. 최신 버전으로 업데이트해 주세요"
                            .formatted(StringUtils.hasText(version) ? version : "미상"));
        }
    }

    public boolean isSupported(String version) {
        if (!StringUtils.hasText(version)) {
            return false;
        }
        String candidate = version.trim();
        if (candidate.equalsIgnoreCase(minSupportedVersion)) {
            return true;
        }

        Matcher target = VERSION_PATTERN.matcher(candidate);
        Matcher minimum = VERSION_PATTERN.matcher(minSupportedVersion);
        if (!target.matches() || !minimum.matches()) {
            // 형식이 다르면 비교 자체가 성립하지 않습니다. 완전 일치는 위에서 이미 통과했습니다.
            return false;
        }

        if (!target.group(1).equalsIgnoreCase(minimum.group(1))) {
            return false; // 다른 계열의 모델
        }

        try {
            return Integer.parseInt(target.group(2)) >= Integer.parseInt(minimum.group(2));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 같은 계열인지만 봅니다.
     *
     * <p>사용처는 {@code POST /me/voiceprint} 재등록입니다. 이미 등록된 샘플과 다른 계열의
     * 임베딩이 들어오면 평균을 낼 수 없어 기존 샘플을 버려야 하는데, 그 판단에 씁니다.
     */
    public boolean sameFamily(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        Matcher leftMatcher = VERSION_PATTERN.matcher(left.trim());
        Matcher rightMatcher = VERSION_PATTERN.matcher(right.trim());
        return leftMatcher.matches() && rightMatcher.matches()
                && leftMatcher.group(1).equalsIgnoreCase(rightMatcher.group(1));
    }
}