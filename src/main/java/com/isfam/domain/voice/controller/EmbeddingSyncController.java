package com.isfam.domain.voice.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.security.DeviceId;
import com.isfam.domain.voice.dto.EmbeddingSyncDto;
import com.isfam.domain.voice.service.EmbeddingSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가족 임베딩 동기화.
 *
 * <p><b>경로는 {@code /family/embeddings} 인데 패키지는 voice 입니다.</b>
 * 다루는 데이터가 가족 구성이 아니라 화자 임베딩이라, 배포 조건·암호화·회수 규칙이
 * {@code VoiceprintService} 와 한 덩어리로 움직입니다. family 패키지에 두면
 * 임베딩 관련 규칙이 두 패키지로 흩어져, 한쪽만 고치는 사고가 납니다.
 *
 * <p>{@code since} 를 {@code Instant} 로 바인딩하지 않고 문자열로 받는 이유는
 * 형식이 틀렸을 때 안내 문구를 다르게 내기 위해서입니다. 타입 변환에 맡기면
 * 일반적인 COMMON_001 이 나가고, 앱 개발자는 어떤 파라미터가 문제인지 모릅니다.
 */
@RestController
@RequiredArgsConstructor
public class EmbeddingSyncController {

    private final EmbeddingSyncService embeddingSyncService;

    /**
     * 404 FAMILY_002 면 앱은 <b>로컬 임베딩을 전량 삭제</b>합니다 —
     * 공간에서 빠졌다는 뜻이라 가족의 생체정보를 갖고 있을 근거가 사라집니다.
     *
     * <p>수신 후 앱은 Android Keystore 기반 저장소에 저장하고
     * {@code PUT /devices/me/sync-status} 를 호출해야 완료로 간주됩니다.
     */
    @GetMapping("/family/embeddings")
    public EmbeddingSyncDto.EmbeddingsResponse getFamilyEmbeddings(
            @CurrentUser Long userId,
            @DeviceId Long deviceId,
            @RequestParam(name = "since", required = false) String since) {

        return embeddingSyncService.getFamilyEmbeddings(userId, deviceId, since);
    }
}