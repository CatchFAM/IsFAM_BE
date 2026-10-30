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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DangerShareServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T01:00:00Z");
    private static final Long FAMILY_ID = 1L;
    private static final Long DAUGHTER = 14L;
    private static final Long DAD = 12L;
    private static final Long MOM = 13L;
    private static final Long ANALYSIS_ID = 502L;

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

        // 딸·아빠·엄마 3인 공간
        FamilyMembership daughter = member(DAUGHTER);
        given(membershipRepository.findByUserIdAndStatus(DAUGHTER, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(daughter));
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(List.of(member(DAD), member(MOM), daughter));

        givenAnalysis(DAUGHTER, VoiceAnalysis.FinalDecision.DANGER, NOW.minus(Duration.ofHours(1)));
        given(dangerShareRepository.existsByAnalysisId(ANALYSIS_ID)).willReturn(false);

        given(dangerShareRepository.saveAndFlush(any(DangerShare.class))).willAnswer(inv -> {
            DangerShare share = inv.getArgument(0);
            ReflectionTestUtils.setField(share, "id", 88L);
            return share;
        });
        given(notificationService.createWithoutPush(any(Notification.class))).willAnswer(inv -> {
            Notification notification = inv.getArgument(0);
            ReflectionTestUtils.setField(notification, "id", 1000L + notification.getUserId());
            return notification;
        });
    }

    // ── 성공 ────────────────────────────────────────────────

    @Test
    @DisplayName("모르는 번호 — 번호는 하이픈 없이 저장되고, 이벤트에는 번호가 없다")
    void unknownNumber() {
        var response = service.share(DAUGHTER, request(null, "010-5521-8842"));

        assertThat(response.shareId()).isEqualTo(88L);

        ArgumentCaptor<DangerShare> shareCaptor = ArgumentCaptor.forClass(DangerShare.class);
        verify(dangerShareRepository).saveAndFlush(shareCaptor.capture());
        assertThat(shareCaptor.getValue().getPhoneNumber()).isEqualTo("01055218842");
        assertThat(shareCaptor.getValue().getImpersonatedUserId()).isNull();

        DangerShareEvent event = capturedEvent();
        assertThat(event.hasPhoneNumber()).isTrue();
        assertThat(event.impersonatedUserId()).isNull();
        assertThat(event.recipients()).containsOnlyKeys(DAD, MOM);
        assertThat(event.toString()).doesNotContain("8842");
    }

    @Test
    @DisplayName("가족 번호 위장 — 사칭당한 본인(아빠)도 받는 사람에 들어간다")
    void impersonated() {
        service.share(DAUGHTER, request(DAD, null));

        ArgumentCaptor<DangerShare> shareCaptor = ArgumentCaptor.forClass(DangerShare.class);
        verify(dangerShareRepository).saveAndFlush(shareCaptor.capture());
        assertThat(shareCaptor.getValue().getPhoneNumber()).isNull();
        assertThat(shareCaptor.getValue().getImpersonatedUserId()).isEqualTo(DAD);

        DangerShareEvent event = capturedEvent();
        assertThat(event.hasPhoneNumber()).isFalse();
        assertThat(event.impersonatedUserId()).isEqualTo(DAD);
        assertThat(event.recipients()).containsOnlyKeys(DAD, MOM);
    }

    @Test
    @DisplayName("확인 필요(needs_check) 판정도 공유할 수 있다")
    void needsCheck() {
        givenAnalysis(DAUGHTER, VoiceAnalysis.FinalDecision.NEEDS_CHECK, NOW.minus(Duration.ofHours(1)));

        service.share(DAUGHTER, request(null, "01055218842"));

        verify(dangerShareRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("알림함은 받는 사람마다 1건, 일반 푸시 이벤트는 발행하지 않는다")
    void notificationsWithoutGenericPush() {
        service.share(DAUGHTER, request(null, "01055218842"));

        verify(notificationService, times(2)).createWithoutPush(any());
        verify(notificationService, never()).create(any());
    }

    // ── 400 ─────────────────────────────────────────────────

    @Test
    @DisplayName("둘 다 보내면 400")
    void both() {
        assertError(request(DAD, "01055218842"), ErrorCode.SHARE_001);
    }

    @Test
    @DisplayName("둘 다 없으면 400 — 빈 문자열 번호도 없는 것으로 본다")
    void neither() {
        assertError(request(null, null), ErrorCode.SHARE_001);
        assertError(request(null, "  "), ErrorCode.SHARE_001);
    }

    @Test
    @DisplayName("번호 형식이 틀리면 422 — 메시지에 입력값이 들어가지 않는다")
    void invalidPhone() {
        assertThatThrownBy(() -> service.share(DAUGHTER, request(null, "abc-1234")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.COMMON_002);
                    assertThat(e.getMessage()).doesNotContain("abc");
                });
    }

    // ── 403 · 404 ───────────────────────────────────────────

    @Test
    @DisplayName("남의 분석이면 403")
    void notOwnAnalysis() {
        givenAnalysis(DAD, VoiceAnalysis.FinalDecision.DANGER, NOW.minus(Duration.ofHours(1)));

        assertError(request(null, "01055218842"), ErrorCode.SHARE_002);
    }

    @Test
    @DisplayName("없는 분석도 403 — 존재 여부를 알려주지 않는다")
    void missingAnalysis() {
        given(voiceAnalysisRepository.findById(ANALYSIS_ID)).willReturn(Optional.empty());

        assertError(request(null, "01055218842"), ErrorCode.SHARE_002);
    }

    @Test
    @DisplayName("사칭된 가족이 같은 공간 멤버가 아니면 403")
    void impersonatedNotMember() {
        assertError(request(999L, null), ErrorCode.SHARE_002);
    }

    @Test
    @DisplayName("사칭된 가족이 본인이면 403")
    void impersonatedSelf() {
        assertError(request(DAUGHTER, null), ErrorCode.SHARE_002);
    }

    @Test
    @DisplayName("공간이 없으면 404 FAMILY_002")
    void noFamily() {
        given(membershipRepository.findByUserIdAndStatus(DAUGHTER, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.empty());

        assertError(request(null, "01055218842"), ErrorCode.FAMILY_002);
    }

    // ── 409 ─────────────────────────────────────────────────

    @Test
    @DisplayName("이미 공유한 분석이면 409")
    void alreadyShared() {
        given(dangerShareRepository.existsByAnalysisId(ANALYSIS_ID)).willReturn(true);

        assertError(request(null, "01055218842"), ErrorCode.SHARE_003);
    }

    @Test
    @DisplayName("동시 요청으로 유니크 제약에 걸려도 409 — 이벤트는 나가지 않는다")
    void concurrentConflict() {
        given(dangerShareRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("Failing row contains (01055218842)"));

        assertError(request(null, "01055218842"), ErrorCode.SHARE_003);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── 422 ─────────────────────────────────────────────────

    @Test
    @DisplayName("정상 판정이면 422")
    void normalDecision() {
        givenAnalysis(DAUGHTER, VoiceAnalysis.FinalDecision.NORMAL, NOW.minus(Duration.ofHours(1)));

        assertError(request(null, "01055218842"), ErrorCode.SHARE_004);
    }

    @Test
    @DisplayName("분석 후 정확히 24시간이면 422, 1초 전이면 통과")
    void shareableWindowBoundary() {
        givenAnalysis(DAUGHTER, VoiceAnalysis.FinalDecision.DANGER, NOW.minus(Duration.ofHours(24)));
        assertError(request(null, "01055218842"), ErrorCode.SHARE_004);

        givenAnalysis(DAUGHTER, VoiceAnalysis.FinalDecision.DANGER,
                NOW.minus(Duration.ofHours(24)).plusSeconds(1));
        service.share(DAUGHTER, request(null, "01055218842"));
        verify(dangerShareRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("공간에 본인 혼자면 422")
    void alone() {
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(List.of(member(DAUGHTER)));

        assertError(request(null, "01055218842"), ErrorCode.SHARE_004);
    }

    // ── 요청 객체 ───────────────────────────────────────────

    @Test
    @DisplayName("요청 객체를 로그에 찍어도 번호가 나오지 않는다")
    void requestToStringHidesPhone() {
        assertThat(request(null, "010-5521-8842").toString()).doesNotContain("8842");
    }

    // ── 도우미 ──────────────────────────────────────────────

    private DangerShareDto.CreateRequest request(Long impersonatedUserId, String phoneNumber) {
        return new DangerShareDto.CreateRequest(ANALYSIS_ID, impersonatedUserId, phoneNumber);
    }

    private FamilyMembership member(Long userId) {
        return FamilyMembership.join(FAMILY_ID, userId, NOW, NOW.minus(Duration.ofDays(30)));
    }

    private void givenAnalysis(Long ownerId, VoiceAnalysis.FinalDecision decision, Instant submittedAt) {
        VoiceAnalysis analysis = VoiceAnalysis.submit(
                ownerId, 88L, 77L, 80.0, decision, "ecapa-tdnn-v1", submittedAt, submittedAt);
        ReflectionTestUtils.setField(analysis, "id", ANALYSIS_ID);
        given(voiceAnalysisRepository.findById(ANALYSIS_ID)).willReturn(Optional.of(analysis));
    }

    private DangerShareEvent capturedEvent() {
        ArgumentCaptor<DangerShareEvent> captor = ArgumentCaptor.forClass(DangerShareEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private void assertError(DangerShareDto.CreateRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> service.share(DAUGHTER, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}