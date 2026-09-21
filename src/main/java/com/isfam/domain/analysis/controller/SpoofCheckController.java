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
 * <p>앱은 <b>음질 통과 구간 최대 30초</b>만 보냅니다. 통화 전체를 올리지 않습니다.
 * 서버가 16kHz mono 로 변환하므로 앱이 리샘플링할 필요는 없습니다.
 *
 * <p><b>IP 해석은 Phase 12 에서 {@link ClientIpResolver} 로 옮겼습니다</b>(P-16).
 * {@code embedding_access_logs} · {@code voiceprint_access_logs} 도 같은 "접속지" 항목을
 * 요구해서, 여기 private 메서드로 두면 세 벌이 됩니다. 프록시 설정이 바뀔 때 한 벌만
 * 고쳐지는 사고를 막으려고 공통으로 뺐습니다 — 동작은 그대로입니다.
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