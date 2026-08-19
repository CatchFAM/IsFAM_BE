package com.isfam.domain.family.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.family.dto.InvitationDto;
import com.isfam.domain.family.service.InvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초대를 받는 쪽 — 딥링크로 진입한 사람이 부릅니다.
 *
 * <p><b>인증 요건이 메서드마다 다릅니다.</b> 미리보기는 비인증(앱을 깔자마자 링크를 눌렀을 때
 * 로그인 화면을 보여주기 전에 "○○ 가족이 초대했어요"를 띄워야 함), 수락은 인증입니다.
 * SecurityConfig 의 {@code PUBLIC_GET} 에 {@code /invitations/*} 가 이미 들어 있고,
 * 이 패턴은 단일 세그먼트만 매칭하므로 {@code /invitations/&#123;code&#125;/accept} 는 걸리지 않습니다.
 */
@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    /**
     * 딥링크 진입 직후 · 비인증 호출.
     * 404 / 410(INVITE_001) 이면 앱은 "만료된 초대예요" 로 분기합니다.
     */
    @GetMapping("/{inviteCode}")
    public InvitationDto.PreviewResponse preview(@PathVariable String inviteCode) {
        return invitationService.preview(inviteCode);
    }

    /**
     * 가입 또는 로그인 후 호출.
     *
     * <p>본문을 {@code required = false} 로 둔 이유는 누락 시 422 INVITE_003 을 내보내기
     * 위해서입니다. 필수로 두면 본문 없는 요청이 COMMON_001(400)이 되어 앱이
     * "음성 공유 동의가 필요해요" 분기를 못 탑니다.
     *
     * <p>수락 직후 앱은 {@code GET /family/embeddings} 를 since 없이 호출합니다.
     */
    @PostMapping("/{inviteCode}/accept")
    public InvitationDto.AcceptResponse accept(
            @CurrentUser Long userId,
            @PathVariable String inviteCode,
            @RequestBody(required = false) InvitationDto.AcceptRequest request) {
        return invitationService.accept(
                userId, inviteCode, request == null ? null : request.voiceSharingConsent());
    }
}