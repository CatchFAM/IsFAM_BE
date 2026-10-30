package com.isfam.domain.notification.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.repository.VoiceAnalysisRepository;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.notification.entity.DangerShare;
import com.isfam.domain.notification.repository.DangerShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DangerShareServiceReadTest {

    private static final Instant NOW = Instant.parse("2026-10-31T01:00:00Z");
    private static final Instant SHARED_AT = NOW.minus(Duration.ofHours(1));
    private static final Long FAMILY_ID = 1L;
    private static final Long SHARE_ID = 88L;
    private static final Long DAUGHTER = 14L;
    private static final Long MOM = 13L;

    @Mock DangerShareRepository dangerShareRepository;
    @Mock VoiceAnalysisRepository voiceAnalysisRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock NotificationService notificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    DangerShareService service;

    @BeforeEach
    void setUp() {
        service = new DangerShareService(dangerShareRepository, voiceAnalysisRepository,
                membershipRepository, notificationService, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        givenShare(unknownNumberShare(SHARED_AT));
        givenMember(MOM, SHARED_AT.minus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("공유 전에 들어온 현재 멤버는 번호를 받는다")
    void member() {
        var response = service.getPhoneNumber(MOM, SHARE_ID);

        assertThat(response.phoneNumber()).isEqualTo("01055218842");
        assertThat(response.toString()).doesNotContain("8842");
    }

    @Test
    @DisplayName("공유와 같은 시각에 들어온 사람은 받는다 (경계)")
    void joinedAtSameInstant() {
        givenMember(MOM, SHARED_AT);

        assertThat(service.getPhoneNumber(MOM, SHARE_ID).phoneNumber()).isEqualTo("01055218842");
    }

    @Test
    @DisplayName("공유 뒤에 들어온 사람은 404")
    void joinedAfterShare() {
        givenMember(MOM, SHARED_AT.plusSeconds(1));

        assertNotFound(MOM);
    }

    @Test
    @DisplayName("그 공간의 현재 멤버가 아니면 404 (나감·강퇴·다른 공간)")
    void notMember() {
        given(membershipRepository.findByFamilyIdAndUserIdAndStatus(
                FAMILY_ID, MOM, FamilyMembership.Status.ACTIVE)).willReturn(Optional.empty());

        assertNotFound(MOM);
    }

    @Test
    @DisplayName("공유가 없으면 404")
    void missing() {
        given(dangerShareRepository.findById(SHARE_ID)).willReturn(Optional.empty());

        assertNotFound(MOM);
    }

    @Test
    @DisplayName("정확히 7일이 지나면 404, 1초 전이면 받는다 (배치가 지우기 전이라도)")
    void readablePeriodBoundary() {
        givenShare(unknownNumberShare(NOW.minus(Duration.ofDays(7))));
        givenMember(MOM, NOW.minus(Duration.ofDays(30)));
        assertNotFound(MOM);

        givenShare(unknownNumberShare(NOW.minus(Duration.ofDays(7)).plusSeconds(1)));
        assertThat(service.getPhoneNumber(MOM, SHARE_ID).phoneNumber()).isEqualTo("01055218842");
    }

    @Test
    @DisplayName("가족 위장 공유는 번호가 없어 404")
    void impersonatedShareHasNoNumber() {
        DangerShare share = DangerShare.impersonated(502L, DAUGHTER, FAMILY_ID, 12L);
        ReflectionTestUtils.setField(share, "id", SHARE_ID);
        ReflectionTestUtils.setField(share, "createdAt", SHARED_AT);
        givenShare(share);

        assertNotFound(MOM);
    }

    // ── 도우미 ──────────────────────────────────────────────

    private DangerShare unknownNumberShare(Instant createdAt) {
        DangerShare share = DangerShare.unknownNumber(502L, DAUGHTER, FAMILY_ID, "01055218842");
        ReflectionTestUtils.setField(share, "id", SHARE_ID);
        ReflectionTestUtils.setField(share, "createdAt", createdAt);
        return share;
    }

    private void givenShare(DangerShare share) {
        given(dangerShareRepository.findById(SHARE_ID)).willReturn(Optional.of(share));
    }

    private void givenMember(Long userId, Instant joinedAt) {
        given(membershipRepository.findByFamilyIdAndUserIdAndStatus(
                FAMILY_ID, userId, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(FamilyMembership.join(FAMILY_ID, userId, joinedAt, joinedAt)));
    }

    private void assertNotFound(Long requester) {
        assertThatThrownBy(() -> service.getPhoneNumber(requester, SHARE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHARE_005);
    }
}