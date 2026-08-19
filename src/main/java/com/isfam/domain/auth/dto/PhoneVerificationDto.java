package com.isfam.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * POST /auth/phone/send · verify 의 요청·응답.
 *
 * <p>엔드포인트마다 파일을 만들면 /auth 한 도메인에 DTO 파일만 열 몇 개가 되어
 * 관련된 것끼리 한 파일에 중첩했습니다. 회원가입·로그인은 AuthDto 로 따로 묶습니다.
 */
public final class PhoneVerificationDto {

    private PhoneVerificationDto() {
    }

    public record SendRequest(
            @NotBlank(message = "휴대폰 번호를 입력해 주세요")
            @Pattern(regexp = "^01[0-9]{8,9}$", message = "휴대폰 번호 형식이 올바르지 않아요")
            String phoneNumber
    ) {
    }

    /** expiresAt 은 발송 후 3분 */
    public record SendResponse(String verificationId, Instant expiresAt) {
    }

    public record VerifyRequest(
            @NotBlank(message = "인증 요청 정보가 없어요")
            String verificationId,

            @NotBlank(message = "인증번호를 입력해 주세요")
            @Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 6자리 숫자예요")
            String verificationCode
    ) {
    }

    /** phoneVerificationToken 은 5분 유효 · 1회용. signup 또는 비밀번호 재설정에서 소비됩니다 */
    public record VerifyResponse(String phoneVerificationToken, Instant expiresAt) {
    }
}