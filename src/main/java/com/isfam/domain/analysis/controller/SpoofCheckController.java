package com.isfam.domain.analysis.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.common.web.ClientIpResolver;
import com.isfam.domain.analysis.dto.SpoofCheckDto;
import com.isfam.domain.analysis.service.SpoofCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 통화 녹음 딥보이스 판정.
 *
 * <p><b>서버에서 통화 음성을 받는 유일한 엔드포인트입니다.</b>
 * 다른 곳에 오디오 업로드를 추가하려는 생각이 들면 legal-compliance.md 4장을 먼저 읽으세요 —
 * 암호화 전송 · 접속기록 · 즉시 파기 · 처리방침 네 가지 의무가 그 엔드포인트에도 붙습니다.
 *
 * <p>앱은 <b>음질 통과 구간 중 중앙 5초</b>만 보냅니다. 통화 전체를 올리지 않습니다.
 * 딥보이스 모델이 분석하는 길이가 5초이고, 그 이상은 수집 근거가 없습니다(법 제3조 제1항).
 * 서버 상한은 경계 여유 1초를 더한 6초이고, 넘으면 판정을 거부하고 기록만 남깁니다.
 *
 * <p><b>형식은 16kHz · mono · 16bit wav 이고 변환은 앱 책임입니다</b>(api-statement.md).
 * 바이트 상한(약 192KB)이 이 형식 기준이라, 44.1kHz·stereo 로 보내면 5초여도 거부됩니다.
 *
 * <p><b>IP 해석은 Phase 12 에서 {@link ClientIpResolver} 로 옮겼습니다</b>(P-16).
 */
@RestController
@RequiredArgsConstructor
public class SpoofCheckController {

    private final SpoofCheckService spoofCheckService;

    /**
     * 파트 이름은 {@code audio_file} 입니다 — 명세와 FastAPI 양쪽이 이 이름을 씁니다.
     *
     * <p>{@code X-Device-Id} 를 요구하는 이유는 {@code spoof_checks.device_id} 때문입니다.
     * "어느 기기에서 음성이 올라왔는가"가 접속기록의 항목입니다.
     */
    @PostMapping(
            value = "/call-events/{callEventId}/spoof-check",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpoofCheckDto.Response check(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @PathVariable Long callEventId,
            @RequestPart("audio_file") MultipartFile audioFile,
            HttpServletRequest request) {

        return spoofCheckService.check(userId, deviceId, callEventId, audioFile,
                ClientIpResolver.resolve(request));
    }
}