package com.isfam.domain.notification.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.entity.VoiceAnalysis;
import com.isfam.domain.analysis.repository.VoiceAnalysisRepository;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.notification.dto.DangerShareDto;
import com.isfam.domain.notification.entity.DangerShare;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.repository.DangerShareRepository;
import com.isfam.infra.push.event.DangerShareEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 위험 통화 공유 — {@code POST /notifications/danger-share} · {@code GET /notifications/danger-share/{id}}.
 *
 * <p><b>의심 번호({@code phoneNumber})를 로그·예외 메시지·이벤트에 절대 넣지 마세요.</b>
 * 통화 상대방은 회원이 아니라 동의를 받은 적이 없는 번호입니다. 서버가 이 번호를 받는 근거는
 * 제15조 제1항 제6호(사기 피해 방지)이고, 그 근거가 성립하려면 처리를 최소로 둬야 합니다.
 * DB({@code danger_shares}) 한 곳에만, 7일만 둡니다.
 *
 * <p><b>공유 검사 순서</b> — 형식(400·422) → 권한(403·404) → 중복(409) → 공유 가능 여부(422).
 * 권한을 공유 가능 여부보다 먼저 보는 이유는, 남의 분석 ID 로 요청했을 때 "정상 판정이라
 * 공유할 수 없어요" 같은 답이 나가면 그 분석의 판정 결과가 새기 때문입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DangerShareService {

    /** 분석 후 이 시간이 지나면 공유할 수 없습니다(422). 기산점은 서버가 찍은 submitted_at */
    static final Duration SHAREABLE_WINDOW = Duration.ofHours(24);

    /** 하이픈·공백을 뺀 뒤의 형식. 국제번호의 + 만 허용합니다 */
    private static final Pattern PHONE_DIGITS = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final DangerShareRepository dangerShareRepository;
    private final VoiceAnalysisRepository voiceAnalysisRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ── 공유 ────────────────────────────────────────────────

    @Transactional
    public DangerShareDto.CreateResponse share(Long senderUserId, DangerShareDto.CreateRequest request) {
        Instant now = clock.instant();

        // ── ① 형식 ──────────────────────────────────────────
        boolean hasImpersonated = request.impersonatedUserId() != null;
        boolean hasPhone = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        if (hasImpersonated == hasPhone) {
            throw new BusinessException(ErrorCode.SHARE_001);
        }
        String phoneNumber = hasPhone ? normalizePhone(request.phoneNumber()) : null;

        // ── ② 권한 ──────────────────────────────────────────
        // 없는 분석과 남의 분석을 구분하지 않습니다 — 둘 다 403.
        VoiceAnalysis analysis = voiceAnalysisRepository.findById(request.analysisId())
                .filter(found -> found.getUserId().equals(senderUserId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_002));

        FamilyMembership senderMembership = membershipRepository
                .findByUserIdAndStatus(senderUserId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
        Long familyId = senderMembership.getFamilyId();

        List<FamilyMembership> members =
                membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE);

        if (hasImpersonated) {
            // 사칭된 가족은 같은 공간의 다른 active 멤버여야 합니다. 이걸 안 보면 아무 user_id 나
            // 넣어 그 사람 폰에 "누군가 회원님인 척했어요" 를 띄울 수 있습니다.
            Long impersonated = request.impersonatedUserId();
            boolean valid = !impersonated.equals(senderUserId)
                    && members.stream().anyMatch(m -> m.getUserId().equals(impersonated));
            if (!valid) {
                throw new BusinessException(ErrorCode.SHARE_002);
            }
        }

        // ── ③ 중복 ──────────────────────────────────────────
        if (dangerShareRepository.existsByAnalysisId(analysis.getId())) {
            throw new BusinessException(ErrorCode.SHARE_003);
        }

        // ── ④ 공유 가능 여부 ────────────────────────────────
        if (!analysis.getFinalDecision().requiresNotification()) {
            throw new BusinessException(ErrorCode.SHARE_004, "정상으로 판정된 통화는 공유할 수 없어요");
        }
        if (!now.isBefore(analysis.getSubmittedAt().plus(SHAREABLE_WINDOW))) {
            throw new BusinessException(ErrorCode.SHARE_004, "분석 후 24시간이 지난 통화는 공유할 수 없어요");
        }

        List<Long> recipientIds = members.stream()
                .map(FamilyMembership::getUserId)
                .filter(id -> !id.equals(senderUserId))
                .toList();
        if (recipientIds.isEmpty()) {
            throw new BusinessException(ErrorCode.SHARE_004, "공유할 가족이 없어요");
        }

        // ── ⑤ 저장 ──────────────────────────────────────────
        DangerShare share = hasImpersonated
                ? DangerShare.impersonated(analysis.getId(), senderUserId, familyId,
                request.impersonatedUserId())
                : DangerShare.unknownNumber(analysis.getId(), senderUserId, familyId, phoneNumber);

        DangerShare saved = saveOrConflict(share);

        // 알림함에는 고정 문구만. 푸시는 아래 전용 이벤트 하나로 나갑니다(createWithoutPush 참고).
        Map<Long, Long> recipients = new LinkedHashMap<>();
        for (Long recipientId : recipientIds) {
            Notification notification = notificationService.createWithoutPush(
                    Notification.dangerShare(recipientId, saved.getId()));
            recipients.put(recipientId, notification.getId());
        }

        // ── ⑥ 커밋 후 푸시 ──────────────────────────────────
        eventPublisher.publishEvent(new DangerShareEvent(
                saved.getId(),
                senderUserId,
                saved.getImpersonatedUserId(),
                saved.hasPhoneNumber(),
                recipients,
                now));

        // 번호·이름은 남기지 않습니다(P-24). 유형과 건수만.
        log.info("위험 통화 공유 — shareId={}, type={}, recipients={}",
                saved.getId(), hasImpersonated ? "impersonated" : "unknown_number", recipients.size());

        return new DangerShareDto.CreateResponse(saved.getId());
    }

    // ── 번호 조회 ───────────────────────────────────────────

    /**
     * {@code GET /notifications/danger-share/{shareId}}.
     *
     * <p>앱이 {@code danger_share} 푸시를 받자마자 <b>한 번</b> 부르고 번호를 폰에 저장합니다.
     * 이후 화면(알림함 탭 등)은 폰에 저장된 값으로 그립니다.
     *
     * <p><b>조회할 수 있는 사람</b> — 공유 시점의 받는 사람과 같습니다.
     * <ul>
     *   <li>그 공간의 <b>현재</b> active 멤버. 공유 뒤 나갔으면 더 받지 못합니다</li>
     *   <li>공유 <b>전에</b> 들어온 사람. 공유는 그 시점의 멤버 전원에게 간 것이라, 뒤에 들어온
     *       사람은 받는 사람이 아닙니다. 나갔다 다시 들어온 사람도 {@code joined_at} 이 재참여
     *       시각으로 바뀌므로 같은 규칙으로 걸러집니다</li>
     * </ul>
     *
     * <p><b>막히는 이유를 나누지 않습니다 — 전부 404.</b> 403 과 404 를 구분하면
     * "그 share_id 의 공유는 존재한다"가 새어 나갑니다.
     *
     * <p>7일이 지났으면 파기 배치가 행을 지우기 전이라도 404 입니다. 배치는 하루 한 번이라
     * 최대 하루 늦게 지워지는데, 그동안 조회까지 열어 두면 보관 기간이 사실상 8일이 됩니다.
     */
    @Transactional(readOnly = true)
    public DangerShareDto.PhoneResponse getPhoneNumber(Long requesterUserId, Long shareId) {
        Instant now = clock.instant();

        DangerShare share = dangerShareRepository.findById(shareId)
                .filter(found -> found.isReadableAt(now))
                .filter(DangerShare::hasPhoneNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARE_005));

        boolean allowed = membershipRepository
                .findByFamilyIdAndUserIdAndStatus(share.getFamilyId(), requesterUserId, ACTIVE)
                .filter(membership -> !membership.getJoinedAt().isAfter(share.getCreatedAt()))
                .isPresent();
        if (!allowed) {
            throw new BusinessException(ErrorCode.SHARE_005);
        }

        log.debug("위험 통화 공유 번호 조회 — shareId={}", shareId);
        return new DangerShareDto.PhoneResponse(share.getPhoneNumber());
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 하이픈·공백을 빼고 숫자만 남깁니다. {@code users.phone_number} 와 같은 저장 형식입니다.
     *
     * <p>오류 메시지에 입력값을 넣지 않습니다 — 응답 본문도 로그도 마찬가지입니다.
     */
    private String normalizePhone(String raw) {
        String normalized = PHONE_SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (!PHONE_DIGITS.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.COMMON_002, "phone_number 형식이 올바르지 않아요");
        }
        return normalized;
    }

    /**
     * 동시 요청 두 개가 ③을 함께 통과하면 uq_danger_shares_analysis 가 막습니다.
     *
     * <p><b>원래 예외의 메시지를 버립니다.</b> DB 제약 위반 메시지에는 PostgreSQL 이 넣은
     * 행 내용이 들어갈 수 있고, 그 행에 의심 번호가 있습니다. 409 로만 바꿔 던지고, 원인은
     * 예외 클래스명만 남깁니다.
     */
    private DangerShare saveOrConflict(DangerShare share) {
        try {
            return dangerShareRepository.saveAndFlush(share);
        } catch (DataIntegrityViolationException e) {
            log.debug("위험 통화 공유 저장 충돌 — {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SHARE_003);
        }
    }
}