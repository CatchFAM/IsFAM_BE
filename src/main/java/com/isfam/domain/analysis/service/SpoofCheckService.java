package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.log.MdcContext;
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

/**
 * 통화 녹음 딥보이스 판정 (Phase 8).
 *
 * <p><b>서버가 통화 음성을 만지는 유일한 지점입니다.</b> 이 클래스가
 * legal-compliance.md 4장 의무 4개의 실행 지점이고, 여기서 무너지면
 * 나머지 설계가 아무리 정확해도 소용이 없습니다.
 *
 * <pre>
 *   ① 암호화 전송   → TLS (AnalysisEndpointGuard 가 기동 시 검사)
 *   ② 접속기록      → spoof_checks row 저장. 거부한 요청도 남깁니다
 *   ③ 즉시 파기      → tmpfs 수신 후 finally 에서 삭제 · purged_at 기록
 *                     + 분석 서버 임시 파일은 remote_purged 로 (Phase 11 · P-23)
 *   ④ 처리방침      → 문서 (Phase 15)
 * </pre>
 *
 * <p><b>화자 비교를 절대 수행하지 않습니다.</b> 임베딩 추출도, 가족 대조도 없습니다.
 * 이 경계가 민감정보 해당 여부를 가릅니다 — 합성 여부 판별은 개인 식별이 아니라서
 * 상대방 동의 없이 가능하지만, 여기에 ECAPA 를 한 줄이라도 끼워 넣는 순간
 * 동의 불가능한 민감정보 처리가 됩니다.
 *
 * <p><b>{@code @Transactional} 을 클래스·메서드에 걸지 않은 건 의도적입니다.</b>
 * FastAPI 왕복(수백 ms ~ 수 초)이 DB 트랜잭션 안에 들어가면 커넥션이 그 시간만큼
 * 점유됩니다. 여기서 필요한 쓰기는 마지막 INSERT 한 건뿐이라 {@code save()} 자체
 * 트랜잭션으로 충분합니다.
 *
 * <p><b>여기서 알람을 쏘지 않습니다.</b> 파기 실패는 로그로만 남기고, 알람은
 * {@code PurgeMonitor} 가 5분 주기로 DB 를 세어 보냅니다(P-21). 요청 경로에
 * 외부 HTTP 호출(Slack)을 하나 더 붙이면 사용자 응답이 그만큼 늦어지고,
 * 무엇보다 <b>프로세스가 죽어서 파기가 안 된 경우에는 이 코드가 애초에 실행되지
 * 않습니다</b> — 감시는 요청 밖에 있어야 합니다.
 *
 * <p><b>Phase 12 — 저장하는 row 에 {@code trace_id} 가 붙습니다.</b> 같은 요청이
 * 다른 기록 테이블에 남긴 row 와 이어집니다. {@code operation}(고시 제8조의 "수행업무")은
 * 어느 팩토리를 쓰느냐로 정해지므로 여기서 따로 넘기지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpoofCheckService {

    /**
     * 앱은 음질 통과 구간 최대 30초를 보냅니다. 35초는 인코딩 오차를 감안한 상한입니다.
     * 이 값을 넘으면 판정하지 않습니다.
     */
    private static final double MAX_DURATION_SECONDS = 35.0;

    /** 30초 · 16kHz · mono · 16bit ≈ 960KB. 상한은 1.5MB */
    private static final long MAX_AUDIO_BYTES = 1_572_864L;

    /**
     * <b>wav 만 받습니다.</b> mp3·m4a 는 헤더만으로 길이를 구할 수 없어
     * 사전 거부(P-20)가 불가능합니다. 상한을 서버가 강제하려면 형식을 좁혀야 합니다.
     */
    private static final String ALLOWED_EXTENSION = "wav";

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
     * <p><b>거부할 때도 파기와 기록이 먼저입니다.</b> 순서가 뒤집히면 "상한을 넘는
     * 음성이 들어왔다"는 사실이 기록에 남지 않아, 통제를 만든 의미가 없어집니다.
     *
     * @param clientIp 접속기록 항목. 컨트롤러가 프록시 헤더까지 고려해 뽑아 넘깁니다
     */
    public SpoofCheckDto.Response check(Long userId, Long deviceId, Long callEventId,
                                        MultipartFile audioFile, String clientIp) {

        requireMyDevice(userId, deviceId);
        CallEvent callEvent = requireMyCallEvent(userId, callEventId);
        requireWav(audioFile);

        Instant requestedAt = clock.instant();

        // 요청 시작 시점에 확정합니다. 파기·기록 도중 스레드가 바뀔 일은 없지만,
        // 값을 한 번만 읽어두면 같은 요청에서 만든 row 들이 확실히 같은 값을 갖습니다.
        String traceId = resolveTraceId();

        AudioTempStorage.TempAudio audio = null;
        AntiSpoofingResponse result = null;
        SpoofCheck.RejectedReason rejection = null;
        Double duration = null;
        Instant purgedAt;

        try {
            audio = audioTempStorage.receive(audioFile, ALLOWED_EXTENSION);

            if (audio.sizeBytes() > MAX_AUDIO_BYTES) {
                rejection = SpoofCheck.RejectedReason.SIZE_EXCEEDED;
            } else {
                AudioTempStorage.Probe probe = audioTempStorage.probe(audio);
                duration = probe.durationSeconds();

                if (!probe.valid()) {
                    rejection = SpoofCheck.RejectedReason.INVALID_WAV_HEADER;
                } else if (probe.durationSeconds() > MAX_DURATION_SECONDS) {
                    rejection = SpoofCheck.RejectedReason.DURATION_EXCEEDED;
                } else {
                    result = antiSpoofingClient.detect(audio);

                    // 사후 확인 — 헤더가 실제 데이터와 다를 수 있습니다
                    Double reported = result.audioQuality() == null
                            ? null : result.audioQuality().durationSeconds();
                    if (reported != null) {
                        duration = reported;
                        if (reported > MAX_DURATION_SECONDS) {
                            rejection = SpoofCheck.RejectedReason.DURATION_EXCEEDED;
                        }
                    }
                }
            }
        } finally {
            // 예외가 나도 원본은 반드시 사라집니다.
            purgedAt = audioTempStorage.purge(audio);
        }

        Integer audioBytes = toIntBytes(audio == null ? 0 : audio.sizeBytes());

        /*
         * 분석 서버까지 간 요청만 원격 파기 값을 가집니다(P-23).
         * 사전 거부(헤더 불량·상한 초과)는 보낸 적이 없으므로 null 이고,
         * 그건 파기 실패가 아니라 "해당 없음"입니다.
         */
        Boolean remotePurged = result == null ? null : result.purged();
        warnIfRemoteNotPurged(result, callEventId);

        if (rejection != null) {
            SpoofCheck saved = spoofCheckRepository.save(SpoofCheck.rejected(
                    traceId, userId, callEvent.getId(), deviceId, rejection,
                    duration, audioBytes, clientIp, requestedAt, purgedAt, remotePurged));

            warnIfNotPurged(purgedAt, saved.getId(), callEventId);
            log.info("딥보이스 판정 거부 — spoofCheckId={}, reason={}, duration={}, bytes={}",
                    saved.getId(), rejection, duration, audioBytes);

            throw new BusinessException(ErrorCode.VOICE_002, rejection.toJson());
        }

        SpoofCheck saved = spoofCheckRepository.save(SpoofCheck.of(
                traceId,
                userId,
                callEvent.getId(),
                deviceId,
                result.spoofScore(),
                Boolean.TRUE.equals(result.isSpoofed()),
                result.threshold(),
                SpoofCheck.AnalysisStatus.from(result.analysisStatus()),
                result.analyzedSegments(),
                StringUtils.hasText(result.modelName()) ? result.modelName() : "unknown",
                duration,
                audioBytes,
                clientIp,
                requestedAt,
                purgedAt,
                remotePurged));

        warnIfNotPurged(purgedAt, saved.getId(), callEventId);

        log.debug("딥보이스 판정 — spoofCheckId={}, status={}, isSpoofed={}, segments={}",
                saved.getId(), saved.getAnalysisStatus(), saved.isSpoofed(),
                saved.getAnalyzedSegments());

        return SpoofCheckDto.Response.of(saved, result);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * MDC 에 trace id 가 없으면 새로 만듭니다.
     *
     * <p>HTTP 요청이면 {@code TraceIdFilter} 가 넣어 두었으므로 정상 흐름에서는
     * 항상 있습니다. 없다고 요청을 실패시키지는 않습니다 — 이어 붙일 상대가 없을 뿐
     * 접속기록 자체는 남아야 합니다.
     */
    private String resolveTraceId() {
        String traceId = MdcContext.traceId();
        return traceId != null ? traceId : MdcContext.newTraceId();
    }

    /**
     * 본인 단말인지 확인합니다. 없는 id 든 남의 단말이든 같은 DEVICE_002(401)입니다.
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
     * 확장자 검사.
     *
     * <p>이 단계에서 거부하면 파일을 받기 전이라 {@code spoof_checks} row 를 만들지
     * 않습니다. 헤더 불량({@code INVALID_WAV_HEADER})과 다른 지점입니다 —
     * 그쪽은 wav 라고 주장하며 들어온 파일이라 기록이 필요합니다.
     */
    private void requireWav(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_001, "audio_file 이 필요해요");
        }

        String filename = audioFile.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            return;
        }

        String extension = StringUtils.getFilenameExtension(filename);
        if (extension != null && !ALLOWED_EXTENSION.equals(extension.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.COMMON_007, "wav 만 분석할 수 있어요");
        }
    }

    private void warnIfNotPurged(Instant purgedAt, Long spoofCheckId, Long callEventId) {
        if (purgedAt == null) {
            // 여기 걸리면 tmpfs 에 통화 음성이 남아 있을 수 있습니다. 즉시 확인해야 합니다.
            // 5분 뒤 PurgeMonitor 가 집계해 알람 + 헬스체크 DOWN 으로 올립니다.
            log.error("!!! purged_at 이 비어 있습니다 — spoofCheckId={}, callEventId={}",
                    spoofCheckId, callEventId);
        }
    }

    /**
     * 분석 서버 파기 미확인 경고 (P-23).
     *
     * <p>{@code null} 은 아직 플래그를 안 보내는 FastAPI 버전입니다 — 배포 과도기에는
     * 정상이라 WARN 으로, 명시적 {@code false} 는 파기 실패라 ERROR 로 구분합니다.
     */
    private void warnIfRemoteNotPurged(AntiSpoofingResponse result, Long callEventId) {
        if (result == null || !result.isRemotePurgeUnconfirmed()) {
            return;
        }
        if (result.purged() == null) {
            log.warn("분석 서버 응답에 purged 플래그가 없습니다 — callEventId={} "
                    + "(FastAPI P-23 미배포 버전으로 보입니다)", callEventId);
        } else {
            log.error("!!! 분석 서버가 임시 파일을 파기하지 못했습니다 — callEventId={}", callEventId);
        }
    }

    /** ERD 의 audio_bytes 가 int 입니다. 상한이 1.5MB 라 넘칠 일은 없지만 방어합니다 */
    private Integer toIntBytes(long size) {
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }
}