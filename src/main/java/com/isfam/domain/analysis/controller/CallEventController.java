package com.isfam.domain.analysis.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.domain.analysis.dto.CallEventDto;
import com.isfam.domain.analysis.service.CallEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통화 감지 이벤트.
 *
 * <p><b>{@code consumes = application/json} 을 명시한 건 의도적입니다.</b>
 * {@code VoiceprintController} 와 같은 이유입니다 — 오디오를 받는 엔드포인트는
 * {@code POST /call-events/&#123;id&#125;/spoof-check} 하나뿐이어야 하고,
 * 그 경계를 코드로 못 박아 둡니다. 여기로 multipart 가 오면 415 로 막힙니다.
 */
@Tag(name = "call event", description = "통화 감지 이벤트")
@RestController
@RequestMapping("/call-events")
@RequiredArgsConstructor
public class CallEventController {

    private final CallEventService callEventService;

    /**
     * 앱이 통화 종료 · 녹음 파일 생성을 감지했을 때.
     *
     * <p>200 인 이유는 명세의 응답 예시가 그렇기 때문입니다. 생성이지만 앱이
     * Location 헤더를 쓰지 않고 본문의 {@code call_event_id} 만 받아 갑니다.
     */
    @Operation(summary = "통화 종료·녹음 파일 생성을 감지했을 때 이벤트 등록")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallEventDto.CreateResponse create(
            @CurrentUser Long userId,
            @Valid @RequestBody CallEventDto.CreateRequest request) {
        return callEventService.create(userId, request);
    }

    /**
     * 분석을 건너뛰거나 실패했을 때. <b>{@code skipped}·{@code failed} 만</b> 받습니다.
     *
     * <p>{@code analyzed_on_device} 는 {@code POST /voice-analyses} 가 같은 트랜잭션에서
     * 처리합니다 — 앱이 두 API 를 다 부르면 중간에 끊겼을 때 상태가 어긋납니다.
     */
    @Operation(summary = "분석을 건너뛰거나 실패했을 때 상태 갱신")
    @PutMapping(value = "/{callEventId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallEventDto.UpdateResponse updateStatus(
            @CurrentUser Long userId,
            @PathVariable Long callEventId,
            @Valid @RequestBody CallEventDto.UpdateRequest request) {
        return callEventService.updateStatus(userId, callEventId, request);
    }
}