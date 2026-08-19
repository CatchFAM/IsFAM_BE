package com.isfam.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 · 로그인 · 토큰 재발급 · 내 정보의 요청·응답.
 * 번호 인증은 PhoneVerificationDto 에 따로 있습니다.
 */
public final class AuthDto {

    private AuthDto() {
    }

    /** 비밀번호 정책 — 영문/숫자 포함 8자 이상 */
    private static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*[0-9]).{8,}$";
    private static final String PASSWORD_MESSAGE = "비밀번호는 영문과 숫자를 포함해 8자 이상이어야 해요";

    // ── 회원가입 ────────────────────────────────────────────

    /**
     * <b>필수 동의는 @AssertTrue 대신 서비스에서 검사합니다.</b>
     * 검증 어노테이션으로 막으면 COMMON_002(422)가 나가는데, 명세는 AUTH_007 을 요구합니다.
     */
    public record SignupRequest(
            @NotBlank(message = "휴대폰 번호를 입력해 주세요")
            @Pattern(regexp = "^01[0-9]{8,9}$", message = "휴대폰 번호 형식이 올바르지 않아요")
            String phoneNumber,

            @NotBlank(message = "번호 인증이 필요해요")
            String phoneVerificationToken,

            @NotBlank(message = "비밀번호를 입력해 주세요")
            @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
            String password,

            @NotBlank(message = "이름을 입력해 주세요")
            @Size(max = 50)
            String userName,

            @NotBlank(message = "닉네임을 입력해 주세요")
            @Size(max = 50)
            String displayName,

            @NotNull Boolean termsAgreed,
            @NotNull Boolean voicePrintAgreed,
            @NotNull Boolean marketingAgreed
    ) {
    }

    // ── 로그인 · 토큰 ───────────────────────────────────────

    public record LoginRequest(
            @NotBlank(message = "휴대폰 번호를 입력해 주세요")
            String phoneNumber,

            @NotBlank(message = "비밀번호를 입력해 주세요")
            String password
    ) {
    }

    public record RefreshRequest(
            @NotBlank(message = "다시 로그인해 주세요")
            String refreshToken
    ) {
    }

    /**
     * signup · login · refresh 공용 응답.
     * refresh 때는 userId·displayName 이 없어 NON_NULL 로 빼고 내려갑니다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(
            Long userId,
            String displayName,
            String accessToken,
            String refreshToken
    ) {
        public static TokenResponse of(Long userId, String displayName,
                                       String accessToken, String refreshToken) {
            return new TokenResponse(userId, displayName, accessToken, refreshToken);
        }

        public static TokenResponse reissued(String accessToken, String refreshToken) {
            return new TokenResponse(null, null, accessToken, refreshToken);
        }
    }

    public record LogoutRequest(String refreshToken) {
    }

    // ── 비밀번호 재설정 ─────────────────────────────────────

    public record PasswordResetRequest(
            @NotBlank(message = "휴대폰 번호를 입력해 주세요")
            String phoneNumber,

            @NotBlank(message = "번호 인증이 필요해요")
            String phoneVerificationToken,

            @NotBlank(message = "비밀번호를 입력해 주세요")
            @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
            String newPassword
    ) {
    }

    // ── 내 정보 ────────────────────────────────────────────

    /** phoneNumber 는 마스킹해서 내려갑니다 */
    public record MeResponse(Long userId, String phoneNumber, String userName, String displayName) {
    }

    public record DisplayNameRequest(
            @NotBlank(message = "닉네임을 입력해 주세요")
            @Size(max = 50, message = "닉네임은 50자를 넘을 수 없어요")
            String displayName
    ) {
    }

    public record DisplayNameResponse(Long userId, String displayName) {
    }
}