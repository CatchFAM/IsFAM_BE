package com.isfam.infra.analysis;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * FastAPI 딥보이스 판정 호출.
 *
 * <p><b>이 클라이언트가 부르는 엔드포인트는 {@code /api/v1/anti-spoofing/detect} 하나뿐입니다.</b>
 * FastAPI 에는 가족 임베딩 대조까지 하는 {@code /voice/verify} 도 있지만,
 * 그걸 호출하는 순간 "합성 판별"이 "발신자 식별"로 성격이 바뀌어
 * 동의를 받을 수 없는 상대방의 민감정보 처리가 됩니다(legal-compliance.md 5장).
 * 메서드를 하나만 열어두는 게 그 경계를 코드로 못 박는 방법입니다.
 *
 * <p><b>오디오는 로그에 남기지 않습니다.</b> 요청 본문 로깅 인터셉터를 붙이지 마세요 —
 * 파일 바이트가 로그 파일로 새면 "저장하지 않는다"는 처리방침이 깨집니다.
 *
 * <p><b>{@code ObjectMapper} 를 주입받지 않습니다.</b> Spring Boot 4 는 Jackson 3 을 쓰고,
 * 그쪽 {@code ObjectMapper} 는 {@code tools.jackson.databind} 패키지입니다
 * (어노테이션만 {@code com.fasterxml.jackson.annotation} 에 남았습니다).
 * 여기서 JSON 파싱이 필요한 곳은 에러 로그 한 줄뿐이라, Jackson 버전에 묶이는 대신
 * 응답 본문을 그대로 잘라 씁니다.
 */
@Slf4j
@Component
public class AntiSpoofingClient {

    /** FastAPI 가 기대하는 파트 이름. {@code file} 이 아닙니다 */
    private static final String PART_NAME = "audio_file";

    /** 에러 본문 로그 상한. FastAPI 의 {@code detail} 은 짧지만 방어적으로 자릅니다 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private final RestClient restClient;
    private final AntiSpoofingProperties properties;

    public AntiSpoofingClient(RestClient antiSpoofingRestClient,
                              AntiSpoofingProperties properties) {
        this.restClient = antiSpoofingRestClient;
        this.properties = properties;
    }

    /**
     * @param audio tmpfs 에 놓인 파일. 이 메서드는 파일을 지우지 않습니다 —
     *              파기는 호출부의 {@code finally} 책임입니다
     */
    public AntiSpoofingResponse detect(AudioTempStorage.TempAudio audio) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(PART_NAME, new FileSystemResource(audio.path()));

        try {
            AntiSpoofingResponse response = restClient.post()
                    .uri(properties.detectPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw translate(clientResponse.getStatusCode(),
                                readBody(clientResponse.getBody()));
                    })
                    .body(AntiSpoofingResponse.class);

            if (response == null || !response.isUsable()) {
                log.error("분석 서버 응답이 비정상입니다: {}", response);
                throw new BusinessException(ErrorCode.COMMON_009);
            }
            return response;

        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결 거부 · 타임아웃 · 역직렬화 실패. 앱 입장에서는 전부 "나중에 다시" 입니다
            log.error("분석 서버 호출 실패 ({}{})", properties.baseUrl(), properties.detectPath(), e);
            throw new BusinessException(ErrorCode.COMMON_009);
        }
    }

    /**
     * FastAPI 의 에러를 앱이 이해하는 코드로 옮깁니다.
     *
     * <p>여기서 502 로 뭉뚱그리면 안 됩니다. "음성이 너무 짧다"는 앱이
     * <b>구간을 다시 모아 재시도</b>하면 해결되는 문제고, "분석 서버가 죽었다"는
     * 재시도해도 소용없는 문제라 사용자 안내가 완전히 갈립니다.
     *
     * <ul>
     *   <li>400 — 파일 없음 · 1.0초 미만 → VOICE_001(422)</li>
     *   <li>413 — 25MB 초과 → COMMON_006(413). 우리 쪽 5MB 제한에 먼저 걸리는 게 정상입니다</li>
     *   <li>415 — 지원하지 않는 확장자 → COMMON_007(415)</li>
     *   <li>422 — 디코딩 실패(손상된 파일) → VOICE_001(422)</li>
     *   <li>그 외 · 5xx — 모델 오류 → COMMON_009(502)</li>
     * </ul>
     */
    private BusinessException translate(HttpStatusCode status, String detail) {
        log.warn("분석 서버 에러 {} — {}", status.value(), detail);

        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return new BusinessException(ErrorCode.VOICE_001,
                    "분석할 음성이 너무 짧아요. 통화가 조금 더 길 때 다시 시도해 주세요");
        }
        if (status.isSameCodeAs(HttpStatus.PAYLOAD_TOO_LARGE)) {
            return new BusinessException(ErrorCode.COMMON_006);
        }
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return new BusinessException(ErrorCode.COMMON_007);
        }
        if (status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            return new BusinessException(ErrorCode.VOICE_001,
                    "음성 파일을 읽지 못했어요. 다시 시도해 주세요");
        }
        return new BusinessException(ErrorCode.COMMON_009);
    }

    /**
     * FastAPI 에러 바디는 {@code {"detail": "..."}} 입니다.
     *
     * <p>파싱하지 않고 원문을 그대로 씁니다. 로그에 찍는 게 전부라 따옴표와 중괄호가
     * 몇 개 더 보이는 건 문제가 되지 않고, 그 대가로 Jackson 버전 의존이 사라집니다.
     * 읽기에 실패해도 삼킵니다 — 여기서 예외가 나면 정작 중요한 원래 에러가 묻힙니다.
     */
    private String readBody(InputStream body) {
        try {
            String raw = StreamUtils.copyToString(body, StandardCharsets.UTF_8);
            return raw.length() <= MAX_DETAIL_LENGTH
                    ? raw
                    : raw.substring(0, MAX_DETAIL_LENGTH) + "...(생략)";
        } catch (IOException | RuntimeException e) {
            return "(응답 본문을 읽지 못했습니다)";
        }
    }
}