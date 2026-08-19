package com.isfam.domain.family.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.dto.FamilyDto;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.service.NotificationService;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import com.isfam.infra.push.event.FamilySyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 가족 공간 · 멤버십.
 *
 * <p><b>owner 권한 검사는 전부 여기서 합니다.</b> DB 를 봐야 판단되는 조건이라
 * SecurityConfig 의 URL 패턴으로는 결정되지 않습니다.
 *
 * <p><b>발송 대상은 반드시 상태 변경 <i>전에</i> 수집합니다.</b> service-flow.md 12장이
 * "삭제 처리 후에 푸시 대상 조회"를 자주 틀리는 지점으로 꼽는데, 그 순서로 짜면 나간 사람이
 * 목록에서 빠져 그 폰에 남의 생체정보가 그대로 남습니다.
 *
 * <p>푸시 자체는 여기서 보내지 않습니다. {@link FamilySyncEvent} 를 발행하면
 * {@code PushDispatcher} 가 <b>커밋 후에</b> 받아 발송합니다 — 롤백된 변경에 대해
 * 푸시가 나가는 걸 막고, FCM 장애로 멤버십 변경이 통째로 롤백되는 것도 막습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final FamilyRepository familyRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final VoiceprintRepository voiceprintRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ── 공간 생성 ───────────────────────────────────────────

    /**
     * 역할 제한 없이 누구나 만들 수 있고, 생성자에게 owner 멤버십이 자동 부여됩니다.
     *
     * <p>이미 다른 공간에 소속돼 있으면 FAMILY_004 입니다. 앱은 여기서
     * "'○○네 가족'을 나가고 만들까요?" 로 분기합니다.
     *
     * <p>voice_sharing_consent 는 요청에 없어 동의 시각을 null 로 둡니다 —
     * 이유는 {@link FamilyMembership#createOwner} 주석 참고.
     */
    @Transactional
    public FamilyDto.FamilyResponse create(Long userId, String name) {
        Instant now = clock.instant();

        if (membershipRepository.existsByUserIdAndStatus(userId, ACTIVE)) {
            throw new BusinessException(ErrorCode.FAMILY_004);
        }

        Family family = familyRepository.save(Family.create(name, userId));

        try {
            membershipRepository.saveAndFlush(
                    FamilyMembership.createOwner(family.getId(), userId, now));
        } catch (DataIntegrityViolationException e) {
            // 위 검사와 insert 사이에 다른 요청이 끼어든 경우.
            // 부분 유니크 인덱스(uq_membership_active_user)가 잡아줍니다.
            throw new BusinessException(ErrorCode.FAMILY_004);
        }

        return buildFamilyResponse(family, userId);
    }

    // ── 공간 조회 ───────────────────────────────────────────

    /**
     * 소속 공간이 없으면 404 FAMILY_002 입니다.
     * 앱은 이걸 받아 "공간 만들기 / 초대 코드 입력" 화면으로 분기합니다.
     */
    @Transactional(readOnly = true)
    public FamilyDto.FamilyResponse getMyFamily(Long userId) {
        FamilyMembership myMembership = findMyActiveMembership(userId);
        Family family = findAliveFamily(myMembership.getFamilyId());
        return buildFamilyResponse(family, userId);
    }

    // ── 공간 이름 수정 ──────────────────────────────────────

    /**
     * owner 만 가능합니다.
     *
     * <p>{@code saveAndFlush} 가 있는 이유는 응답의 updated_at 때문입니다.
     * {@code @LastModifiedDate} 는 flush 시점에 찍혀서, 그냥 반환하면 변경 <i>전</i> 값이 나갑니다.
     */
    @Transactional
    public FamilyDto.UpdateNameResponse rename(Long userId, String name) {
        FamilyMembership myMembership = findMyActiveMembership(userId);
        requireOwner(myMembership);

        Family family = findAliveFamily(myMembership.getFamilyId());
        family.rename(name);
        familyRepository.saveAndFlush(family);

        return new FamilyDto.UpdateNameResponse(
                family.getId(), family.getName(), family.getUpdatedAt());
    }

    // ── 공간 삭제 ───────────────────────────────────────────

    /**
     * owner 전용이고, <b>본인 외 active 멤버가 0명</b>이어야 합니다(FAMILY_007).
     *
     * <p>전원 추방을 선행 조건으로 강제하는 이유는 owner 가 버튼 하나로 온 가족의 보호를
     * 끊는 걸 막기 위해서입니다. 그래서 이 시점엔 owner 혼자라 <b>푸시 대상이 없습니다</b> —
     * 다른 멤버는 각자 추방 시점에 이미 정리됐습니다.
     *
     * <p>owner 본인 폰은 다음 GET /family/embeddings 에서 404(FAMILY_002)를 받고
     * 로컬 임베딩을 정리합니다. voiceprints 는 user 소유라 삭제되지 않습니다 —
     * 새 공간을 만들고 초대만 다시 하면 음성 재등록 없이 복구됩니다.
     */
    @Transactional
    public void delete(Long userId) {
        Instant now = clock.instant();

        FamilyMembership myMembership = findMyActiveMembership(userId);
        requireOwner(myMembership);

        Family family = findAliveFamily(myMembership.getFamilyId());

        long activeCount = membershipRepository.countByFamilyIdAndStatus(family.getId(), ACTIVE);
        if (activeCount > 1) {
            throw new BusinessException(ErrorCode.FAMILY_007);
        }

        family.softDelete(now);
        myMembership.remove();
    }

    // ── 나가기 / 내보내기 ───────────────────────────────────

    /**
     * 본인을 지정하면 나가기(status='left'), owner 가 타인을 지정하면 내보내기('removed').
     *
     * <p><b>row 는 지우지 않습니다.</b> 남은 멤버에게 removed_user_ids 로 알려야 하므로
     * "예전에 멤버였다"는 사실이 남아 있어야 합니다.
     *
     * <p>owner 는 개별 탈퇴가 불가능합니다(FAMILY_005). 전원 내보낸 뒤 DELETE /family 로
     * 공간을 삭제하는 게 owner 가 빠져나가는 유일한 경로입니다.
     */
    @Transactional
    public void removeMember(Long userId, Long targetUserId) {
        Instant now = clock.instant();

        FamilyMembership myMembership = findMyActiveMembership(userId);
        Long familyId = myMembership.getFamilyId();

        FamilyMembership target = membershipRepository
                .findByFamilyIdAndUserIdAndStatus(familyId, targetUserId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_001));

        boolean isSelf = userId.equals(targetUserId);

        if (isSelf) {
            if (myMembership.isOwner()) {
                throw new BusinessException(ErrorCode.FAMILY_005);
            }
        } else if (!myMembership.isOwner()) {
            // 명세의 에러 목록엔 없지만, owner 도 본인도 아닌 요청을 통과시킬 수는 없습니다
            throw new BusinessException(ErrorCode.FAMILY_003);
        }

        // ── 상태를 바꾸기 전에 푸시 대상을 먼저 모읍니다 (Phase 5) ──
        // 바꾼 뒤에 조회하면 나간 사람이 목록에서 빠져, 그 폰에 남의 임베딩이 그대로 남습니다.
        Set<Long> syncTargets = collectSyncTargets(familyId);

        if (isSelf) {
            target.leave();
        } else {
            target.remove();
        }

        // ── 알림함 ──────────────────────────────────────────
        // 대상은 "남은 멤버"입니다. 나간 본인은 자기가 나간 걸 알고, 내보낸 owner 도
        // 자기가 누른 버튼이라 알림을 받을 이유가 없습니다.
        // 자진 탈퇴(isSelf)면 userId == targetUserId 라 남은 전원이 받습니다.
        String targetName = userRepository.findById(targetUserId)
                .map(User::getDisplayName)
                .orElse("구성원");

        Set<Long> notifyTargets = new HashSet<>(syncTargets);
        notifyTargets.remove(targetUserId);
        notifyTargets.remove(userId);
        notifyTargets.forEach(memberId ->
                notificationService.create(Notification.memberLeft(memberId, targetName)));

        // ── 동기화 푸시 ─────────────────────────────────────
        // syncTargets 에는 방금 나간/강퇴된 사람이 포함돼 있습니다. 그 폰도 깨워서
        // GET /family/embeddings 에서 404(FAMILY_002)를 받고 로컬 임베딩을 지워야 합니다.
        eventPublisher.publishEvent(new FamilySyncEvent(
                syncTargets, FamilySyncEvent.Reason.MEMBER_REMOVED, familyId, now));

        log.debug("가족 구성 변경 — 동기화 대상 {}건, 알림 {}건 (familyId={})",
                syncTargets.size(), notifyTargets.size(), familyId);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 상태 변경 <b>전에</b> 호출해야 합니다. 이 시점의 active 멤버 전원이 대상이고,
     * 여기엔 곧 나갈 본인·강퇴당할 사람도 포함됩니다 — 그 폰들도 로컬 임베딩을 지워야 하므로.
     */
    private Set<Long> collectSyncTargets(Long familyId) {
        return membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .stream()
                .map(FamilyMembership::getUserId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private FamilyMembership findMyActiveMembership(Long userId) {
        return membershipRepository.findByUserIdAndStatus(userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
    }

    /**
     * 소프트 삭제된 공간은 없는 것으로 취급합니다.
     *
     * <p>삭제 시 owner 멤버십도 removed 로 바뀌므로 정상 흐름에서는 여기 도달하지 않습니다.
     * 둘 중 하나만 처리되는 상황을 대비한 방어입니다.
     */
    private Family findAliveFamily(Long familyId) {
        return familyRepository.findByIdAndDeletedAtIsNull(familyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
    }

    private void requireOwner(FamilyMembership membership) {
        if (!membership.isOwner()) {
            throw new BusinessException(ErrorCode.FAMILY_003);
        }
    }

    /**
     * members[] 조립. 쿼리는 멤버십·유저·보이스프린트 3번으로 고정입니다.
     * 멤버마다 조회하면 8명짜리 공간에서 17번이 나갑니다.
     */
    private FamilyDto.FamilyResponse buildFamilyResponse(Family family, Long myUserId) {
        List<FamilyMembership> memberships =
                membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(family.getId(), ACTIVE);

        List<Long> userIds = memberships.stream().map(FamilyMembership::getUserId).toList();

        // IN 절에 빈 컬렉션이 들어가면 DB 에 따라 문법 오류가 납니다.
        // 정상 흐름에선 최소 본인 1명이라 비지 않지만, 조립부가 조용히 터지면 원인 찾기가 어렵습니다.
        if (userIds.isEmpty()) {
            return new FamilyDto.FamilyResponse(
                    family.getId(), family.getName(), null, List.of(), family.getCreatedAt());
        }

        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<Long> registered = Set.copyOf(voiceprintRepository.findRegisteredUserIds(userIds));

        List<FamilyDto.Member> members = memberships.stream()
                .map(membership -> {
                    User user = users.get(membership.getUserId());
                    return new FamilyDto.Member(
                            membership.getUserId(),
                            user == null ? null : user.getDisplayName(),
                            membership.getMemberRole(),
                            registered.contains(membership.getUserId()),
                            membership.getJoinedAt());
                })
                .toList();

        FamilyMembership.Role myRole = memberships.stream()
                .filter(membership -> membership.getUserId().equals(myUserId))
                .findFirst()
                .map(FamilyMembership::getMemberRole)
                .orElse(null);

        return new FamilyDto.FamilyResponse(
                family.getId(), family.getName(), myRole, members, family.getCreatedAt());
    }
}