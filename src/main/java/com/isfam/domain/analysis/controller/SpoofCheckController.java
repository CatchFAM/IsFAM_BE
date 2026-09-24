package com.isfam.domain.analysis.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.analysis.dto.SpoofCheckDto;
import com.isfam.domain.analysis.service.SpoofCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
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
 * <p>현재 앱은 전처리한 통화의 중앙 5초를 16kHz mono PCM16 WAV로 보냅니다.
 * 통화 전체와 원본 m4a는 올리지 않습니다.
 */
@RestController
@RequiredArgsConstructor
public class SpoofCheckController {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";

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
                resolveClientIp(request));
    }

    /**
     * 접속기록에 남길 클라이언트 IP.
     *
     * <p>{@code getRemoteAddr()} 만 쓰면 리버스 프록시·로드밸런서 뒤에서는
     * 전부 같은 내부 IP 가 찍혀 기록이 무의미해집니다. 프록시 헤더를 먼저 봅니다.
     *
     * <p><b>다만 이 헤더들은 클라이언트가 위조할 수 있습니다.</b> 신뢰할 수 있는 프록시
     * 뒤에서만 의미가 있고, 프록시가 헤더를 덮어쓰도록 설정돼 있어야 합니다.
     * 인증·인가 판단에는 절대 쓰지 마세요 — 기록 용도입니다.
     *
     * <p>{@code X-Forwarded-For} 는 {@code client, proxy1, proxy2} 형태라 첫 항목만 씁니다.
     * 45자로 자르는 건 IPv6 최대 길이(39) + 여유이고, 컬럼 길이와 맞춥니다.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (StringUtils.hasText(forwarded)) {
            return truncate(forwarded.split(",")[0].trim());
        }
        String realIp = request.getHeader(HEADER_REAL_IP);
        if (StringUtils.hasText(realIp)) {
            return truncate(realIp.trim());
        }
        return truncate(request.getRemoteAddr());
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= 45 ? value : value.substring(0, 45);
    }
}
