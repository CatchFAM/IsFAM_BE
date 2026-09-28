package com.isfam.domain.auth.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.security.JwtTokenProvider;
import com.isfam.domain.auth.dto.AuthDto;
import com.isfam.domain.auth.entity.RefreshToken;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.RefreshTokenRepository;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 14 가입 게이트.
 * <ul>
 *   <li>P-06 — 개인정보 처리방침 동의(privacy_agreed) 저장. 누락·거부 → AUTH_007</li>
 *   <li>P-08 — 만 14세 미만 차단(age_over_14). 누락·false → AUTH_010, 동의 검사보다 먼저</li>
 * </ul>
 * 완료 기준: <b>가입 1건 → user_agreements 5건</b>.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceSignupTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Long USER_ID = 10L;
    private static final Long SESSION_ID = 20L;

    @Mock UserRepository userRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PhoneVerificationService phoneVerificationService;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock PasswordEncoder passwordEncoder;

    @Captor ArgumentCaptor<List<UserAgreement>> agreementsCaptor;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, userAgreementRepository, refreshTokenRepository,
                phoneVerificationService, jwtTokenProvider, passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 저장 ───────────────────────────────────────────────

    @Test
    void signupRecordsFiveAgreementsIncludingPrivacyAndAge() {
        givenSignupSucceeds();

        service.signup(request(true, true, true, true, false));

        verify(userAgreementRepository).saveAll(agreementsCaptor.capture());
        List<UserAgreement> saved = agreementsCaptor.getValue();

        assertThat(saved).hasSize(5);
        assertThat(saved)
                .extracting(UserAgreement::getAgreementType, UserAgreement::isAgreed)
                .containsExactly(
                        tuple(UserAgreement.Type.AGE_OVER_14, true),
                        tuple(UserAgreement.Type.TERMS, true),
                        tuple(UserAgreement.Type.PRIVACY, true),
                        tuple(UserAgreement.Type.VOICE_PRINT, true),
                        // 거부한 선택 동의도 행을 남깁니다 — "묻지 않았다"와 구분
                        tuple(UserAgreement.Type.MARKETING, false));
        assertThat(saved)
                .allSatisfy(agreement -> {
                    assertThat(agreement.getUserId()).isEqualTo(USER_ID);
                    assertThat(agreement.getAgreedAt()).isEqualTo(NOW);
                    assertThat(agreement.getAgreementVersion()).isNotBlank();
                });
    }

    // ── 14세 미만 (P-08) ────────────────────────────────────

    @Test
    void ageOver14FalseIsRejectedWithAuth010() {
        assertRejectedBeforeAnything(request(false, true, true, true, true), ErrorCode.AUTH_010);
    }

    /** 필드를 모르는 구버전 앱 — 확인받지 못했으니 14세 미만과 같게 다룹니다 */
    @Test
    void ageOver14MissingIsRejectedWithAuth010() {
        assertRejectedBeforeAnything(request(null, true, true, true, true), ErrorCode.AUTH_010);
    }

    /** 둘 다 걸리면 나이가 먼저 — 앱이 "약관 동의"가 아니라 "가입 불가"를 보여줘야 합니다 */
    @Test
    void ageIsCheckedBeforeConsents() {
        assertRejectedBeforeAnything(request(false, false, false, false, false), ErrorCode.AUTH_010);
    }

    // ── 필수 동의 (P-06) ────────────────────────────────────

    @Test
    void privacyFalseIsRejectedWithAuth007() {
        assertRejectedBeforeAnything(request(true, true, false, true, true), ErrorCode.AUTH_007);
    }

    /** 필드를 모르는 구버전 앱 — COMMON_002 가 아니라 AUTH_007 이어야 합니다 */
    @Test
    void privacyMissingIsRejectedWithAuth007() {
        assertRejectedBeforeAnything(request(true, true, null, true, true), ErrorCode.AUTH_007);
    }

    @Test
    void termsFalseIsStillRejected() {
        assertRejectedBeforeAnything(request(true, false, true, true, true), ErrorCode.AUTH_007);
    }

    @Test
    void voicePrintFalseIsStillRejected() {
        assertRejectedBeforeAnything(request(true, true, true, false, true), ErrorCode.AUTH_007);
    }

    // ── 내부 ──────────────────────────────────────────────

    /**
     * 나이·동의 검사는 번호 인증 토큰 소비보다 먼저입니다 — 체크박스 하나 때문에
     * 인증번호를 다시 받게 만들지 않습니다. 계정·동의 이력도 남지 않아야 합니다.
     */
    private void assertRejectedBeforeAnything(AuthDto.SignupRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);

        verifyNoInteractions(phoneVerificationService, userAgreementRepository, refreshTokenRepository);
        verify(userRepository, never()).save(any());
    }

    private void givenSignupSucceeds() {
        User user = mock(User.class);
        given(user.getId()).willReturn(USER_ID);
        given(user.getDisplayName()).willReturn("홍길동");
        given(passwordEncoder.encode(anyString())).willReturn("hashed");
        given(userRepository.save(any(User.class))).willReturn(user);

        RefreshToken refreshToken = mock(RefreshToken.class);
        given(refreshToken.getId()).willReturn(SESSION_ID);
        given(refreshTokenRepository.save(any(RefreshToken.class))).willReturn(refreshToken);

        given(jwtTokenProvider.issueRefreshToken(USER_ID)).willReturn(
                new JwtTokenProvider.IssuedToken("refresh", "jti-r", NOW, NOW.plusSeconds(60)));
        given(jwtTokenProvider.issueAccessToken(USER_ID, SESSION_ID)).willReturn(
                new JwtTokenProvider.IssuedToken("access", "jti-a", NOW, NOW.plusSeconds(60)));
    }

    private AuthDto.SignupRequest request(Boolean ageOver14, Boolean terms, Boolean privacy,
                                          Boolean voicePrint, Boolean marketing) {
        return new AuthDto.SignupRequest(
                "01012345678", "verification-token", "password1",
                "홍길동", "길동", ageOver14, terms, privacy, voicePrint, marketing);
    }
}