package com.isfam.domain.family.service;

import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMemberAliasRepository;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.service.NotificationService;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * POST /family — owner 의 음성 공유 게이팅.
 *
 * <p>가입 때 음성 공유에 동의한 사람은 공간을 만드는 순간부터 목소리가 가족에게 배포돼야 합니다.
 * 이 값이 null 로 남으면 {@code EmbeddingSyncService} 가 owner 의 임베딩을 내보내지 않아,
 * 가족 폰에서 owner 목소리만 대조가 안 되는 상태가 됩니다.
 */
@ExtendWith(MockitoExtension.class)
class FamilyServiceCreateTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final Long USER_ID = 10L;
    private static final Long FAMILY_ID = 1L;

    @Mock FamilyRepository familyRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock FamilyMemberAliasRepository aliasRepository;
    @Mock UserRepository userRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock VoiceprintRepository voiceprintRepository;
    @Mock NotificationService notificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    private FamilyService service;

    @BeforeEach
    void setUp() {
        service = new FamilyService(familyRepository, membershipRepository, aliasRepository,
                userRepository, userAgreementRepository, voiceprintRepository,
                notificationService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ownerSharesFromCreation() {
        givenCreatable();
        givenVoiceSharingAgreed(true);

        service.create(USER_ID, "우리 가족");

        FamilyMembership owner = capturedOwner();
        assertThat(owner.isOwner()).isTrue();
        assertThat(owner.getVoiceSharingConsentedAt()).isEqualTo(NOW);
        assertThat(owner.getJoinedAt()).isEqualTo(NOW);
        // 동의 이력은 가입 때 이미 있습니다 — 공간을 만들 때 새로 남기지 않습니다
        verify(userAgreementRepository, never()).save(any());
    }

    /** 동의 행이 없으면 받은 적 없는 동의를 시각만 찍어 남기지 않습니다 */
    @Test
    void ownerWithoutAgreementIsNotShared() {
        givenCreatable();
        givenVoiceSharingAgreed(false);

        service.create(USER_ID, "우리 가족");

        FamilyMembership owner = capturedOwner();
        assertThat(owner.isOwner()).isTrue();
        assertThat(owner.getVoiceSharingConsentedAt()).isNull();
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenVoiceSharingAgreed(boolean agreed) {
        given(userAgreementRepository.existsByUserIdAndAgreementTypeAndAgreedTrue(
                USER_ID, UserAgreement.Type.VOICE_SHARING)).willReturn(agreed);
    }

    private void givenCreatable() {
        given(membershipRepository.existsByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(false);

        Family family = mock(Family.class);
        given(family.getId()).willReturn(FAMILY_ID);
        given(family.getName()).willReturn("우리 가족");
        given(family.getCreatedAt()).willReturn(NOW);
        given(familyRepository.save(any(Family.class))).willReturn(family);

        given(membershipRepository.saveAndFlush(any(FamilyMembership.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        // 응답 조립은 이 테스트의 관심사가 아니라 빈 목록으로 짧게 끝냅니다
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE)).willReturn(List.of());
    }

    private FamilyMembership capturedOwner() {
        ArgumentCaptor<FamilyMembership> captor = ArgumentCaptor.forClass(FamilyMembership.class);
        verify(membershipRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}