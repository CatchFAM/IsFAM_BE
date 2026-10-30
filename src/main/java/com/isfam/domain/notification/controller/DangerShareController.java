package com.isfam.domain.notification.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.notification.dto.DangerShareDto;
import com.isfam.domain.notification.service.DangerShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위험 통화 공유.
 *
 * <p>{@code NotificationController} 와 나눈 이유 — 그쪽은 "알림함은 서버만 만든다, 생성 API 가
 * 없다"가 원칙인 컨트롤러입니다. 공유는 사용자가 만드는 요청이라 성격이 다릅니다.
 */
@Tag(name = "notification", description = "위험 통화 공유")
@RestController
@RequestMapping("/notifications/danger-share")
@RequiredArgsConstructor
public class DangerShareController {

    private final DangerShareService dangerShareService;

    @Operation(summary = "위험 통화를 가족에게 공유")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DangerShareDto.CreateResponse share(
            @CurrentUser Long userId,
            @Valid @RequestBody DangerShareDto.CreateRequest request) {

        return dangerShareService.share(userId, request);
    }

    /**
     * 공유된 의심 번호 조회. 앱이 {@code danger_share} 푸시를 받자마자 한 번 부릅니다.
     *
     * <p><b>{@code Cache-Control: no-store}</b> — 응답이 회원이 아닌 사람의 전화번호입니다.
     * 중간 프록시나 폰의 HTTP 캐시에 남으면 7일 보관을 서버에서 지켜도 의미가 없습니다.
     */
    @Operation(summary = "공유된 의심 번호 조회")
    @GetMapping("/{shareId}")
    public ResponseEntity<DangerShareDto.PhoneResponse> getPhoneNumber(
            @CurrentUser Long userId,
            @PathVariable Long shareId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(dangerShareService.getPhoneNumber(userId, shareId));
    }
}