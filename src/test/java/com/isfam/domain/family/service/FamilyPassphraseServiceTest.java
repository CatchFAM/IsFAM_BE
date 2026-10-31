package com.isfam.domain.family.service;

import com.isfam.common.crypto.PassphraseCipher;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.family.dto.FamilyPassphraseDto;
import com.isfam.domain.family.entity.Family;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.entity.FamilyPassphrase;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.family.repository.FamilyPassphraseRepository;
import com.isfam.domain.family.repository.FamilyRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 우리 집 암호 — 권한 · 암호화 저장 · 알림 대상과 문구.
 *
 * <p>고정하고 싶은 것:
 * <ul>
 *   <li>정답이 평문으로 DB 에 가지 않는다</li>
 *   <li>알림은 owner 를 뺀 멤버에게만, 문구에 질문·정답이 없다 (FCM · 잠금화면)</li>
 *   <li>같은 값으로 다시 저장하면 알림이 가지 않는다</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FamilyPassphraseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Long FAMILY_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long MEMBER_A = 11L;
    private static final Long MEMBER_B = 12L;

    private static final String QUESTION = "우리 집 첫 강아지 이름은?";
    private static final String ANSWER = "보리";

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    @Mock FamilyPassphraseRepository passphraseRepository;
    @Mock FamilyMembershipRepository membershipRepository;
    @Mock FamilyRepository familyRepository;
    @Mock NotificationService notificationService;

    private PassphraseCipher cipher;
    private FamilyPassphraseService service;

    @BeforeEach
    void setUp() {
        cipher = new PassphraseCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new FamilyPassphraseService(passphraseRepository, membershipRepository,
                familyRepository, notificationService, cipher);
    }

    // ── 등록 ──────────────────────────────────────────────

    @Test
    @DisplayName("첫 등록 — 정답은 암호문으로 저장되고, owner 를 뺀 멤버에게 '정해졌어요' 알림")
    void firstSave() {
        givenMe(OWNER_ID, true);
        givenAlive();
        given(passphraseRepository.findByFamilyId(FAMILY_ID)).willReturn(Optional.empty());
        givenSaveReturnsArgument();
        givenActiveMembers();

        FamilyPassphraseDto.PassphraseResponse response = service.save(OWNER_ID, QUESTION, ANSWER);

        assertThat(response.question()).isEqualTo(QUESTION);
        assertThat(response.answer()).isEqualTo(ANSWER);

        FamilyPassphrase saved = capturedSave();
        assertThat(saved.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(saved.getAnswerEncrypted())
                .isNotEqualTo(ANSWER.getBytes(StandardCharsets.UTF_8));
        assertThat(cipher.decrypt(saved.getAnswerEncrypted(), FAMILY_ID)).isEqualTo(ANSWER);

        List<Notification> sent = capturedNotifications(2);
        assertThat(sent).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(MEMBER_A, MEMBER_B);
        assertThat(sent).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(Notification.Type.PASSPHRASE_UPDATED);
            assertThat(notification.getTitle()).contains("정해졌어요");
            assertNoSecretIn(notification);
        });
    }

    // ── 수정 ──────────────────────────────────────────────

    @Test
    @DisplayName("변경 — 같은 row 를 고치고 '바뀌었어요' 알림")
    void change() {
        givenMe(OWNER_ID, true);
        givenAlive();
        FamilyPassphrase existing = existingRow(QUESTION, ANSWER);
        given(passphraseRepository.findByFamilyId(FAMILY_ID)).willReturn(Optional.of(existing));
        givenSaveReturnsArgument();
        givenActiveMembers();

        service.save(OWNER_ID, "아빠 고향은?", "전주");

        FamilyPassphrase saved = capturedSave();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getQuestion()).isEqualTo("아빠 고향은?");
        assertThat(cipher.decrypt(saved.getAnswerEncrypted(), FAMILY_ID)).isEqualTo("전주");

        assertThat(capturedNotifications(2)).allSatisfy(notification -> {
            assertThat(notification.getTitle()).contains("바뀌었어요");
            assertThat(notification.getTitle() + notification.getBody())
                    .doesNotContain("아빠 고향은?").doesNotContain("전주");
        });
    }

    @Test
    @DisplayName("값이 그대로면(앞뒤 공백만 다르면) 저장도 알림도 하지 않는다")
    void unchangedDoesNothing() {
        givenMe(OWNER_ID, true);
        givenAlive();
        given(passphraseRepository.findByFamilyId(FAMILY_ID))
                .willReturn(Optional.of(existingRow(QUESTION, ANSWER)));

        FamilyPassphraseDto.PassphraseResponse response =
                service.save(OWNER_ID, "  " + QUESTION + " ", " " + ANSWER + "  ");

        assertThat(response.answer()).isEqualTo(ANSWER);
        verify(passphraseRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationService);
    }

    // ── 권한 ──────────────────────────────────────────────

    @Test
    @DisplayName("member 는 등록·수정할 수 없다 — FAMILY_003")
    void memberCannotSave() {
        givenMe(MEMBER_A, false);

        assertThatThrownBy(() -> service.save(MEMBER_A, QUESTION, ANSWER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FAMILY_003);

        verifyNoInteractions(passphraseRepository, notificationService);
    }

    @Test
    @DisplayName("member 는 삭제할 수 없다 — FAMILY_003")
    void memberCannotDelete() {
        givenMe(MEMBER_A, false);

        assertThatThrownBy(() -> service.delete(MEMBER_A))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FAMILY_003);

        verifyNoInteractions(passphraseRepository);
    }

    // ── 조회 ──────────────────────────────────────────────

    @Test
    @DisplayName("member 도 조회할 수 있고 정답은 복호화되어 나간다")
    void memberCanRead() {
        givenMe(MEMBER_A, false);
        givenAlive();
        given(passphraseRepository.findByFamilyId(FAMILY_ID))
                .willReturn(Optional.of(existingRow(QUESTION, ANSWER)));

        FamilyPassphraseDto.PassphraseResponse response = service.get(MEMBER_A);

        assertThat(response.question()).isEqualTo(QUESTION);
        assertThat(response.answer()).isEqualTo(ANSWER);
        assertThat(response.toString()).doesNotContain(ANSWER);
    }

    @Test
    @DisplayName("미설정이면 404 FAMILY_008")
    void notSet() {
        givenMe(MEMBER_A, false);
        givenAlive();
        given(passphraseRepository.findByFamilyId(FAMILY_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MEMBER_A))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FAMILY_008);
    }

    // ── 삭제 ──────────────────────────────────────────────

    @Test
    @DisplayName("삭제 — 알림을 보내지 않는다. 없어도 예외 없음")
    void deleteSendsNothing() {
        givenMe(OWNER_ID, true);
        givenAlive();
        given(passphraseRepository.findByFamilyId(FAMILY_ID)).willReturn(Optional.empty());

        service.delete(OWNER_ID);

        verify(passphraseRepository, never()).delete(any());
        verifyNoInteractions(notificationService);
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenMe(Long userId, boolean owner) {
        FamilyMembership membership = owner
                ? FamilyMembership.createOwner(FAMILY_ID, userId, NOW, NOW)
                : FamilyMembership.join(FAMILY_ID, userId, NOW, NOW);
        given(membershipRepository.findByUserIdAndStatus(userId, ACTIVE))
                .willReturn(Optional.of(membership));
    }

    private void givenAlive() {
        given(familyRepository.findByIdAndDeletedAtIsNull(FAMILY_ID))
                .willReturn(Optional.of(mock(Family.class)));
    }

    private void givenActiveMembers() {
        given(membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(FAMILY_ID, ACTIVE))
                .willReturn(List.of(
                        FamilyMembership.createOwner(FAMILY_ID, OWNER_ID, NOW, NOW),
                        FamilyMembership.join(FAMILY_ID, MEMBER_A, NOW, NOW),
                        FamilyMembership.join(FAMILY_ID, MEMBER_B, NOW, NOW)));
    }

    private void givenSaveReturnsArgument() {
        given(passphraseRepository.saveAndFlush(any(FamilyPassphrase.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private FamilyPassphrase existingRow(String question, String answer) {
        return FamilyPassphrase.create(FAMILY_ID, question, cipher.encrypt(answer, FAMILY_ID));
    }

    private FamilyPassphrase capturedSave() {
        ArgumentCaptor<FamilyPassphrase> captor = ArgumentCaptor.forClass(FamilyPassphrase.class);
        verify(passphraseRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private List<Notification> capturedNotifications(int expected) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService, times(expected)).create(captor.capture());
        return captor.getAllValues();
    }

    private void assertNoSecretIn(Notification notification) {
        String text = notification.getTitle() + notification.getBody();
        assertThat(text).doesNotContain(QUESTION).doesNotContain(ANSWER);
    }
}