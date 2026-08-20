package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.dto.SpoofCheckDto;
import com.isfam.domain.analysis.entity.CallEvent;
import com.isfam.domain.analysis.entity.SpoofCheck;
import com.isfam.domain.analysis.repository.CallEventRepository;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.infra.analysis.AntiSpoofingClient;
import com.isfam.infra.analysis.AntiSpoofingResponse;
import com.isfam.infra.analysis.AudioTempStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * 통화 녹음 딥보이스 판정 (Phase 8).
 *
 * <p><b>서버가 통화 음성을 만지는 유일한 지점입니다.</b> 이 클래스가
 * legal-compliance.md 4장 의무 4개의 실행 지점이고, 여기서 무너지면
 * 나머지 설계가 아무리 정확해도 소용이 없습니다.
 *
 * <pre>
 *   ① 암호화 전송   → TLS (인프라 · isfam.fastapi.base-url 을 https 로)
 *   ② 접속기록      → spoof_checks row 저장
 *   ③ 즉시 파기      → tmpfs 수신 후 finally 에서 삭제 · purged_at 기록
 *   ④ 처리방침      → 문서 (9장 미완 항목)
 * </pre>
 *
 * <p><b>화자 비교를 절대 수행하지 않습니다.</b> 임베딩 추출도, 가족 대조도 없습니다.
 * 이 경계가 민감정보 해당 여부를 가릅니다 — 합성 여부 판별은 개인 식별이 아니라서
 * 상대방 동의 없이 가능하지만, 여기에 ECAPA 를 한 줄이라도 끼워 넣는 순간
 * 동의 불가능한 민감정보 처리가 됩니다(legal-compliance.md 1장·5장).
 *
 * <p><b>{@code @Transactional} 을 클래스·메서드에 걸지 않은 건 의도적입니다.</b>
 * FastAPI 왕복(수백 ms ~ 수 초)이 DB 트랜잭션 안에 들어가면 커넥션이 그 시간만큼
 * 점유됩니다. 통화가 몰리는 시간대에 그런 요청이 쌓이면 커넥션 풀이 마르고,
 * 분석과 무관한 API 까지 같이 멈춥니다. 여기서 필요한 쓰기는 마지막 INSERT 한 건뿐이라
 * {@code save()} 자체 트랜잭션으로 충분합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpoofCheckService {

    /**
     * FastAPI 가 받는 형식. 명세상 앱은 wav 로 보내지만, 분석 서버가 mp3·m4a 도
     * 처리하므로 굳이 좁히지 않았습니다. <b>서버가 16kHz mono 변환을 하므로
     * 앱이 리샘플링할 필요는 없습니다</b> — 다만 30초 상한과 음질 게이트는 앱 책임입니다.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "m4a");

    private static final String DEFAULT_EXTENSION = "wav";

    private final SpoofCheckRepository spoofCheckRepository;
    private final CallEventRepository callEventRepository;
    private final DeviceRepository deviceRepository;
    private final AntiSpoofingClient antiSpoofingClient;
    private final AudioTempStorage audioTempStorage;
    private final Clock clock;

    /**
     * {@code POST /call-events/&#123;call_event_id&#125;/spoof-check}.
     *
     * <p>순서가 중요합니다 — <b>소유권 확인이 파일을 받기 전에</b> 끝나야 합니다.
     * 남의 통화 이벤트에 음성을 올릴 수 있으면, 그건 남의 이름으로 접속기록을
     * 남기는 것과 같습니다.
     *
     * @param clientIp 접속기록 항목. 컨트롤러가 프록시 헤더까지 고려해 뽑아 넘깁니다
     */
    public SpoofCheckDto.Response check(Long userId, Long deviceId, Long callEventId,
                                        MultipartFile audioFile, String clientIp) {

        requireMyDevice(userId, deviceId);
        CallEvent callEvent = requireMyCallEvent(userId, callEventId);

        String extension = resolveExtension(audioFile);
        Instant requestedAt = clock.instant();

        AntiSpoofingResponse result;
        Instant purgedAt = null;
        AudioTempStorage.TempAudio audio = null;

        try {
            audio = audioTempStorage.receive(audioFile, extension);
            result = antiSpoofingClient.detect(audio);
        } finally {
            // 예외가 나도 원본은 반드시 사라집니다. legal-compliance.md 4장 ③ 의
            // 파이썬 예시와 같은 구조입니다.
            purgedAt = audioTempStorage.purge(audio);
        }

        SpoofCheck saved = spoofCheckRepository.save(SpoofCheck.of(
                userId,
                callEvent.getId(),
                deviceId,
                result.spoofScore(),
                Boolean.TRUE.equals(result.isSpoofed()),
                result.threshold(),
                SpoofCheck.AnalysisStatus.from(result.analysisStatus()),
                result.analyzedSegments(),
                StringUtils.hasText(result.modelName()) ? result.modelName() : "unknown",
                result.audioQuality() == null ? null : result.audioQuality().durationSeconds(),
                toIntBytes(audio == null ? 0 : audio.sizeBytes()),
                clientIp,
                requestedAt,
                purgedAt));

        if (purgedAt == null) {
            // 여기 걸리면 tmpfs 에 통화 음성이 남아 있을 수 있습니다. 즉시 확인해야 합니다.
            log.error("!!! purged_at 이 비어 있습니다 — spoofCheckId={}, callEventId={}",
                    saved.getId(), callEventId);
        }

        log.debug("딥보이스 판정 — spoofCheckId={}, status={}, isSpoofed={}, segments={}",
                saved.getId(), saved.getAnalysisStatus(), saved.isSpoofed(),
                saved.getAnalyzedSegments());

        return SpoofCheckDto.Response.of(saved, result);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 본인 단말인지 확인합니다. 없는 id 든 남의 단말이든 같은 DEVICE_002(401)입니다 —
     * 다른 서비스와 같은 규칙입니다.
     *
     * <p>여기서 단말까지 보는 이유는 이 요청이 통화 음성을 실어 오기 때문입니다.
     * 어느 기기에서 무엇이 올라왔는지가 {@code spoof_checks.device_id} 로 남아야
     * 접속기록으로서 의미가 있습니다.
     */
    private void requireMyDevice(Long userId, Long deviceId) {
        deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_002,
                        "단말 정보를 확인할 수 없어요. 앱을 다시 실행해 주세요"));
    }

    private CallEvent requireMyCallEvent(Long userId, Long callEventId) {
        return callEventRepository.findByIdAndUserId(callEventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 통화 기록을 찾을 수 없어요"));
    }

    /**
     * 확장자 판정.
     *
     * <p>FastAPI 가 파일명으로 형식을 검사하므로(415 조건) 확장자를 살려서 넘겨야 합니다.
     * 파일명이 없거나 확장자가 없으면 wav 로 가정합니다 — 앱은 wav 만 보내게 돼 있고,
     * 여기서 400 을 내면 앱 버그가 아닌 경우에도 분석이 통째로 막힙니다.
     * 실제로 형식이 틀렸다면 FastAPI 가 415 로 잡아주고, 그건 COMMON_007 로 전달됩니다.
     */
    private String resolveExtension(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_001, "audio_file 이 필요해요");
        }

        String filename = audioFile.getOriginalFilename();
        if (StringUtils.hasText(filename)) {
            String extension = StringUtils.getFilenameExtension(filename);
            if (extension != null) {
                String normalized = extension.toLowerCase(Locale.ROOT);
                if (ALLOWED_EXTENSIONS.contains(normalized)) {
                    return normalized;
                }
                throw new BusinessException(ErrorCode.COMMON_007,
                        "wav, mp3, m4a 만 분석할 수 있어요");
            }
        }
        return DEFAULT_EXTENSION;
    }

    /** ERD 의 audio_bytes 가 int 입니다. 업로드 상한이 5MB 라 넘칠 일은 없지만 방어합니다 */
    private Integer toIntBytes(long size) {
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }
}