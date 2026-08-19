package com.isfam.domain.auth.service;

import com.isfam.domain.auth.dto.PhoneVerificationDto;
import com.isfam.domain.auth.entity.PhoneVerification;
import com.isfam.domain.auth.repository.PhoneVerificationRepository;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.security.JwtTokenProvider;
import com.isfam.common.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 휴대폰 번호 인증 — 발송 · 확인 · 소비.
 *
 * <p><b>데모 정책</b> — 실제 SMS 를 보내지 않고 인증번호를 {@code 123456} 으로 고정합니다.
 * 실발송으로 바꿀 때 손댈 곳은 {@link #generateCode()} 하나입니다.
 * 코드는 그때도 평문으로 저장하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    /** 데모 고정 코드. 실발송 전환 시 난수 6자리로 교체 */
    private static final String DEMO_CODE = "123456";

    private static final Duration CODE_TTL = Duration.ofMinutes(3);
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    private static final Duration SEND_LIMIT_WINDOW = Duration.ofHours(24);
    private static final long SEND_LIMIT_COUNT = 5;

    private final PhoneVerificationRepository phoneVerificationRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    /**
     * 인증번호 발송.
     * 제한을 안 걸면 SMS 비용이 그대로 나가므로 재발송 간격과 횟수를 먼저 봅니다.
     */
    @Transactional
    public PhoneVerificationDto.SendResponse send(String phoneNumber) {
        Instant now = clock.instant();

        phoneVerificationRepository.findTopByPhoneNumberOrderByCreatedAtDesc(phoneNumber)
                .filter(latest -> latest.getCreatedAt().plus(RESEND_INTERVAL).isAfter(now))
                .ifPresent(latest -> {
                    throw new BusinessException(ErrorCode.AUTH_005, "잠시 후에 다시 시도해 주세요");
                });

        long sentCount = phoneVerificationRepository
                .countByPhoneNumberAndCreatedAtAfter(phoneNumber, now.minus(SEND_LIMIT_WINDOW));
        if (sentCount >= SEND_LIMIT_COUNT) {
            throw new BusinessException(ErrorCode.AUTH_005);
        }

        String verificationId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(CODE_TTL);

        phoneVerificationRepository.save(PhoneVerification.issue(
                verificationId, phoneNumber, HashUtil.sha256(generateCode()), expiresAt));

        log.debug("인증번호 발송 verification_id={}", verificationId);
        return new PhoneVerificationDto.SendResponse(verificationId, expiresAt);
    }

    /**
     * 인증번호 확인 → phone_verification_token 발급.
     *
     * <p>만료·시도초과는 410 으로 내려 앱이 "처음부터 다시" 분기를 타게 합니다.
     * 단순 불일치(422)와 구분돼야 합니다.
     */
    @Transactional
    public PhoneVerificationDto.VerifyResponse verify(String verificationId, String code) {
        Instant now = clock.instant();

        PhoneVerification verification = phoneVerificationRepository.findByVerificationId(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_006));

        if (verification.isExpired(now) || verification.isAttemptExceeded()) {
            throw new BusinessException(ErrorCode.AUTH_006, HttpStatus.GONE);
        }

        if (!HashUtil.matches(code, verification.getCodeHash())) {
            verification.recordFailedAttempt();
            throw new BusinessException(ErrorCode.AUTH_004);
        }

        JwtTokenProvider.IssuedToken token =
                jwtTokenProvider.issuePhoneVerificationToken(verificationId, verification.getPhoneNumber());
        verification.markVerified(now, HashUtil.sha256(token.value()), token.expiresAt());

        return new PhoneVerificationDto.VerifyResponse(token.value(), token.expiresAt());
    }

    /**
     * phone_verification_token 소비. signup / 비밀번호 재설정이 호출합니다.
     *
     * <p>JWT 검증만으로는 재사용을 막지 못합니다 — 서명이 유효한 같은 토큰을 두 번 보낼 수 있으므로
     * DB 의 consumed_at 을 함께 봐야 1회용이 성립합니다.
     *
     * @param phoneNumber 요청 본문의 번호. 토큰에 실린 번호와 다르면 거부합니다
     */
    @Transactional
    public void consume(String token, String phoneNumber) {
        Instant now = clock.instant();

        JwtTokenProvider.PhoneVerificationPayload payload =
                jwtTokenProvider.parsePhoneVerificationToken(token);

        if (!phoneNumber.equals(payload.phoneNumber())) {
            throw new BusinessException(ErrorCode.AUTH_006);
        }

        PhoneVerification verification = phoneVerificationRepository
                .findByVerificationId(payload.verificationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_006));

        if (!verification.isVerified() || verification.isConsumed()
                || !HashUtil.matches(token, verification.getTokenHash())) {
            throw new BusinessException(ErrorCode.AUTH_006);
        }
        if (verification.isTokenExpired(now)) {
            throw new BusinessException(ErrorCode.AUTH_006, HttpStatus.GONE);
        }

        verification.markConsumed(now);
    }

    /** 데모에서는 고정값. 실발송 전환 시 여기만 난수로 바꾸면 됩니다 */
    private String generateCode() {
        return DEMO_CODE;
    }
}