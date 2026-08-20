package com.isfam.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.analysis.entity.VoiceAnalysis;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * {@code /voice-analyses} 의 요청·응답.
 *
 * <p><b>추가하면 안 되는 필드가 이 클래스의 정의에 가깝습니다.</b>
 * {@code similarity_score}, {@code matched_user_id}, {@code matched_member},
 * {@code matched_family_id}, 상대방 전화번호 — 전부 발신자 식별 결과라 상대방 동의를
 * 받을 수 없고, 법 제23조의 예외에도 걸리지 않습니다(legal-compliance.md 3장·5장).
 *
 * <p>그래서 앱의 히스토리 화면은 <b>로컬 DB 기준으로 그려야 합니다.</b>
 * 이 응답은 기기 변경·재설치 시 판정을 복구하기 위한 것이고,
 * "엄마 목소리로 확인됨" 같은 표시는 폰에만 존재합니다.
 */
public final class VoiceAnalysisDto {

    private VoiceAnalysisDto() {
    }

    // ── 제출 ────────────────────────────────────────────────

    /**
     * {@code spoof_score} 가 입력에 없는 게 정상입니다 — 앱이 되돌려주지 않고
     * 서버가 {@code spoof_check_id} 로 조회합니다(명세 [정책]). 같은 값을 두 군데 두면
     * 언젠가 갈라지고, 그때 어느 쪽이 진짜인지 판단할 근거가 없습니다.
     *
     * <p>{@code final_decision} 을 enum 으로 직접 바인딩하지 않고 문자열로 받는 이유는
     * 잘못된 값이 왔을 때 허용되는 값을 안내하기 위해서입니다.
     */
    public record SubmitRequest(
            @NotNull(message = "call_event_id 가 필요해요")
            Long callEventId,

            @NotNull(message = "spoof_check_id 가 필요해요")
            Long spoofCheckId,

            @NotNull(message = "risk_level 이 필요해요")
            @DecimalMin(value = "0.0", message = "risk_level 은 0 이상이어야 해요")
            @DecimalMax(value = "100.0", message = "risk_level 은 100 이하여야 해요")
            Double riskLevel,

            @NotBlank(message = "final_decision 이 필요해요")
            String finalDecision,

            /* 화자 모델(ECAPA) 버전만. 딥보이스 모델은 서버에서 돌아가므로 앱이 관리하지 않습니다 */
            @NotBlank(message = "on_device_model_version 이 필요해요")
            String onDeviceModelVersion,

            @NotNull(message = "analyzed_at 이 필요해요")
            Instant analyzedAt
    ) {
    }

    // ── 응답 ────────────────────────────────────────────────

    /**
     * 제출 · 단건 조회 · 목록이 <b>같은 형태</b>입니다(명세 확인). 하나로 씁니다 —
     * 셋을 따로 두면 나중에 한쪽에만 필드가 붙어 앱이 화면마다 다르게 파싱하게 됩니다.
     *
     * <p>{@code spoof_score} 는 {@code spoof_checks} 조인 결과입니다.
     * 판정 row 가 사라질 일은 없지만(접속기록이라 삭제하지 않습니다) 방어적으로 null 을
     * 허용하고, {@code @JsonInclude(ALWAYS)} 로 키 자체는 유지합니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(
            Long analysisId,
            Double spoofScore,
            Double riskLevel,
            VoiceAnalysis.FinalDecision finalDecision,
            Instant submittedAt
    ) {

        public static Item of(VoiceAnalysis analysis, Double spoofScore) {
            return new Item(
                    analysis.getId(),
                    spoofScore,
                    analysis.getRiskLevel(),
                    analysis.getFinalDecision(),
                    analysis.getSubmittedAt());
        }
    }

    // ── 목록 필터 ───────────────────────────────────────────

    /**
     * {@code GET /voice-analyses} 의 필터 4종. 전부 선택입니다.
     *
     * <p>{@code dateFrom}/{@code dateTo} 는 {@code submitted_at} 기준입니다.
     * {@code analyzed_at} 이 아닌 이유는 오프라인에서 밀린 분석이 한꺼번에 올라올 때
     * 페이지 경계가 요동치기 때문입니다 — 정렬 기준과 필터 기준은 같아야 합니다.
     */
    public record SearchCondition(
            Instant dateFrom,
            Instant dateTo,
            VoiceAnalysis.FinalDecision finalDecision,
            Double minRiskLevel
    ) {
    }
}