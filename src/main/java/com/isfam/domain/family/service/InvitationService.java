package com.isfam.domain.family.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.dto.InvitationDto;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 초대 링크 발급·수락.
 *
 * <p>코드는 <b>공간당 1개, 재사용형</b>입니다. 한 링크로 온 가족이 각자 수락하며
 * "이미 사용된 코드" 검사는 없습니다. 코드가 죽는 경우는 세 가지뿐입니다 —
 * 회전(POST), 비활성화(DELETE), 72시간 만료.
 *
 * <p><b>GET 과 POST 의 차이가 이 도메인의 핵심입니다.</b> service-flow.md 12장이
 * "초대 화면 진입 시 POST 호출"을 자주 틀리는 지점으로 꼽습니다. 화면을 열 때마다 회전하면
 * 방금 카톡으로 보낸 링크가 죽어서, 받는 쪽에는 "만료된 초대예요"만 뜹니다.
 * 서버가 막을 수 있는 실수는 아니라 문서와 주석으로 계속 표시해 둡니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final FamilyRepository familyRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;

    /** 명세 [정책] 만료 72시간 */
    @Value("${isfam.invite.code-ttl-hours:72}")
    private long codeTtlHours;

    /** 딥링크 도메인. 운영/스테이징에서 달라지므로 하드코딩하지 않습니다 */
    @Value("${isfam.invite.link-format:https://isfam.app/i/%s}")
    private String linkFormat;

    @Value("${isfam.invite.qr-url-format:https://isfam.app/qr/%s.png}")
    private String qrUrlFormat;

    // ── 초대 링크 조회 ──────────────────────────────────────

    /**
     * 초대 화면 진입 시 호출됩니다. <b>여기서는 절대 코드를 만들지 않습니다.</b>
     *
     * <p>코드가 없거나 만료·비활성이면 200 + 전 필드 null 입니다. 404 가 아닌 이유는
     * "공간은 있는데 링크가 없는 상태"가 정상 상태이기 때문입니다 — 앱은 이걸 받아
     * "초대 링크 만들기" 버튼을 띄웁니다. FAMILY_002(404)는 공간 자체가 없을 때만입니다.
     */
    @Transactional(readOnly = true)
    public InvitationDto.InviteCodeResponse getInviteCode(Long userId) {
        Instant now = clock.instant();

        FamilyMembership myMembership = findMyActiveMembership(userId);
        Family family = findAliveFamily(myMembership.getFamilyId());

        return family.hasActiveInviteCode(now)
                ? toInviteCodeResponse(family)
                : InvitationDto.InviteCodeResponse.inactive();
    }

    // ── 초대 링크 발급(회전) ────────────────────────────────

    /**
     * "링크 새로 만들기" 버튼 전용. 기존 코드를 즉시 무효화하고 새로 발급합니다.
     *
     * <p><b>owner 전용이 아닙니다.</b> 명세의 권한이 "status=active 멤버"입니다.
     * 일반 멤버도 회전시킬 수 있어 owner 가 공유한 링크를 죽일 수 있는데, 이건 명세의 선택이고
     * 조이려면 DELETE 처럼 owner 로 제한하면 됩니다.
     *
     * <p>QR 은 코드 기반이라 URL 이 함께 갱신됩니다. 이미지 자체는 이 서버가 만들지 않고
     * {@code qr_code_url} 로 위임합니다.
     */
    @Transactional
    public InvitationDto.InviteCodeResponse issueInviteCode(Long userId, Boolean voiceSharingConsent) {
        Instant now = clock.instant();

        FamilyMembership myMembership = findMyActiveMembership(userId);
        Family family = findAliveFamily(myMembership.getFamilyId());

        family.rotateInviteCode(
                inviteCodeGenerator.generate(), now, Duration.ofHours(codeTtlHours));

        // [명세 확장] 링크를 만드는 사람의 제3자 제공 동의. 보내지 않으면 아무것도 기록하지 않습니다.
        // 공간 생성자(owner)가 동의를 남길 수 있는 유일한 지점입니다 — 자세한 배경은
        // FamilyMembership.voiceSharingConsentedAt 주석 참고.
        if (Boolean.TRUE.equals(voiceSharingConsent)
                && myMembership.getVoiceSharingConsentedAt() == null) {
            myMembership.consentVoiceSharing(now);
        }

        return toInviteCodeResponse(family);
    }

    // ── 초대 링크 비활성화 ──────────────────────────────────

    /** owner 전용(FAMILY_003). families.invite_code=null 로 만들어 링크를 죽입니다 */
    @Transactional
    public void deactivateInviteCode(Long userId) {
        FamilyMembership myMembership = findMyActiveMembership(userId);
        requireOwner(myMembership);

        findAliveFamily(myMembership.getFamilyId()).deactivateInviteCode();
    }

    // ── 초대 미리보기 ───────────────────────────────────────

    /**
     * 딥링크 진입 직후, <b>로그인 전에</b> 호출됩니다.
     *
     * <p>없는 코드는 404, 만료·비활성은 410 으로 갈립니다(둘 다 INVITE_001).
     * 앱 문구는 어차피 "만료된 초대예요" 하나지만, 상태를 구분해 두면 코드 무차별 대입인지
     * 링크 회전 타이밍 문제인지 로그에서 갈라집니다.
     *
     * <p>만료된 코드를 지우지 않고 남겨두기 때문에 이 구분이 가능합니다.
     */
    @Transactional(readOnly = true)
    public InvitationDto.PreviewResponse preview(String rawCode) {
        Instant now = clock.instant();

        Family family = familyRepository
                .findByInviteCodeAndDeletedAtIsNull(InviteCodeGenerator.normalize(rawCode))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_001));

        requireActiveInviteCode(family, now);

        long memberCount = membershipRepository.countByFamilyIdAndStatus(family.getId(), ACTIVE);

        return new InvitationDto.PreviewResponse(
                family.getInviteCode(),
                family.getName(),
                (int) memberCount,
                family.getInviteCodeExpiresAt());
    }

    // ── 초대 수락 ───────────────────────────────────────────

    /**
     * 가입 또는 로그인을 마친 뒤 호출됩니다.
     *
     * <p><b>검사 순서가 응답 품질을 결정합니다.</b>
     * <ol>
     *   <li>코드 유효성 (404 / 410 INVITE_001) — 재시도해도 소용없는 상태를 먼저 알립니다</li>
     *   <li>동의 여부 (422 INVITE_003)</li>
     *   <li>이 공간의 active 멤버인가 (409 INVITE_002)</li>
     *   <li>다른 공간 소속인가 (409 FAMILY_004)</li>
     *   <li>정원 (409 FAMILY_006)</li>
     * </ol>
     * 3 이 4 보다 먼저여야 합니다. 이미 이 공간 멤버인 사람은 두 조건을 동시에 만족하는데,
     * FAMILY_004 를 먼저 보면 앱이 "'우리 가족'을 나가고 수락할까요?"를 띄웁니다 —
     * 지금 있는 그 공간에 다시 들어가려고 나가라는 안내가 됩니다.
     *
     * <p>수락 후 앱은 {@code GET /family/embeddings} 를 since 없이 전량 호출하고
     * {@code sync_trigger: "joined"} 로 기록합니다.
     */
    @Transactional
    public InvitationDto.AcceptResponse accept(Long userId, String rawCode, Boolean voiceSharingConsent) {
        Instant now = clock.instant();

        // 정원 검사를 안전하게 하려면 여기서 공간을 잠가야 합니다 — FamilyRepository 주석 참고
        Family family = familyRepository
                .findByInviteCodeForUpdate(InviteCodeGenerator.normalize(rawCode))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_001));

        requireActiveInviteCode(family, now);

        // 제3자 제공 동의는 법 제23조상 필수라 없으면 진행 자체가 불가능합니다.
        // false 와 누락(null)을 같게 다룹니다 — 둘 다 "동의를 받지 못한" 상태입니다.
        if (!Boolean.TRUE.equals(voiceSharingConsent)) {
            throw new BusinessException(ErrorCode.INVITE_003);
        }

        // status 를 안 보고 조회합니다. 나갔던 사람이 다시 들어오는 경로가 여기입니다.
        Optional<FamilyMembership> existing =
                membershipRepository.findByFamilyIdAndUserId(family.getId(), userId);

        if (existing.map(FamilyMembership::isActive).orElse(false)) {
            throw new BusinessException(ErrorCode.INVITE_002);
        }
        if (membershipRepository.existsByUserIdAndStatus(userId, ACTIVE)) {
            throw new BusinessException(ErrorCode.FAMILY_004);
        }

        long activeCount = membershipRepository.countByFamilyIdAndStatus(family.getId(), ACTIVE);
        if (activeCount >= family.getMaxMembers()) {
            throw new BusinessException(ErrorCode.FAMILY_006);
        }

        FamilyMembership membership = existing
                .map(found -> {
                    found.rejoin(now, now);
                    return found;
                })
                .orElseGet(() -> FamilyMembership.join(family.getId(), userId, now, now));

        try {
            membershipRepository.saveAndFlush(membership);
        } catch (DataIntegrityViolationException e) {
            // 위 검사와 flush 사이에 다른 요청이 끼어든 경우.
            // 부분 유니크 인덱스(uq_membership_active_user)가 잡아줍니다.
            throw new BusinessException(ErrorCode.FAMILY_004);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_009));

        // TODO(Phase 5): 동기화 푸시 — 기존 멤버는 새 멤버의 임베딩을, 새 멤버는 가족 전원의
        //   임베딩을 받아야 하므로 대상에 본인을 포함합니다. reason = member_joined.
        // TODO(Phase 5): notifications INSERT — type=member_joined, 대상은 본인 제외한 기존 멤버.
        //   여길 빼먹으면 알림함이 영원히 빈 화면입니다(service-flow.md 12장).
        Set<Long> syncTargets = collectSyncTargets(family.getId());
        log.debug("초대 수락 — 동기화 푸시 대상 {}건 (familyId={}, userId={})",
                syncTargets.size(), family.getId(), userId);

        return new InvitationDto.AcceptResponse(
                family.getId(),
                family.getName(),
                userId,
                user.getDisplayName(),
                membership.getMemberRole(),
                membership.getStatus(),
                membership.getJoinedAt());
    }

    // ── 내부 ────────────────────────────────────────────────

    /** 방금 들어온 본인까지 포함한 active 멤버 전원 */
    private Set<Long> collectSyncTargets(Long familyId) {
        Set<Long> targets = new HashSet<>();
        membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .forEach(membership -> targets.add(membership.getUserId()));
        return targets;
    }

    /** 코드는 있는데 만료·비활성이면 410 입니다. 404 는 코드 자체를 못 찾은 경우 */
    private void requireActiveInviteCode(Family family, Instant now) {
        if (!family.hasActiveInviteCode(now)) {
            throw new BusinessException(ErrorCode.INVITE_001, HttpStatus.GONE);
        }
    }

    private FamilyMembership findMyActiveMembership(Long userId) {
        return membershipRepository.findByUserIdAndStatus(userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
    }

    private Family findAliveFamily(Long familyId) {
        return familyRepository.findByIdAndDeletedAtIsNull(familyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
    }

    private void requireOwner(FamilyMembership membership) {
        if (!membership.isOwner()) {
            throw new BusinessException(ErrorCode.FAMILY_003);
        }
    }

    private InvitationDto.InviteCodeResponse toInviteCodeResponse(Family family) {
        String code = family.getInviteCode();
        return new InvitationDto.InviteCodeResponse(
                code,
                linkFormat.formatted(code),
                qrUrlFormat.formatted(code),
                family.getInviteCodeExpiresAt(),
                family.getInviteCodeRotatedAt());
    }
}