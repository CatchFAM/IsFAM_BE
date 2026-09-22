package com.isfam.domain.audit.service;

import com.isfam.common.log.MdcContext;
import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import com.isfam.domain.audit.repository.VoiceprintAccessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 음성의 수집·파기를 기록합니다 (P-28).
 *
 * <p><b>기록이 실패하면 그 작업도 실패합니다.</b> {@code EmbeddingAccessLogger} 와
 * 같은 판단이고, 같은 방식({@link Propagation#MANDATORY})으로 강제합니다.
 * 호출자의 트랜잭션에 반드시 올라타므로 여기서 터지면 등록이나 탈퇴가 통째로 롤백됩니다.
 *
 * <p>파기 쪽은 특히 그렇습니다. <b>지워졌는데 기록이 없으면 복구할 방법이 없습니다</b> —
 * 생체정보는 물리 삭제라 되돌릴 수도, 나중에 재구성할 수도 없습니다. 반면 롤백되면
 * 사용자는 에러를 보고 다시 시도하면 됩니다. 한쪽만 되돌릴 수 있으니 그쪽을 고릅니다.
 *
 * <p><b>지운 게 0건이면 기록하지 않습니다.</b> 음성을 등록한 적 없는 사용자가 탈퇴하면
 * 지울 것도 없고, 그때 "파기했다"는 row 를 남기면 있지도 않았던 생체정보가 있었던 것처럼
 * 보입니다. 기록이 사실과 달라지는 쪽이 비어 있는 것보다 나쁩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceprintAccessLogger {

    private final VoiceprintAccessLogRepository repository;

    /**
     * 음성 등록·재등록. {@code POST /me/voiceprint} 한 번에 한 건입니다.
     *
     * <p>같은 문장을 다시 녹음해 샘플이 <b>교체</b>되는 경우도 기록합니다 —
     * 새 생체정보를 수집한 것이기 때문입니다.
     *
     * @param affectedSamples 등록 후 이 사용자의 전체 샘플 수
     * @param now             호출자가 {@code Clock} 으로 만든 시각
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRegister(Long userId, Integer sentenceId, int affectedSamples,
                               String embeddingModelVersion, String clientIp, Instant now) {

        repository.save(VoiceprintAccessLog.register(
                resolveTraceId(), userId, userId,
                sentenceId, affectedSamples, embeddingModelVersion, clientIp, now));

        log.debug("음성 등록 기록 — userId={}, sentenceId={}, samples={}",
                userId, sentenceId, affectedSamples);
    }

    /**
     * 생체정보 파기.
     *
     * @param actorUserId          작업을 수행한 계정. 본인이면 {@code subjectUserId} 와 같고,
     *                             배치면 null
     * @param affectedSamples      사라진 {@code voiceprint_samples} 건수
     * @param affectedVoiceprints  사라진 {@code voiceprints} 건수 (0 또는 1)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDestroy(Long subjectUserId, Long actorUserId,
                              VoiceprintAccessLog.Reason reason,
                              int affectedSamples, int affectedVoiceprints,
                              String embeddingModelVersion, String clientIp, Instant now) {

        // 지운 게 없으면 파기가 아닙니다. 없던 것을 있었던 것처럼 남기지 않습니다.
        if (affectedSamples <= 0 && affectedVoiceprints <= 0) {
            return;
        }

        repository.save(VoiceprintAccessLog.destroy(
                resolveTraceId(), subjectUserId, actorUserId, reason,
                affectedSamples, affectedVoiceprints, embeddingModelVersion, clientIp, now));

        log.info("생체정보 파기 기록 — subject={}, reason={}, samples={}, voiceprints={}",
                subjectUserId, reason, affectedSamples, affectedVoiceprints);
    }

    /**
     * MDC 에 trace id 가 없으면 새로 만듭니다.
     *
     * <p>HTTP 요청이면 {@code TraceIdFilter} 가 넣어 두었으므로 정상 흐름에서는 항상 있습니다.
     * 없는 경우(배치·테스트)에 not null 제약으로 기록을 실패시키는 건 과합니다 —
     * 이어 붙일 상대가 없을 뿐 기록 자체는 남아야 합니다.
     */
    private String resolveTraceId() {
        String traceId = MdcContext.traceId();
        return traceId != null ? traceId : MdcContext.newTraceId();
    }
}