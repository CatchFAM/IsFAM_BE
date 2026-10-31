package com.isfam.domain.voice.service;

import com.isfam.common.crypto.EmbeddingCipher;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import com.isfam.domain.audit.service.DataDeletionLogger;
import com.isfam.domain.audit.service.VoiceprintAccessLogger;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.voice.entity.Voiceprint;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import com.isfam.domain.voice.repository.VoiceprintSampleRepository;
import com.isfam.infra.push.event.FamilySyncEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 14 P-18 — {@code DELETE /me/voiceprint}.
 *
 * <p>완료 기준: <b>임베딩 삭제 + 가족 폰 회수</b>. 회수는 {@code GET /family/embeddings} 가
 * 음성 없는 멤버를 removed_user_ids 로 내려보내는 기존 동작에 맡기고, 여기서는
 * 삭제 · 기록 2종 · 동기화 푸시 대상까지 확인합니다.
 *
 * <p>알림 설정 세분화 2단계에서 {@code NotificationService} 의존이 빠졌습니다 —
 * 이 서비스는 등록·삭제 어느 쪽에서도 알림함을 만들지 않습니다.
 */
@ExtendWith(MockitoExtension.class)
class VoiceprintServiceDeleteTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Long USER_ID = 7L;
    private static final Long FAMILY_ID = 1L;
    private static final String IP = "127.0.0.1";
    private static final String MODEL = "ecapa-tdnn-v1";

    @Mock VoiceprintRepository voiceprintRepository;
    @Mock VoiceprintSampleRepository sampleRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock VoiceprintAccessLogger accessLogger;
    @Mock DataDeletionLogger dataDeletionLogger;
    @Mock EmbeddingCipher embeddingCipher;
    @Mock ModelVersionPolicy modelVersionPolicy;
    @Mock ApplicationEventPublisher eventPublisher;

    private VoiceprintService service;

    @BeforeEach
    void setUp() {
        service = new VoiceprintService(voiceprintRepository, sampleRepository, membershipRepository,
                accessLogger, dataDeletionLogger, embeddingCipher, modelVersionPolicy, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesVoiceprintAndSamplesThenRecordsBoth() {
        givenRegistered(3);
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.empty());

        service.delete(USER_ID, IP);

        // 모델 버전은 지우기 전에 읽어야 기록에 남길 수 있습니다
        InOrder order = inOrder(voiceprintRepository, sampleRepository, accessLogger);
        order.verify(voiceprintRepository).findByUserId(USER_ID);
        order.verify(voiceprintRepository).deleteAllByUserId(USER_ID);
        order.verify(sampleRepository).deleteAllByUserId(USER_ID);
        order.verify(accessLogger).recordDestroy(USER_ID, USER_ID,
                VoiceprintAccessLog.Reason.USER_REQUEST, 3, 1, MODEL, IP, NOW);

        verify(dataDeletionLogger).recordUserRequest(
                USER_ID, DataDeletionLogger.Tables.VOICEPRINTS, 1, NOW, NOW);
        verify(dataDeletionLogger).recordUserRequest(
                USER_ID, DataDeletionLogger.Tables.VOICEPRINT_SAMPLES, 3, NOW, NOW);
    }

    /** 가족 폰이 GET /family/embeddings 를 다시 부르도록 — 본인은 제외 */
    @Test
    void wakesFamilyPhonesExceptSelf() {
        givenRegistered(3);
        givenFamilyOf(USER_ID, 8L, 9L);

        service.delete(USER_ID, IP);

        ArgumentCaptor<FamilySyncEvent> event = ArgumentCaptor.forClass(FamilySyncEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().targetUserIds()).containsExactlyInAnyOrder(8L, 9L);
        assertThat(event.getValue().reason()).isEqualTo(FamilySyncEvent.Reason.VOICEPRINT_DELETED);
        assertThat(event.getValue().familyId()).isEqualTo(FAMILY_ID);
    }

    /** 혼자 있는 공간이면 보낼 폰이 없습니다 */
    @Test
    void noPushWhenAloneInFamily() {
        givenRegistered(3);
        givenFamilyOf(USER_ID);

        service.delete(USER_ID, IP);

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * 등록한 적 없어도 성공(멱등). 파기 기록은 0건으로 남기고,
     * 생체정보 파기 기록·푸시는 남기지 않습니다 — 없던 것을 있었던 것처럼 보이지 않게.
     */
    @Test
    void notRegisteredIsIdempotentAndRecordsZero() {
        given(voiceprintRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(voiceprintRepository.deleteAllByUserId(USER_ID)).willReturn(0);
        given(sampleRepository.deleteAllByUserId(USER_ID)).willReturn(0);
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(mock(FamilyMembership.class)));

        service.delete(USER_ID, IP);

        // 0건이면 로거가 스스로 건너뜁니다 — 호출 자체는 0 으로 갑니다
        verify(accessLogger).recordDestroy(USER_ID, USER_ID,
                VoiceprintAccessLog.Reason.USER_REQUEST, 0, 0, null, IP, NOW);
        verify(dataDeletionLogger).recordUserRequest(
                eq(USER_ID), eq(DataDeletionLogger.Tables.VOICEPRINTS), eq(0), any(), any());
        verify(dataDeletionLogger).recordUserRequest(
                eq(USER_ID), eq(DataDeletionLogger.Tables.VOICEPRINT_SAMPLES), eq(0), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(membershipRepository, never()).findByFamilyIdAndStatusOrderByJoinedAtAsc(any(), any());
    }

    /** 멤버십·음성 공유 동의는 건드리지 않습니다 — 다시 등록하면 바로 공유되게 */
    @Test
    void doesNotTouchMembershipOrConsent() {
        givenRegistered(3);
        FamilyMembership membership = FamilyMembership.join(FAMILY_ID, USER_ID, NOW, NOW);
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(membership));
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE)).willReturn(List.of(membership));

        service.delete(USER_ID, IP);

        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getVoiceSharingConsentedAt()).isEqualTo(NOW);
        verify(membershipRepository, never()).save(any());
        verify(accessLogger, never()).recordRegister(any(), any(), anyInt(), any(), any(), any());
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenRegistered(int samples) {
        Voiceprint voiceprint = mock(Voiceprint.class);
        given(voiceprint.getEmbeddingModelVersion()).willReturn(MODEL);
        given(voiceprintRepository.findByUserId(USER_ID)).willReturn(Optional.of(voiceprint));
        given(voiceprintRepository.deleteAllByUserId(USER_ID)).willReturn(1);
        given(sampleRepository.deleteAllByUserId(USER_ID)).willReturn(samples);
    }

    private void givenFamilyOf(Long... memberIds) {
        FamilyMembership mine = mock(FamilyMembership.class);
        given(mine.getFamilyId()).willReturn(FAMILY_ID);
        given(membershipRepository.findByUserIdAndStatus(USER_ID, FamilyMembership.Status.ACTIVE))
                .willReturn(Optional.of(mine));

        List<FamilyMembership> members = java.util.Arrays.stream(memberIds)
                .map(id -> {
                    FamilyMembership member = mock(FamilyMembership.class);
                    given(member.getUserId()).willReturn(id);
                    return member;
                })
                .toList();
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(
                FAMILY_ID, FamilyMembership.Status.ACTIVE)).willReturn(members);
    }
}