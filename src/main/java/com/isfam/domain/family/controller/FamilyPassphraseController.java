package com.isfam.domain.family.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.family.dto.FamilyPassphraseDto;
import com.isfam.domain.family.service.FamilyPassphraseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 우리 집 암호. 전부 인증이 필요하고, owner 권한(FAMILY_003)은 서비스에서 검사합니다.
 */
@Tag(name = "family", description = "가족 공간 · 구성원")
@RestController
@RequestMapping("/family/passphrase")
@RequiredArgsConstructor
public class FamilyPassphraseController {

    private final FamilyPassphraseService passphraseService;

    /** active 멤버 누구나. 미설정이면 404 FAMILY_008 */
    @Operation(summary = "우리 집 암호 조회")
    @GetMapping
    public FamilyPassphraseDto.PassphraseResponse get(@CurrentUser Long userId) {
        return passphraseService.get(userId);
    }

    /** owner 전용 등록·수정. 바뀌었으면 나머지 멤버에게 가족 소식 알림 */
    @Operation(summary = "우리 집 암호 등록·수정")
    @PutMapping
    public FamilyPassphraseDto.PassphraseResponse save(
            @CurrentUser Long userId,
            @Valid @RequestBody FamilyPassphraseDto.SaveRequest request) {
        return passphraseService.save(userId, request.question(), request.answer());
    }

    /** owner 전용. 없어도 204. 알림 없음 */
    @Operation(summary = "우리 집 암호 삭제")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Long userId) {
        passphraseService.delete(userId);
    }
}