package com.isfam.domain.family.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 14 P-07 — 음성 제3자 제공 동의(VOICE_SHARING) 이력.
 *
 * <p>완료 기준: <b>초대 수락 → VOICE_SHARING 기록 + 게이팅 값(voice_sharing_consented_at) 동시 갱신</b>.
 * owner 의 링크 발급 동의도 같은 모양으로 남습니다.
 */
@ExtendWith(MockitoExtension.class)
class InvitationServiceConsentTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Long USER_ID = 7L;
    private static final Long FAMILY_ID = 1L;
    private static final String CODE = "AB12CD";

    @Mock FamilyRepository familyRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock UserRepository userRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock InviteCodeGenerator inviteCodeGenerator;
    @Mock NotificationService notificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    private InvitationService service;

    @BeforeEach
    void setUp() {
        service = new InvitationService(familyRepository, membershipRepository, userRepository,
                userAgreementRepository, inviteCodeGenerator, notificationService, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "codeTtlHours", 72L);
        ReflectionTestUtils.setField(service, "linkFormat", "https://isfam.app/i/%s");
        ReflectionTestUtils.setField(service, "qrUrlFormat", "https://isfam.app/qr/%s.png");
    }

    // ── 초대 수락 ───────────────────────────────────────────

    @Test
    void acceptRecordsVoiceSharingConsentAtSameInstantAsGate() {
        givenAcceptableFamily();
        given(membershipRepository.findByFamilyIdAndUserId(FAMILY_ID, USER_ID)).willReturn(Optional.empty());
        given(membershipRepository.saveAndFlush(any(FamilyMembership.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE)).willReturn(List.of());
        User user = mock(User.class);
        given(user.getDisplayName()).willReturn("김영희");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        service.accept(USER_ID, CODE, true);

        UserAgreement saved = captureAgreement();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getAgreementType()).isEqualTo(UserAgreement.Type.VOICE_SHARING);
        assertThat(saved.isAgreed()).isTrue();
        assertThat(saved.getAgreementVersion()).isNotBlank();
        assertThat(saved.getAgreedAt()).isEqualTo(NOW);

        // 게이팅 값과 같은 시각 — 두 기록이 어긋나지 않아야 합니다
        ArgumentCaptor<FamilyMembership> membership = ArgumentCaptor.forClass(FamilyMembership.class);
        verify(membershipRepository).saveAndFlush(membership.capture());
        assertThat(membership.getValue().getVoiceSharingConsentedAt()).isEqualTo(saved.getAgreedAt());
    }

    @Test
    void acceptWithoutConsentLeavesNoAgreement() {
        givenActiveCodeOnly();

        assertThatThrownBy(() -> service.accept(USER_ID, CODE, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVITE_003);

        verify(userAgreementRepository, never()).save(any());
    }

    /** 멤버십 확정 전에 실패하면 동의 이력도 남지 않습니다 */
    @Test
    void acceptRejectedForOtherFamilyLeavesNoAgreement() {
        Family family = givenActiveCodeOnly();
        given(family.getId()).willReturn(FAMILY_ID);
        given(membershipRepository.findByFamilyIdAndUserId(FAMILY_ID, USER_ID)).willReturn(Optional.empty());
        given(membershipRepository.existsByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(true);

        assertThatThrownBy(() -> service.accept(USER_ID, CODE, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FAMILY_004);

        verify(userAgreementRepository, never()).save(any());
    }

    // ── 링크 발급 (owner 동의) ──────────────────────────────

    @Test
    void issueWithFirstConsentRecordsAgreement() {
        FamilyMembership owner = FamilyMembership.createOwner(FAMILY_ID, USER_ID, NOW.minusSeconds(60));
        givenIssuable(owner);

        service.issueInviteCode(USER_ID, true);

        assertThat(owner.getVoiceSharingConsentedAt()).isEqualTo(NOW);
        UserAgreement saved = captureAgreement();
        assertThat(saved.getAgreementType()).isEqualTo(UserAgreement.Type.VOICE_SHARING);
        assertThat(saved.getAgreedAt()).isEqualTo(NOW);
    }

    /** 링크를 돌릴 때마다 true 가 와도 이력은 처음 한 번만 */
    @Test
    void issueWhenAlreadyConsentedDoesNotRecordAgain() {
        FamilyMembership owner = FamilyMembership.createOwner(FAMILY_ID, USER_ID, NOW.minusSeconds(60));
        owner.consentVoiceSharing(NOW.minusSeconds(30));
        givenIssuable(owner);

        service.issueInviteCode(USER_ID, true);

        verify(userAgreementRepository, never()).save(any());
        assertThat(owner.getVoiceSharingConsentedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void issueWithoutConsentRecordsNothing() {
        FamilyMembership owner = FamilyMembership.createOwner(FAMILY_ID, USER_ID, NOW.minusSeconds(60));
        givenIssuable(owner);

        service.issueInviteCode(USER_ID, null);

        verify(userAgreementRepository, never()).save(any());
        assertThat(owner.getVoiceSharingConsentedAt()).isNull();
    }

    // ── 내부 ──────────────────────────────────────────────

    private Family givenActiveCodeOnly() {
        Family family = mock(Family.class);
        given(family.hasActiveInviteCode(NOW)).willReturn(true);
        given(familyRepository.findByInviteCodeForUpdate(CODE)).willReturn(Optional.of(family));
        return family;
    }

    private void givenAcceptableFamily() {
        Family family = givenActiveCodeOnly();
        given(family.getId()).willReturn(FAMILY_ID);
        given(family.getMaxMembers()).willReturn(Family.DEFAULT_MAX_MEMBERS);
        given(membershipRepository.existsByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(false);
        given(membershipRepository.countByFamilyIdAndStatus(FAMILY_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(1L);
    }

    private void givenIssuable(FamilyMembership owner) {
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(owner));
        given(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .willReturn(Optional.of(mock(Family.class)));
        given(inviteCodeGenerator.generate()).willReturn(CODE);
    }

    private UserAgreement captureAgreement() {
        ArgumentCaptor<UserAgreement> captor = ArgumentCaptor.forClass(UserAgreement.class);
        verify(userAgreementRepository).save(captor.capture());
        return captor.getValue();
    }
}