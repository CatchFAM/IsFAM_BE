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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 초대 수락·링크 발급과 음성 공유.
 *
 * <p>음성 공유 동의는 <b>가입 때</b> 받습니다(user_agreements.VOICE_SHARING). 초대 수락은 그 동의로
 * 게이팅 값(voice_sharing_consented_at)을 채우기만 하고, 동의를 다시 받거나 이력 행을 남기지 않습니다.
 */
@ExtendWith(MockitoExtension.class)
class InvitationServiceConsentTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
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

    /** 가입 동의가 있으면 수락하는 순간 공유가 시작됩니다. 동의 이력은 가입 때 이미 있습니다 */
    @Test
    void acceptStartsSharingFromSignupConsent() {
        givenVoiceSharingAgreed(true);
        givenAcceptSucceeds(Optional.empty());

        service.accept(USER_ID, CODE);

        FamilyMembership membership = capturedMembership();
        assertThat(membership.getVoiceSharingConsentedAt()).isEqualTo(NOW);
        assertThat(membership.getJoinedAt()).isEqualTo(NOW);
        verify(userAgreementRepository, never()).save(any());
    }

    /** 나갔던 공간에 다시 들어오면 공유 시작 시각은 이번 참여 시각입니다 */
    @Test
    void rejoinRestartsSharingAtJoinTime() {
        Instant before = NOW.minusSeconds(3600);
        FamilyMembership left = FamilyMembership.join(FAMILY_ID, USER_ID, before, before);
        left.leave();

        givenVoiceSharingAgreed(true);
        givenAcceptSucceeds(Optional.of(left));

        service.accept(USER_ID, CODE);

        assertThat(left.isActive()).isTrue();
        assertThat(left.getVoiceSharingConsentedAt()).isEqualTo(NOW);
        assertThat(left.getJoinedAt()).isEqualTo(NOW);
    }

    /**
     * 동의 행이 없는 계정 — 가입에서 필수라 정상 흐름에선 없지만, 있더라도 공유를 켜지 않습니다.
     * 수락 자체는 진행됩니다(목소리만 배포되지 않음).
     */
    @Test
    void acceptWithoutAgreementJoinsButDoesNotShare() {
        givenVoiceSharingAgreed(false);
        givenAcceptSucceeds(Optional.empty());

        service.accept(USER_ID, CODE);

        FamilyMembership membership = capturedMembership();
        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getVoiceSharingConsentedAt()).isNull();
    }

    /** 멤버십 검사에서 실패하면 아무것도 저장되지 않습니다 */
    @Test
    void acceptRejectedForOtherFamilySavesNothing() {
        Family family = givenActiveCodeOnly();
        given(family.getId()).willReturn(FAMILY_ID);
        given(membershipRepository.findByFamilyIdAndUserId(FAMILY_ID, USER_ID)).willReturn(Optional.empty());
        given(membershipRepository.existsByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(true);

        assertThatThrownBy(() -> service.accept(USER_ID, CODE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FAMILY_004);

        verify(membershipRepository, never()).saveAndFlush(any());
        verify(userAgreementRepository, never()).save(any());
    }

    // ── 링크 발급 ──────────────────────────────────────────

    /** 링크 발급은 동의·공유 상태를 건드리지 않습니다 — owner 공유는 공간 생성 때 켜집니다 */
    @Test
    void issueDoesNotTouchConsent() {
        FamilyMembership owner = FamilyMembership.createOwner(
                FAMILY_ID, USER_ID, NOW.minusSeconds(60), NOW.minusSeconds(60));
        givenIssuable(owner);

        service.issueInviteCode(USER_ID);

        assertThat(owner.getVoiceSharingConsentedAt()).isEqualTo(NOW.minusSeconds(60));
        verifyNoInteractions(userAgreementRepository);
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenVoiceSharingAgreed(boolean agreed) {
        given(userAgreementRepository.existsByUserIdAndAgreementTypeAndAgreedTrue(
                USER_ID, UserAgreement.Type.VOICE_SHARING)).willReturn(agreed);
    }

    private Family givenActiveCodeOnly() {
        Family family = mock(Family.class);
        given(family.hasActiveInviteCode(NOW)).willReturn(true);
        given(familyRepository.findByInviteCodeForUpdate(CODE)).willReturn(Optional.of(family));
        return family;
    }

    /** 코드 유효 · 다른 공간 소속 아님 · 정원 여유 · 수락 성공까지 */
    private void givenAcceptSucceeds(Optional<FamilyMembership> existing) {
        Family family = givenActiveCodeOnly();
        given(family.getId()).willReturn(FAMILY_ID);
        given(family.getMaxMembers()).willReturn(Family.DEFAULT_MAX_MEMBERS);
        given(membershipRepository.findByFamilyIdAndUserId(FAMILY_ID, USER_ID)).willReturn(existing);
        given(membershipRepository.existsByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(false);
        given(membershipRepository.countByFamilyIdAndStatus(FAMILY_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(1L);
        given(membershipRepository.saveAndFlush(any(FamilyMembership.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE)).willReturn(List.of());

        User user = mock(User.class);
        given(user.getDisplayName()).willReturn("김영희");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    }

    private void givenIssuable(FamilyMembership owner) {
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(owner));
        given(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .willReturn(Optional.of(mock(Family.class)));
        given(inviteCodeGenerator.generate()).willReturn(CODE);
    }

    private FamilyMembership capturedMembership() {
        ArgumentCaptor<FamilyMembership> captor = ArgumentCaptor.forClass(FamilyMembership.class);
        verify(membershipRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}