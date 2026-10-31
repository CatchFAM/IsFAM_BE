package com.isfam.domain.family.service;

import com.isfam.common.crypto.PassphraseCipher;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.family.dto.FamilyPassphraseDto;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.entity.FamilyPassphrase;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyPassphraseRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 우리 집 암호 — {@code /family/passphrase}.
 *
 * <p>조회는 active 멤버 전원, 등록·수정·삭제는 owner 만 합니다.
 *
 * <p><b>알림.</b> 등록·수정 시 owner 를 뺀 active 멤버에게 {@code passphrase_updated} 를 남깁니다.
 * 가족 소식 알림 토글을 따르고, 꺼져 있어도 알림함엔 남습니다(기존 규칙 그대로).
 * <b>문구에 질문·정답을 넣지 않습니다</b> — 푸시는 FCM(미국)을 거치고 잠금화면에도 뜹니다.
 * 앱은 알림을 탭하면 GET 으로 가져갑니다.
 *
 * <p><b>삭제는 알림을 보내지 않습니다.</b> 화면에 없는 기능이라 지금은 정하지 않았습니다.
 *
 * <p>정답 평문은 이 클래스 밖으로 응답 DTO 로만 나갑니다. 로그에 찍지 마세요.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyPassphraseService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final FamilyPassphraseRepository passphraseRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final FamilyRepository familyRepository;
    private final NotificationService notificationService;
    private final PassphraseCipher cipher;

    // ── 조회 ────────────────────────────────────────────────

    /** 미설정이면 404 FAMILY_008 — 앱은 owner 면 설정 화면, member 면 "아직 없어요"로 분기합니다 */
    @Transactional(readOnly = true)
    public FamilyPassphraseDto.PassphraseResponse get(Long userId) {
        Long familyId = findMyFamilyId(userId);

        FamilyPassphrase passphrase = passphraseRepository.findByFamilyId(familyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_008));

        return toResponse(passphrase, cipher.decrypt(passphrase.getAnswerEncrypted(), familyId));
    }

    // ── 등록 · 수정 ─────────────────────────────────────────

    /**
     * 없으면 만들고 있으면 바꿉니다. 화면의 "가족에게 공유하고 저장" 버튼 하나가 둘을 겸합니다.
     *
     * <p><b>질문·정답이 그대로면 아무것도 하지 않습니다.</b> 저장 버튼을 다시 눌렀다고
     * 가족 전원에게 "암호가 바뀌었어요"가 가면 안 됩니다. 응답의 updated_at 도 이전 값 그대로입니다.
     *
     * <p>같은 공간에서 첫 등록이 동시에 두 번 들어오면 uq_passphrase_family 가 잡습니다.
     * 쓸 수 있는 사람이 owner 하나라 경합이 드물고, 진 쪽이 재시도하면 수정 경로로 붙으므로
     * 재시도 로직을 두지 않았습니다(별명의 uq_alias_family_viewer_target 과 같은 판단).
     *
     * <p>{@code saveAndFlush} 인 이유는 FamilyService.rename() 과 같습니다 —
     * {@code @LastModifiedDate} 는 flush 시점에 찍힙니다.
     */
    @Transactional
    public FamilyPassphraseDto.PassphraseResponse save(Long userId, String rawQuestion, String rawAnswer) {
        FamilyMembership myMembership = findMyActiveMembership(userId);
        requireOwner(myMembership);
        Long familyId = myMembership.getFamilyId();
        requireAliveFamily(familyId);

        String question = normalize(rawQuestion);
        String answer = normalize(rawAnswer);

        Optional<FamilyPassphrase> existing = passphraseRepository.findByFamilyId(familyId);

        if (existing.isPresent() && isUnchanged(existing.get(), question, answer, familyId)) {
            return toResponse(existing.get(), answer);
        }

        boolean firstTime = existing.isEmpty();
        byte[] encrypted = cipher.encrypt(answer, familyId);

        FamilyPassphrase passphrase = existing
                .map(row -> {
                    row.change(question, encrypted);
                    return row;
                })
                .orElseGet(() -> FamilyPassphrase.create(familyId, question, encrypted));

        FamilyPassphrase saved = passphraseRepository.saveAndFlush(passphrase);

        notifyOthers(familyId, userId, firstTime);

        return toResponse(saved, answer);
    }

    // ── 삭제 ────────────────────────────────────────────────

    /**
     * owner 전용. 없어도 204 입니다(멱등). 알림을 보내지 않습니다.
     */
    @Transactional
    public void delete(Long userId) {
        FamilyMembership myMembership = findMyActiveMembership(userId);
        requireOwner(myMembership);
        Long familyId = myMembership.getFamilyId();
        requireAliveFamily(familyId);

        passphraseRepository.findByFamilyId(familyId).ifPresent(passphraseRepository::delete);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 대상은 owner 를 뺀 active 멤버입니다. owner 는 자기가 누른 버튼이라 받을 이유가 없습니다
     * (FamilyService.removeMember 의 내보낸 owner 와 같은 판단).
     */
    private void notifyOthers(Long familyId, Long ownerUserId, boolean firstTime) {
        List<Long> targets = membershipRepository
                .findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .stream()
                .map(FamilyMembership::getUserId)
                .filter(memberId -> !memberId.equals(ownerUserId))
                .toList();

        targets.forEach(memberId ->
                notificationService.create(Notification.passphraseUpdated(memberId, firstTime)));

        log.debug("우리 집 암호 {} — 알림 {}건 (familyId={})",
                firstTime ? "등록" : "변경", targets.size(), familyId);
    }

    private boolean isUnchanged(FamilyPassphrase row, String question, String answer, Long familyId) {
        return row.getQuestion().equals(question)
                && cipher.decrypt(row.getAnswerEncrypted(), familyId).equals(answer);
    }

    /** {@code @NotBlank} 를 통과했어도 앞뒤 공백은 털어서 저장합니다 */
    private String normalize(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_002);
        }
        return trimmed;
    }

    private FamilyPassphraseDto.PassphraseResponse toResponse(FamilyPassphrase row, String answer) {
        return new FamilyPassphraseDto.PassphraseResponse(row.getQuestion(), answer, row.getUpdatedAt());
    }

    private Long findMyFamilyId(Long userId) {
        Long familyId = findMyActiveMembership(userId).getFamilyId();
        requireAliveFamily(familyId);
        return familyId;
    }

    private FamilyMembership findMyActiveMembership(Long userId) {
        return membershipRepository.findByUserIdAndStatus(userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));
    }

    /** 소프트 삭제된 공간은 없는 것으로 취급합니다 (FamilyService.findAliveFamily 와 같은 방어) */
    private void requireAliveFamily(Long familyId) {
        if (familyRepository.findByIdAndDeletedAtIsNull(familyId).isEmpty()) {
            throw new BusinessException(ErrorCode.FAMILY_002);
        }
    }

    private void requireOwner(FamilyMembership membership) {
        if (!membership.isOwner()) {
            throw new BusinessException(ErrorCode.FAMILY_003);
        }
    }
}