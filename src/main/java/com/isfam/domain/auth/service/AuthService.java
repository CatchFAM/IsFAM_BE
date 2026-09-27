package com.isfam.domain.auth.service;

import com.isfam.domain.auth.dto.AuthDto;
import com.isfam.domain.auth.entity.RefreshToken;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.entity.UserAgreement;
import com.isfam.domain.auth.repository.RefreshTokenRepository;
import com.isfam.domain.auth.repository.UserAgreementRepository;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.security.JwtTokenProvider;
import com.isfam.common.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 계정 · 세션 관리.
 *
 * <p><b>토큰 발급 순서가 강제됩니다</b>({@link #issueTokens}).
 * access_token 의 sid 클레임이 refresh_tokens.id 라서, refresh 를 먼저 저장해 id 를 얻어야
 * access 를 만들 수 있습니다. 이 순서 덕분에 로그아웃이 세션 단위로 동작합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 동의 이력에 함께 남기는 약관 버전. 문안이 바뀌면 올리세요 */
    private static final String AGREEMENT_VERSION = "v1.0";

    private final UserRepository userRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PhoneVerificationService phoneVerificationService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    // ── 회원가입 ────────────────────────────────────────────

    /**
     * 가입. phone_verification_token 을 여기서 소비합니다(1회용).
     *
     * <p>실패해서 재시도하면 번호 인증부터 다시 해야 합니다 — 토큰이 이미 폐기됐기 때문입니다.
     *
     * <p><b>필수 동의 검사가 토큰 소비보다 먼저입니다.</b> 동의가 빠진 요청으로 토큰을
     * 태워버리면, 사용자는 체크박스 하나 때문에 인증번호를 다시 받아야 합니다.
     */
    @Transactional
    public AuthDto.TokenResponse signup(AuthDto.SignupRequest request) {
        Instant now = clock.instant();

        // 필수 동의는 검증 어노테이션이 아니라 여기서 봅니다 — 명세가 AUTH_007 을 요구
        // 필수 3종: 이용약관 · 개인정보 처리방침(P-06) · 음성 민감정보 처리
        if (!Boolean.TRUE.equals(request.termsAgreed())
                || !Boolean.TRUE.equals(request.privacyAgreed())
                || !Boolean.TRUE.equals(request.voicePrintAgreed())) {
            throw new BusinessException(ErrorCode.AUTH_007);
        }

        if (userRepository.existsByPhoneNumberAndDeletedAtIsNull(request.phoneNumber())) {
            throw new BusinessException(ErrorCode.AUTH_003);
        }

        // 토큰이 이 번호에 대해 발급된 게 맞는지 확인 후 소비
        phoneVerificationService.consume(request.phoneVerificationToken(), request.phoneNumber());

        User user = userRepository.save(User.create(
                request.phoneNumber(),
                passwordEncoder.encode(request.password()),
                request.userName(),
                request.displayName()));

        saveAgreements(user.getId(), request, now);

        AuthDto.TokenResponse tokens = issueTokens(user.getId());
        return AuthDto.TokenResponse.of(user.getId(), user.getDisplayName(),
                tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 동의 이력 저장. 시각과 약관 버전을 함께 남깁니다(legal-compliance.md 8장).
     *
     * <p>가입 1건 = 4행입니다. 필수 3종은 위에서 true 가 확인됐으므로 항상 agreed=true 이고,
     * 마케팅(선택)만 false 로 남을 수 있습니다. <b>거부한 선택 동의도 행을 남깁니다</b> —
     * "묻지 않았다"와 "물었는데 거부했다"를 구분해야 하기 때문입니다(L5 항목별 동의).
     */
    private void saveAgreements(Long userId, AuthDto.SignupRequest request, Instant now) {
        userAgreementRepository.saveAll(List.of(
                UserAgreement.of(userId, UserAgreement.Type.TERMS,
                        request.termsAgreed(), AGREEMENT_VERSION, now),
                UserAgreement.of(userId, UserAgreement.Type.PRIVACY,
                        request.privacyAgreed(), AGREEMENT_VERSION, now),
                UserAgreement.of(userId, UserAgreement.Type.VOICE_PRINT,
                        request.voicePrintAgreed(), AGREEMENT_VERSION, now),
                UserAgreement.of(userId, UserAgreement.Type.MARKETING,
                        Boolean.TRUE.equals(request.marketingAgreed()), AGREEMENT_VERSION, now)));
    }

    // ── 로그인 ──────────────────────────────────────────────

    /**
     * 로그인.
     *
     * <p>미가입(AUTH_001)과 비밀번호 불일치(AUTH_008)를 구분해 내려줍니다.
     * 계정 존재 여부가 노출되지만, 명세가 "가입되지 않은 번호예요 → 회원가입 하시겠어요?" 분기를
     * 요구하고 있어 그대로 따랐습니다.
     */
    @Transactional
    public AuthDto.TokenResponse login(AuthDto.LoginRequest request) {
        Instant now = clock.instant();

        User user = userRepository.findByPhoneNumberAndDeletedAtIsNull(request.phoneNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_001));

        if (user.isLocked(now)) {
            throw new BusinessException(ErrorCode.AUTH_008,
                    "로그인 시도가 많아 잠시 잠겼어요. 10분 후에 다시 시도해 주세요");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordLoginFailure(now);
            throw new BusinessException(ErrorCode.AUTH_008);
        }

        user.recordLoginSuccess();

        AuthDto.TokenResponse tokens = issueTokens(user.getId());
        return AuthDto.TokenResponse.of(user.getId(), user.getDisplayName(),
                tokens.accessToken(), tokens.refreshToken());
    }

    // ── 토큰 재발급 ─────────────────────────────────────────

    /**
     * refresh 로테이션. 쓴 토큰은 즉시 revoke 하고 새 쌍을 발급합니다.
     * 탈취된 토큰을 재사용하면 이미 revoke 되어 AUTH_002 가 나갑니다.
     */
    @Transactional
    public AuthDto.TokenResponse refresh(String refreshToken) {
        Instant now = clock.instant();

        JwtTokenProvider.RefreshTokenPayload payload = jwtTokenProvider.parseRefreshToken(refreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(HashUtil.sha256(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_002));

        if (!stored.isUsable(now) || !stored.getUserId().equals(payload.userId())) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }

        stored.revoke(now);

        AuthDto.TokenResponse tokens = issueTokens(payload.userId());
        return AuthDto.TokenResponse.reissued(tokens.accessToken(), tokens.refreshToken());
    }

    // ── 로그아웃 ────────────────────────────────────────────

    /**
     * 현재 세션만 종료. 다른 기기 세션은 유지됩니다.
     *
     * <p>access_token 의 sid 로 끊는 게 기본이고, 본문에 refresh_token 이 오면 그걸 우선합니다.
     * 이미 만료된 access 로도 로그아웃할 수 있어야 하기 때문입니다.
     */
    @Transactional
    public void logout(Long userId, Long sessionId, String refreshToken) {
        Instant now = clock.instant();

        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepository.findByTokenHash(HashUtil.sha256(refreshToken))
                    .filter(token -> token.getUserId().equals(userId))
                    .ifPresent(token -> token.revoke(now));
            return;
        }

        if (sessionId != null) {
            refreshTokenRepository.findById(sessionId)
                    .filter(token -> token.getUserId().equals(userId))
                    .ifPresent(token -> token.revoke(now));
        }
    }

    // ── 비밀번호 재설정 ─────────────────────────────────────

    /**
     * 비밀번호 잊었을 때. access_token 없이 phone_verification_token 으로 본인 확인합니다.
     * 성공하면 <b>모든 세션을 끊습니다</b> — 탈취 상태였다면 여기서 정리돼야 합니다.
     */
    @Transactional
    public void resetPassword(AuthDto.PasswordResetRequest request) {
        Instant now = clock.instant();

        User user = userRepository.findByPhoneNumberAndDeletedAtIsNull(request.phoneNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_001));

        phoneVerificationService.consume(request.phoneVerificationToken(), request.phoneNumber());

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllByUserId(user.getId(), now);
    }

    // ── 내 정보 ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthDto.MeResponse getMe(Long userId) {
        User user = findActiveUser(userId);
        return new AuthDto.MeResponse(
                user.getId(),
                user.getDisplayName(),
                maskPhoneNumber(user.getPhoneNumber()),
                user.getCreatedAt());
    }

    /**
     * 닉네임 수정.
     *
     * <p><b>saveAndFlush 가 필요합니다.</b> {@code @LastModifiedDate} 는 영속성 컨텍스트가
     * flush 될 때 찍히므로, 그냥 반환하면 응답의 updated_at 이 <i>변경 전</i> 값입니다.
     * 눈에 잘 안 띄는데, 앱이 이 값을 캐시 무효화 기준으로 쓰면 갱신이 한 박자 밀립니다.
     *
     * <p>여기서 밀린 users.updated_at 이 Phase 7 의 GET /family/embeddings 에서
     * 멤버별 updated_at 계산에 들어갑니다 — 그래서 같은 공간 멤버 폰이 다음 동기화에서
     * 새 닉네임을 받습니다. 별도 푸시는 쏘지 않습니다(service-flow.md 9장).
     */
    @Transactional
    public AuthDto.DisplayNameResponse updateDisplayName(Long userId, String displayName) {
        User user = findActiveUser(userId);
        user.changeDisplayName(displayName);
        userRepository.saveAndFlush(user);

        return new AuthDto.DisplayNameResponse(
                user.getId(), user.getDisplayName(), user.getUpdatedAt());
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 토큰 한 쌍 발급.
     *
     * <p>순서를 바꾸면 안 됩니다 — refresh 를 저장해 얻은 id 가 access 의 sid 가 됩니다.
     */
    private AuthDto.TokenResponse issueTokens(Long userId) {
        JwtTokenProvider.IssuedToken refresh = jwtTokenProvider.issueRefreshToken(userId);

        RefreshToken saved = refreshTokenRepository.save(RefreshToken.issue(
                userId, HashUtil.sha256(refresh.value()), refresh.issuedAt(), refresh.expiresAt()));

        JwtTokenProvider.IssuedToken access =
                jwtTokenProvider.issueAccessToken(userId, saved.getId());

        return AuthDto.TokenResponse.reissued(access.value(), refresh.value());
    }

    private User findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_009));
    }

    /** 01012345678 → 010****5678 */
    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 7) {
            return phoneNumber;
        }
        return phoneNumber.substring(0, 3) + "****" + phoneNumber.substring(phoneNumber.length() - 4);
    }
}