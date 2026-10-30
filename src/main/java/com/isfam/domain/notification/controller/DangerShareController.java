package com.isfam.domain.notification.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.notification.dto.DangerShareDto;
import com.isfam.domain.notification.service.DangerShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위험 통화 공유. 번호 조회(GET)는 4단계에서 추가합니다.
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
}