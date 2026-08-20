package com.isfam.domain.analysis.controller;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.security.CurrentUser;
import com.isfam.common.web.PageQuery;
import com.isfam.common.web.PageResponse;
import com.isfam.domain.analysis.dto.VoiceAnalysisDto;
import com.isfam.domain.analysis.entity.VoiceAnalysis;
import com.isfam.domain.analysis.service.VoiceAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 통화 분석 결과 제출 · 히스토리.
 *
 * <p><b>히스토리 화면은 이 응답만으로 그려지지 않습니다.</b> 발신자 매칭 정보는
 * 서버에 없고 폰 로컬 DB 에만 있습니다. 이 API 는 기기 변경·재설치 시 판정을
 * 복구하기 위한 것입니다(legal-compliance.md 6장 파생 영향).
 *
 * <p>쿼리 파라미터를 {@code @RequestParam(name = ...)} 으로 하나씩 받는 이유는
 * {@code NotificationController} 와 같습니다 — Jackson 의 SNAKE_CASE 설정은
 * 쿼리 파라미터 바인딩에 적용되지 않아서, {@code @ModelAttribute} 로 받으면
 * {@code min_risk_level} 이 조용히 무시됩니다.
 */
@RestController
@RequestMapping("/voice-analyses")
@RequiredArgsConstructor
public class VoiceAnalysisController {

    private final VoiceAnalysisService voiceAnalysisService;

    /**
     * 온디바이스 분석 결과 제출.
     *
     * <p>성공하면 서버가 같은 트랜잭션에서 {@code call_events} 상태를
     * {@code analyzed_on_device} 로 바꾸고 {@code voice_analysis_id} 를 채웁니다.
     * 앱이 {@code PUT /call-events} 를 따로 부를 필요가 없습니다.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public VoiceAnalysisDto.Item submit(
            @CurrentUser Long userId,
            @Valid @RequestBody VoiceAnalysisDto.SubmitRequest request) {
        return voiceAnalysisService.submit(userId, request);
    }

    @GetMapping("/{analysisId}")
    public VoiceAnalysisDto.Item get(@CurrentUser Long userId,
                                     @PathVariable Long analysisId) {
        return voiceAnalysisService.get(userId, analysisId);
    }

    /**
     * 필터 4종은 전부 선택입니다. {@code submitted_at} 내림차순.
     *
     * <p>{@code date_from}/{@code date_to} 는 ISO-8601 시각
     * ({@code 2026-08-20T00:00:00Z})과 날짜({@code 2026-08-20}) 둘 다 받습니다.
     * 날짜만 오면 <b>UTC 기준</b> 그날 00:00:00 ~ 23:59:59.999999999 로 해석합니다 —
     * 서버 시각이 전부 UTC 라 여기서 KST 를 끼워 넣으면 하루 경계가 9시간 어긋납니다.
     */
    @GetMapping
    public PageResponse<VoiceAnalysisDto.Item> search(
            @CurrentUser Long userId,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo,
            @RequestParam(name = "final_decision", required = false) String finalDecision,
            @RequestParam(name = "min_risk_level", required = false) Double minRiskLevel,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {

        VoiceAnalysisDto.SearchCondition condition = new VoiceAnalysisDto.SearchCondition(
                parseInstant(dateFrom, "date_from", false),
                parseInstant(dateTo, "date_to", true),
                parseDecision(finalDecision),
                minRiskLevel);

        return voiceAnalysisService.search(userId, condition, PageQuery.of(page, pageSize));
    }

    /** 성공하면 204. 응답 본문이 없습니다 */
    @DeleteMapping("/{analysisId}")
    public ResponseEntity<Void> delete(@CurrentUser Long userId,
                                       @PathVariable Long analysisId) {
        voiceAnalysisService.delete(userId, analysisId);
        return ResponseEntity.noContent().build();
    }

    // ── 파라미터 파싱 ───────────────────────────────────────

    /**
     * @param endOfDay 날짜만 온 경우 그날의 끝으로 해석할지. {@code date_to} 에 필요합니다 —
     *                 {@code 2026-08-20} 을 00:00 으로 잡으면 그날 기록이 통째로 빠집니다
     */
    private Instant parseInstant(String raw, String field, boolean endOfDay) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 날짜만 온 경우로 한 번 더 시도합니다
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                    ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1)
                    : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COMMON_001,
                    "%s 형식이 올바르지 않아요. 2026-08-20 또는 2026-08-20T00:00:00Z 형태로 보내주세요"
                            .formatted(field));
        }
    }

    private VoiceAnalysis.FinalDecision parseDecision(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return VoiceAnalysis.FinalDecision.from(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_001,
                    "final_decision 은 normal, needs_check, danger 중 하나여야 해요");
        }
    }
}