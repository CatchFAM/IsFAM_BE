package com.isfam.domain.family.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.family.dto.InvitationDto;
import com.isfam.domain.family.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초대 링크 관리 — 공간을 가진 쪽에서 부르는 API 입니다.
 * 초대를 받는 쪽은 {@link InvitationController} 를 씁니다.
 *
 * <p>전부 인증이 필요합니다. FamilyController 와 경로 접두사({@code /family})가 겹치지만
 * 다루는 리소스가 달라 컨트롤러를 나눴습니다.
 */
@Tag(name = "invitation", description = "초대 링크 발급 · 조회 · 수락")
@RestController
@RequestMapping("/family/invite-code")
@RequiredArgsConstructor
public class InviteCodeController {

    private final InvitationService invitationService;

    /**
     * 초대 화면 진입 시 호출.
     *
     * <p>코드가 없거나 만료·비활성이어도 <b>200</b> 이고 전 필드가 null 입니다.
     * 앱은 이걸 보고 "초대 링크 만들기" 버튼을 띄웁니다.
     */
    @Operation(summary = "현재 초대 링크/QR 조회")
    @GetMapping
    public InvitationDto.InviteCodeResponse getInviteCode(@CurrentUser Long userId) {
        return invitationService.getInviteCode(userId);
    }

    /**
     * "링크 새로 만들기" 버튼 전용 — <b>화면 진입 시 부르면 안 됩니다.</b>
     * 기존 코드가 즉시 죽어서 방금 공유한 링크가 무효가 됩니다.
     *
     * <p>요청 본문이 없습니다(명세 입력 "-"). 링크를 만드는 사람의 음성 공유 동의는 가입 때 받고,
     * 공간을 만들 때 이미 공유가 켜집니다. 본문을 보내도 읽지 않습니다.
     *
     * <p>201 이 아니라 200 인 이유: 새로 주소가 생기는 게 아니라 기존 리소스 하나의 값이
     * 교체되는 동작이고, 응답 형태가 GET 과 완전히 같아서 앱이 파서를 공유합니다.
     */
    @Operation(summary = "초대 링크/QR 발급")
    @PostMapping
    public InvitationDto.InviteCodeResponse issueInviteCode(@CurrentUser Long userId) {
        return invitationService.issueInviteCode(userId);
    }

    /** owner 전용. 링크를 죽입니다(403 FAMILY_003) */
    @Operation(summary = "초대 링크 비활성화")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateInviteCode(@CurrentUser Long userId) {
        invitationService.deactivateInviteCode(userId);
    }
}