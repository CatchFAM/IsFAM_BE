package com.isfam.domain.family.service;

import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.dto.FamilyDto;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMemberAliasRepository;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyPassphraseRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.service.NotificationService;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GET /family 의 phone_number.
 *
 * <p>번호는 <b>가족과 전화번호 공유(PHONE_SHARING)에 동의한 멤버만</b> 실립니다.
 * 동의 확인 없이 실으면 동의 없는 제3자 제공이 되므로 이 조건을 테스트로 고정합니다.
 */
@ExtendWith(MockitoExtension.class)
class FamilyServicePhoneNumberTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Long FAMILY_ID = 1L;
    private static final Long ME = 10L;
    private static final Long AGREED = 11L;
    private static final Long NOT_AGREED = 12L;

    @Mock FamilyRepository familyRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock FamilyMemberAliasRepository aliasRepository;
    @Mock FamilyPassphraseRepository passphraseRepository;
    @Mock UserRepository userRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock VoiceprintRepository voiceprintRepository;
    @Mock NotificationService notificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    private FamilyService service;

    @BeforeEach
    void setUp() {
        service = new FamilyService(familyRepository, membershipRepository, aliasRepository,
                passphraseRepository, userRepository, userAgreementRepository, voiceprintRepository,
                notificationService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void phoneNumberIsSharedOnlyForMembersWhoAgreed() {
        FamilyMembership mine = membership(ME, FamilyMembership.Role.OWNER);
        given(mine.getFamilyId()).willReturn(FAMILY_ID);
        FamilyMembership agreed = membership(AGREED, FamilyMembership.Role.MEMBER);
        FamilyMembership notAgreed = membership(NOT_AGREED, FamilyMembership.Role.MEMBER);

        given(membershipRepository.findByUserIdAndStatus(ME, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(mine));
        given(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .willReturn(Optional.of(family()));
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(List.of(mine, agreed, notAgreed));

        User me = user(ME, "나");
        given(me.getPhoneNumber()).willReturn("01000000010");
        User agreedUser = user(AGREED, "엄마");
        given(agreedUser.getPhoneNumber()).willReturn("01022147788");
        User notAgreedUser = user(NOT_AGREED, "아빠");
        given(userRepository.findAllById(List.of(ME, AGREED, NOT_AGREED)))
                .willReturn(List.of(me, agreedUser, notAgreedUser));

        given(voiceprintRepository.findRegisteredUserIds(List.of(ME, AGREED, NOT_AGREED)))
                .willReturn(List.of());
        // NOT_AGREED 는 동의 기록이 없는 계정(이 항목 이전 가입자)과 같은 상태
        given(userAgreementRepository.findAgreedUserIds(
                List.of(ME, AGREED, NOT_AGREED), UserAgreement.Type.PHONE_SHARING))
                .willReturn(List.of(ME, AGREED));
        given(aliasRepository.findByFamilyIdAndViewerUserId(FAMILY_ID, ME)).willReturn(List.of());

        FamilyDto.FamilyResponse response = service.getMyFamily(ME);

        assertThat(response.members())
                .extracting(FamilyDto.Member::userId, FamilyDto.Member::phoneNumber)
                .containsExactly(
                        tuple(ME, "01000000010"),
                        tuple(AGREED, "01022147788"),
                        tuple(NOT_AGREED, null));

        // 동의하지 않은 사람의 번호는 꺼내지도 않습니다
        verify(notAgreedUser, never()).getPhoneNumber();
    }

    // ── 내부 ──────────────────────────────────────────────

    private Family family() {
        Family family = mock(Family.class);
        given(family.getId()).willReturn(FAMILY_ID);
        given(family.getName()).willReturn("우리 가족");
        given(family.getCreatedAt()).willReturn(NOW);
        return family;
    }

    private FamilyMembership membership(Long userId, FamilyMembership.Role role) {
        FamilyMembership membership = mock(FamilyMembership.class);
        given(membership.getUserId()).willReturn(userId);
        given(membership.getMemberRole()).willReturn(role);
        given(membership.getJoinedAt()).willReturn(NOW);
        return membership;
    }

    private User user(Long id, String displayName) {
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        given(user.getDisplayName()).willReturn(displayName);
        return user;
    }
}