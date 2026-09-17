package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.common.web.PageQuery;
import com.isfam.common.web.PageResponse;
import com.isfam.domain.analysis.dto.VoiceAnalysisDto;
import com.isfam.domain.analysis.entity.CallEvent;
import com.isfam.domain.analysis.entity.SpoofCheck;
import com.isfam.domain.analysis.entity.VoiceAnalysis;
import com.isfam.domain.analysis.repository.CallEventRepository;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import com.isfam.domain.analysis.repository.VoiceAnalysisRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.repository.NotificationRepository;
import com.isfam.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 통화 분석 결과 제출 · 조회 · 삭제 (Phase 8).
 *
 * <p><b>서버는 위험도를 계산하지 않습니다.</b> 앱이 온디바이스 화자 유사도와
 * 서버 {@code spoof_score} 를 융합해 낸 결론만 받습니다. 서버가 재계산하려면
 * 유사도 값이 필요한데, 그걸 받는 순간 발신자 식별 결과를 보유하게 되어
 * 상대방 동의 없는 민감정보 처리가 됩니다(legal-compliance.md 3장).
 *
 * <p>그래서 이 서비스가 하는 일은 <b>검증 · 저장 · 상태 동기화 · 알림</b> 네 가지뿐입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAnalysisService {

    private final VoiceAnalysisRepository voiceAnalysisRepository;
    private final CallEventRepository callEventRepository;
    private final SpoofCheckRepository spoofCheckRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final ModelVersionPolicy modelVersionPolicy;
    private final Clock clock;

    // ── 제출 ────────────────────────────────────────────────

    /**
     * {@code POST /voice-analyses}.
     *
     * <p><b>검사 순서</b> — 모델 버전(MODEL_001) → 소유권(404) → 짝 검증 → 중복.
     * 버전을 먼저 보는 이유는 {@code VoiceprintService} 와 같습니다. 재시도로 해결되지 않는
     * 유일한 조건이라, 앱이 곧장 "업데이트해 주세요"로 분기해야 합니다.
     *
     * <p><b>저장 · 상태 전이 · 알림이 한 트랜잭션입니다.</b> 명세 [정책] 그대로이고,
     * 갈라두면 "분석 결과는 있는데 call_events 는 detected" 같은 조합이 생깁니다.
     * 푸시만 커밋 이후에 나갑니다({@code PushDispatcher} 의 AFTER_COMMIT).
     *
     * <p><b>재제출은 기존 결과를 그대로 돌려줍니다.</b> 앱이 응답을 못 받고 재시도하는 건
     * 흔한 일인데, 그때마다 새 row 를 만들면 {@code danger_call} 알림도 같이 늘어납니다.
     * 위험 알림이 두 번 오는 건 사용자 입장에서 "통화가 두 번 위험했다"로 읽힙니다.
     */
    @Transactional
    public VoiceAnalysisDto.Item submit(Long userId, VoiceAnalysisDto.SubmitRequest request) {
        modelVersionPolicy.requireSupported(request.onDeviceModelVersion());

        CallEvent callEvent = callEventRepository
                .findByIdAndUserId(request.callEventId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 통화 기록을 찾을 수 없어요"));

        SpoofCheck spoofCheck = spoofCheckRepository
                .findByIdAndUserId(request.spoofCheckId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 분석 요청을 찾을 수 없어요"));

        // 판정을 거부한 요청에는 점수가 없습니다. 이걸로 분석 결과를 만들면
        // spoof_score 가 null 인 분석이 생기고, 앱 화면에서 근거 없는 판정이 됩니다.
        if (spoofCheck.isRejected()) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "판정이 거부된 요청으로는 분석을 제출할 수 없어요");
        }

        // 다른 통화의 판정을 이 통화에 붙이면 이력이 통째로 어긋납니다.
        // 앱 버그일 가능성이 높아 조용히 받아주지 않습니다.
        if (!spoofCheck.getCallEventId().equals(callEvent.getId())) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "spoof_check_id 가 이 통화의 판정 결과가 아니에요");
        }

        // ── 재제출 처리 ─────────────────────────────────────
        var existing = voiceAnalysisRepository.findByCallEventId(callEvent.getId());
        if (existing.isPresent()) {
            VoiceAnalysis found = existing.get();
            if (!found.getSpoofCheckId().equals(spoofCheck.getId())) {
                throw new BusinessException(ErrorCode.COMMON_002,
                        "이 통화는 이미 다른 판정 결과로 분석이 제출됐어요");
            }
            log.debug("통화 분석 재제출 — 기존 결과 반환 (analysisId={})", found.getId());
            return VoiceAnalysisDto.Item.of(found, spoofCheck.getSpoofScore());
        }

        if (voiceAnalysisRepository.existsBySpoofCheckId(spoofCheck.getId())) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "이미 다른 통화에 사용된 판정 결과예요");
        }

        VoiceAnalysis.FinalDecision decision = parseDecision(request.finalDecision());
        Instant now = clock.instant();

        // saveAndFlush 인 이유는 analysis_id 가 필요해서입니다 —
        // call_events.voice_analysis_id 와 danger_call 알림의 target_id 가 이 값입니다.
        VoiceAnalysis saved = voiceAnalysisRepository.saveAndFlush(VoiceAnalysis.submit(
                userId,
                callEvent.getId(),
                spoofCheck.getId(),
                request.riskLevel(),
                decision,
                request.onDeviceModelVersion(),
                request.analyzedAt(),
                now));

        callEvent.linkAnalysis(saved.getId());

        if (decision.requiresNotification()) {
            // 본문에 가족 이름이 들어갈 여지가 없도록 팩토리가 문구를 고정하고 있습니다.
            // 구체적인 안내("엄마 목소리로 확인됐어요")는 폰 로컬 알림 담당입니다.
            notificationService.create(Notification.dangerCall(userId, saved.getId()));
            log.debug("위험 통화 알림 생성 — analysisId={}, decision={}", saved.getId(), decision);
        }

        return VoiceAnalysisDto.Item.of(saved, spoofCheck.getSpoofScore());
    }

    // ── 조회 ────────────────────────────────────────────────

    /**
     * {@code GET /voice-analyses/&#123;analysis_id&#125;}.
     *
     * <p>{@code matched_member}·{@code similarity_score} 는 응답에 없습니다 —
     * 서버가 보유하지 않기 때문입니다. 앱은 이 판정에 로컬 DB 의 매칭 정보를 합쳐
     * 화면을 그립니다.
     */
    @Transactional(readOnly = true)
    public VoiceAnalysisDto.Item get(Long userId, Long analysisId) {
        VoiceAnalysis analysis = voiceAnalysisRepository.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 분석 기록을 찾을 수 없어요"));

        Double spoofScore = spoofCheckRepository.findById(analysis.getSpoofCheckId())
                .map(SpoofCheck::getSpoofScore)
                .orElse(null);

        return VoiceAnalysisDto.Item.of(analysis, spoofScore);
    }

    /**
     * {@code GET /voice-analyses} — {@code submitted_at} 내림차순.
     *
     * <p>{@code spoof_score} 는 분석마다 한 건씩 조회하면 page_size 만큼 쿼리가 나갑니다.
     * 페이지의 {@code spoof_check_id} 를 모아 한 번에 가져와 메모리에서 붙입니다.
     */
    @Transactional(readOnly = true)
    public PageResponse<VoiceAnalysisDto.Item> search(Long userId,
                                                      VoiceAnalysisDto.SearchCondition condition,
                                                      PageQuery pageQuery) {

        Page<VoiceAnalysis> page = voiceAnalysisRepository.findAll(
                VoiceAnalysisSpecifications.search(userId, condition),
                pageQuery.toPageable(Sort.by(Sort.Direction.DESC, "submittedAt")));

        Set<Long> spoofCheckIds = page.getContent().stream()
                .map(VoiceAnalysis::getSpoofCheckId)
                .collect(Collectors.toSet());

        Map<Long, Double> scores = loadScores(spoofCheckIds);

        return PageResponse.of(page, pageQuery,
                analysis -> VoiceAnalysisDto.Item.of(
                        analysis, scores.get(analysis.getSpoofCheckId())));
    }

    // ── 삭제 ────────────────────────────────────────────────

    /**
     * {@code DELETE /voice-analyses/&#123;analysis_id&#125;} — 204.
     *
     * <p>세 가지가 함께 정리됩니다.
     * <ol>
     *   <li>{@code call_events.voice_analysis_id} 를 null 로 — 참조가 남으면 나중에
     *       존재하지 않는 분석을 가리킵니다. 상태는 {@code analyzed_on_device} 로 두는데,
     *       분석이 실제로 일어났다는 사실까지 거짓이 되는 건 아니기 때문입니다</li>
     *   <li>이 분석을 가리키던 {@code danger_call} 알림 삭제 — 남겨두면 사용자가 탭했을 때
     *       404 를 만납니다. 알림함에 열리지 않는 항목이 남는 것보다 사라지는 편이 낫습니다</li>
     *   <li>{@code voice_analyses} row 삭제</li>
     * </ol>
     *
     * <p><b>{@code spoof_checks} 는 지우지 않습니다.</b> 그건 사용자의 분석 기록이 아니라
     * 사업자의 접속기록이고, 보관 의무 대상입니다(legal-compliance.md 4장 ②).
     * Phase 9 회원 탈퇴의 삭제 목록에도 이 테이블이 없는 게 같은 이유입니다.
     */
    @Transactional
    public void delete(Long userId, Long analysisId) {
        VoiceAnalysis analysis = voiceAnalysisRepository.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 분석 기록을 찾을 수 없어요"));

        callEventRepository.findByIdAndUserId(analysis.getCallEventId(), userId)
                .ifPresent(CallEvent::unlinkAnalysis);

        int removedNotifications = notificationRepository.deleteByTarget(
                userId, Notification.TargetType.VOICE_ANALYSIS, analysisId);

        voiceAnalysisRepository.delete(analysis);

        log.debug("분석 기록 삭제 — analysisId={}, 함께 정리한 알림 {}건 (spoof_checks 는 보존)",
                analysisId, removedNotifications);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * {@code Collectors.toMap} 을 쓰지 않는 이유는 거부 row 의 {@code spoofScore} 가
     * null 이기 때문입니다. null 값을 만나면 NPE 로 목록 조회가 통째로 실패합니다.
     *
     * <p>거부된 판정은 분석 제출 단계에서 막히므로 여기까지 올 일은 없지만,
     * 과거 데이터나 직접 INSERT 로 들어오면 그때 터집니다.
     */
    private Map<Long, Double> loadScores(Set<Long> spoofCheckIds) {
        if (spoofCheckIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Double> scores = new HashMap<>();
        spoofCheckRepository.findByIdIn(spoofCheckIds)
                .forEach(check -> scores.put(check.getId(), check.getSpoofScore()));
        return scores;
    }

    private VoiceAnalysis.FinalDecision parseDecision(String raw) {
        try {
            return VoiceAnalysis.FinalDecision.from(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "final_decision 은 normal, needs_check, danger 중 하나여야 해요");
        }
    }
}