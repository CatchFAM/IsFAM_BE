package com.isfam.domain.family.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.family.dto.FamilyDto;
import com.isfam.domain.family.service.FamilyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가족 공간.
 *
 * <p>전부 인증이 필요합니다. owner 권한(FAMILY_003)은 DB 를 봐야 판단되므로
 * SecurityConfig 가 아니라 FamilyService 에서 검사합니다.
 *
 * <p>초대 관련 엔드포인트({@code /family/invite-code})는 Phase 4 에서 별도 컨트롤러로 붙습니다.
 */
@RestController
@RequestMapping("/family")
@RequiredArgsConstructor
public class FamilyController {

    private final FamilyService familyService;

    /** 누구나 만들 수 있고, 생성자가 owner 가 됩니다 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyDto.FamilyResponse create(@CurrentUser Long userId,
                                           @Valid @RequestBody FamilyDto.CreateRequest request) {
        return familyService.create(userId, request.name());
    }

    /** 404 FAMILY_002 면 앱이 "공간 만들기 / 초대 코드 입력" 화면으로 분기합니다 */
    @GetMapping
    public FamilyDto.FamilyResponse getMyFamily(@CurrentUser Long userId) {
        return familyService.getMyFamily(userId);
    }

    @PutMapping
    public FamilyDto.UpdateNameResponse rename(
            @CurrentUser Long userId,
            @Valid @RequestBody FamilyDto.UpdateNameRequest request) {
        return familyService.rename(userId, request.name());
    }

    /** owner 전용. 본인 외 active 멤버가 남아 있으면 409 FAMILY_007 */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Long userId) {
        familyService.delete(userId);
    }

    /**
     * 나가기(본인 지정) / 내보내기(owner 가 타인 지정).
     *
     * <p>owner 는 개별 탈퇴가 안 됩니다(409 FAMILY_005) — 전원 내보낸 뒤 DELETE /family 입니다.
     */
    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@CurrentUser Long currentUserId,
                             @PathVariable("userId") Long targetUserId) {
        familyService.removeMember(currentUserId, targetUserId);
    }

    /**
     * 멤버 별명 지정 · 해제.
     *
     * <p>내 화면에서만 보이는 라벨이라 owner 권한이 필요 없습니다. active 멤버면 누구나
     * 다른 active 멤버에게 붙일 수 있습니다.
     *
     * <p>alias 가 null 이거나 공백뿐이면 해제입니다 — 별도 DELETE 엔드포인트를 두지 않은 건
     * 앱의 "입력란 비우고 저장" 흐름이 그대로 해제가 되기 때문입니다.
     */
    @PutMapping("/members/{userId}/alias")
    public FamilyDto.UpdateAliasResponse updateAlias(
            @CurrentUser Long currentUserId,
            @PathVariable("userId") Long targetUserId,
            @Valid @RequestBody FamilyDto.UpdateAliasRequest request) {
        return familyService.updateAlias(currentUserId, targetUserId, request.alias());
    }
}