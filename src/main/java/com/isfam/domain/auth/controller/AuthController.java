package com.isfam.domain.auth.controller;

import com.isfam.domain.auth.dto.PhoneVerificationDto;
import com.isfam.domain.auth.service.PhoneVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /auth/** — 인증 관련 전부. 회원가입·로그인은 Phase 1-B 에서 추가됩니다 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PhoneVerificationService phoneVerificationService;

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
}