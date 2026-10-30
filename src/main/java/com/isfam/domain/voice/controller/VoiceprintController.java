package com.isfam.domain.voice.controller;

import com.isfam.common.security.CurrentUser;
import com.isfam.common.web.ClientIpResolver;
import com.isfam.domain.voice.dto.VoiceprintDto;
import com.isfam.domain.voice.service.VoiceprintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 목소리 등록 · 상태 조회 · 삭제.
 *
 * <p><b>{@code consumes = application/json} 을 명시한 건 의도적입니다.</b>
 * 이 엔드포인트로 오디오 파일이 올라오는 순간 legal-compliance.md 4장의 의무 4개
 * (암호화 전송 · 접속기록 · 즉시 파기 · 처리방침)가 여기에도 붙습니다. multipart 요청은
 * 415(COMMON_007)로 막히고, 그게 설계 의도를 코드로 못 박아두는 방법입니다.
 * 오디오를 받는 엔드포인트는 {@code POST /call-events/&#123;id&#125;/spoof-check} 하나뿐입니다.
 *
 * <p>모두 인증이 필요하고 본인 것만 다룹니다. 남의 임베딩을 조회할 수 있는 API 는
 * {@code GET /family/embeddings} 뿐이며, 그건 제3자 제공 동의를 받은 가족 멤버로 한정됩니다.
 *
 * <p><b>Phase 12 — 등록에 {@code HttpServletRequest} 가 붙었습니다</b>(P-28).
 * 생체정보 수집 기록의 "접속지"(고시 제8조) 때문입니다. 조회에는 붙이지 않았습니다 —
 * 그 응답에는 임베딩이 실리지 않아 기록 대상이 아닙니다. 삭제(Phase 14)는 파기 기록 때문에 붙습니다.
 */
@Tag(name = "voiceprint", description = "내 목소리 등록 · 조회 · 삭제")
@RestController
@RequestMapping("/me/voiceprint")
@RequiredArgsConstructor
public class VoiceprintController {

    private final VoiceprintService voiceprintService;

    /**
     * 등록 · 재등록. 여러 번 호출하면 샘플이 누적되고(3회 이상 권장),
     * 같은 {@code sentence_id} 면 해당 샘플만 교체됩니다.
     *
     * <p>새로 만들어지는지 갱신되는지 앱이 구분할 필요가 없어 201 이 아니라 200 입니다.
     */
    @Operation(summary = "내 목소리 등록/재등록")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public VoiceprintDto.RegisterResponse register(
            @CurrentUser Long userId,
            @Valid @RequestBody VoiceprintDto.RegisterRequest request,
            HttpServletRequest httpRequest) {
        return voiceprintService.register(userId, request,
                ClientIpResolver.resolve(httpRequest));
    }

    /** 미등록이면 404 가 아니라 {@code voiceprint_registered=false} 인 200 입니다 */
    @Operation(summary = "내 목소리 등록 상태 조회")
    @GetMapping
    public VoiceprintDto.StatusResponse getStatus(@CurrentUser Long userId) {
        return voiceprintService.getStatus(userId);
    }

    /**
     * 내 목소리 삭제 (Phase 14, P-18). 계정·가족 공간은 그대로 두고 음성 등록 정보만 지웁니다.
     *
     * <p><b>응답은 204, 본문 없음입니다.</b> 등록한 적 없어도 204 입니다(멱등).
     * 앱은 204 를 받으면 온보딩의 "목소리 등록" 상태로 돌아가면 됩니다 —
     * 같은 공간 가족 폰에서는 다음 동기화 때 이 사람의 임베딩이 지워집니다.
     *
     * <p>본문을 받지 않습니다. 탈퇴와 달리 비밀번호를 묻지 않는 이유는, 되돌릴 수 없긴 하지만
     * 계정이 남아 언제든 다시 등록할 수 있고, 권리 행사(처리정지)를 어렵게 만들 이유가 없어서입니다.
     */
    @Operation(summary = "내 목소리 삭제")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser Long userId, HttpServletRequest httpRequest) {
        voiceprintService.delete(userId, ClientIpResolver.resolve(httpRequest));
    }
}