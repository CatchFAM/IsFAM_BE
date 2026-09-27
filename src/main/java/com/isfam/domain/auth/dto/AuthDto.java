package com.isfam.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

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
     *
     * <p><b>{@code privacyAgreed} 에는 {@code @NotNull} 을 걸지 않았습니다</b> (Phase 14, P-06).
     * 이 필드를 모르는 앱 빌드는 값을 보내지 않는데, {@code @NotNull} 이면 그 요청이
     * COMMON_002 "입력값을 확인해 주세요"로 떨어져 무엇이 빠졌는지 알 수 없습니다.
     * 누락도 "처리방침 동의를 받지 못한 상태"라 AUTH_007 로 내보내는 편이 명세와 맞습니다.
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
            /** 개인정보 처리방침 (필수). null·false 모두 AUTH_007 */
            Boolean privacyAgreed,
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

    /**
     * GET /auth/me 응답. 필드 구성은 명세를 그대로 따릅니다.
     *
     * <p><b>user_name(실명)은 내려주지 않습니다.</b> 명세 출력에 없고, 화면에 쓰이는 이름은
     * 전부 display_name 입니다(가족 목록 · 초대 화면 · 임베딩 동기화). 실명은 가입 시 받아
     * 보관만 하는 값이라 조회 API 로 노출할 이유가 없습니다.
     *
     * <p>phoneNumber 는 마스킹해서 내려갑니다.
     */
    public record MeResponse(
            Long userId,
            String displayName,
            String phoneNumber,
            Instant createdAt
    ) {
    }

    public record DisplayNameRequest(
            @NotBlank(message = "닉네임을 입력해 주세요")
            @Size(max = 50, message = "닉네임은 50자를 넘을 수 없어요")
            String displayName
    ) {
    }

    /**
     * PUT /auth/me 응답.
     *
     * <p>updatedAt 이 명세에 있는 이유는 단순 표시용이 아닙니다. Phase 7 의
     * GET /family/embeddings 가 멤버별 updated_at 을 {@code GREATEST(voiceprints.updated_at,
     * users.updated_at, family_memberships.updated_at)} 으로 계산하므로,
     * 닉네임 변경이 users.updated_at 을 밀어야 다음 동기화에서 새 이름이 내려갑니다.
     */
    public record DisplayNameResponse(
            Long userId,
            String displayName,
            Instant updatedAt
    ) {
    }

    // ── 회원 탈퇴 ───────────────────────────────────────────

    /**
     * {@code DELETE /auth/me} 요청.
     *
     * <p><b>비밀번호를 요구합니다.</b> access_token 만으로도 본인이긴 하지만, 탈퇴는
     * 생체정보를 포함한 계정 전체를 되돌릴 수 없게 지우는 동작입니다. 폰을 잠깐 빌려주거나
     * 잠금 해제된 채로 두었을 때 한 번의 탭으로 실행되면 안 됩니다.
     *
     * <p>비밀번호 정책 검증({@code @Pattern})은 걸지 않습니다. 정책이 바뀌기 전에 가입한
     * 계정이 형식 때문에 탈퇴하지 못하는 상황을 만들지 않기 위해서입니다 —
     * 어차피 맞는지 여부는 BCrypt 대조로 판정합니다.
     */
    public record WithdrawRequest(
            @NotBlank(message = "비밀번호를 입력해 주세요")
            String password
    ) {
    }
}