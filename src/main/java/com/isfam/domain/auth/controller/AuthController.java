package com.isfam.domain.auth.controller;

import com.isfam.domain.auth.dto.AuthDto;
import com.isfam.domain.auth.dto.PhoneVerificationDto;
import com.isfam.domain.auth.service.AuthService;
import com.isfam.domain.auth.service.PhoneVerificationService;
import com.isfam.domain.auth.service.WithdrawalService;
import com.isfam.common.security.AuthPrincipal;
import com.isfam.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /auth/** — 번호 인증 · 가입 · 로그인 · 세션 · 내 정보 · 탈퇴 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PhoneVerificationService phoneVerificationService;
    private final AuthService authService;
    private final WithdrawalService withdrawalService;

    // ── 번호 인증 (비인증) ──────────────────────────────────

    /** 인증번호 발송. 데모에서는 실제 SMS 없이 123456 고정 */
    @PostMapping("/phone/send")
    public PhoneVerificationDto.SendResponse sendCode(
            @Valid @RequestBody PhoneVerificationDto.SendRequest request) {
        return phoneVerificationService.send(request.phoneNumber());
    }

    /** 인증번호 확인 → phone_verification_token(5분·1회용) 발급 */
    @PostMapping("/phone/verify")
    public PhoneVerificationDto.VerifyResponse verifyCode(
            @Valid @RequestBody PhoneVerificationDto.VerifyRequest request) {
        return phoneVerificationService.verify(request.verificationId(), request.verificationCode());
    }

    // ── 가입 · 로그인 (비인증) ──────────────────────────────

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDto.TokenResponse signup(@Valid @RequestBody AuthDto.SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/login")
    public AuthDto.TokenResponse login(@Valid @RequestBody AuthDto.LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthDto.TokenResponse refresh(@Valid @RequestBody AuthDto.RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** 비밀번호 재설정. 비밀번호를 잊은 상태라 access_token 없이 호출됩니다 */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody AuthDto.PasswordResetRequest request) {
        authService.resetPassword(request);
    }

    // ── 세션 · 내 정보 (인증 필요) ──────────────────────────

    /** 현재 세션만 종료. 다른 기기는 유지됩니다 */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CurrentUser AuthPrincipal principal,
                       @RequestBody(required = false) AuthDto.LogoutRequest request) {
        authService.logout(principal.userId(), principal.sessionId(),
                request == null ? null : request.refreshToken());
    }

    @GetMapping("/me")
    public AuthDto.MeResponse getMe(@CurrentUser Long userId) {
        return authService.getMe(userId);
    }

    /**
     * 닉네임 수정.
     *
     * <p>경로는 명세대로 {@code PUT /auth/me} 입니다. 하위 경로로 빼두면 앱이 명세대로
     * 짰을 때 405 가 납니다. 회원 탈퇴({@code DELETE /auth/me})도 같은 경로에
     * 메서드만 달리해 붙습니다.
     */
    @PutMapping("/me")
    public AuthDto.DisplayNameResponse updateDisplayName(
            @CurrentUser Long userId,
            @Valid @RequestBody AuthDto.DisplayNameRequest request) {
        return authService.updateDisplayName(userId, request.displayName());
    }

    /**
     * 회원 탈퇴.
     *
     * <p><b>되돌릴 수 없습니다.</b> 음성 등록 정보(voiceprints·voiceprint_samples)는
     * 물리 삭제되므로 같은 번호로 재가입해도 목소리는 다시 등록해야 합니다.
     * 법 제21조가 생체정보의 soft delete 를 허용하지 않습니다.
     *
     * <p>본문에 {@code password} 가 필요합니다 — 이유는 {@code WithdrawRequest} 참고.
     *
     * <p><b>응답은 204 입니다.</b> 성공했는데 본문을 기다리면 앱이 멈추므로 명세대로
     * 비워 보냅니다. 앱은 204 를 받으면 로컬 데이터(토큰·임베딩·통화 기록)를 전량
     * 삭제하고 로그인 화면으로 돌아가야 합니다.
     *
     * <p>에러는 둘입니다.
     * <ul>
     *   <li>401 AUTH_008 — 비밀번호 불일치</li>
     *   <li>409 FAMILY_005 — owner 인 공간이 있음. 전원 내보내고
     *       {@code DELETE /family} 로 공간을 지운 뒤 재시도</li>
     * </ul>
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@CurrentUser Long userId,
                         @Valid @RequestBody AuthDto.WithdrawRequest request) {
        withdrawalService.withdraw(userId, request.password());
    }
}