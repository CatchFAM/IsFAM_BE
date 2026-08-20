package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.dto.CallEventDto;
import com.isfam.domain.analysis.entity.CallEvent;
import com.isfam.domain.analysis.repository.CallEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통화 감지 이벤트 등록 · 상태 갱신 (Phase 8).
 *
 * <p><b>이 서비스는 통화 내용을 전혀 다루지 않습니다.</b> "언제 통화가 있었고
 * 그게 분석됐는가"만 기록합니다. 상대방 번호·이름·통화 시간이 여기로 들어오려 하면
 * 설계가 틀린 겁니다(legal-compliance.md 3장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallEventService {

    private final CallEventRepository callEventRepository;

    /**
     * {@code POST /call-events} — 앱이 녹음 파일 생성을 감지한 직후.
     *
     * <p><b>멱등 처리를 하지 않습니다.</b> 같은 통화가 두 번 감지되면 이벤트도 두 개
     * 생기는데, 그걸 막을 방법이 마땅치 않습니다 — 중복 판정 키로 쓸 만한 게
     * {@code detected_at} 뿐이고, 그건 앱이 보내는 값이라 재시도할 때마다 조금씩 달라집니다.
     * 대신 {@code voice_analyses} 쪽에 유니크 제약을 걸어 <b>분석 결과와 알림은</b>
     * 중복되지 않게 막았습니다. 남는 건 상태가 {@code detected} 인 고아 이벤트뿐이고,
     * 그건 사용자에게 보이지 않습니다.
     */
    @Transactional
    public CallEventDto.CreateResponse create(Long userId, CallEventDto.CreateRequest request) {
        CallEvent saved = callEventRepository.save(
                CallEvent.detect(userId, request.detectedAt()));

        log.debug("통화 이벤트 등록 — callEventId={}, userId={}", saved.getId(), userId);
        return CallEventDto.CreateResponse.from(saved);
    }

    /**
     * {@code PUT /call-events/&#123;id&#125;} — <b>skipped · failed 전용</b>.
     *
     * <p>{@code analyzed_on_device} 는 {@code POST /voice-analyses} 트랜잭션 안에서만
     * 찍힙니다. 앱이 양쪽을 다 호출하면 중간에 끊겼을 때 상태가 어긋나므로
     * 전이 지점을 한쪽으로 통일했습니다(명세 [정책]).
     *
     * <p>이미 분석이 끝난 이벤트에 대한 요청은 <b>거절하지 않고 무시</b>합니다 —
     * 이유는 {@link CallEvent#updateLocalStatus} javadoc 에 있습니다.
     * 남의 이벤트면 404 이고, 존재 여부까지 숨깁니다.
     */
    @Transactional
    public CallEventDto.UpdateResponse updateStatus(Long userId, Long callEventId,
                                                    CallEventDto.UpdateRequest request) {

        CallEvent callEvent = callEventRepository.findByIdAndUserId(callEventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_004,
                        "해당 통화 기록을 찾을 수 없어요"));

        CallEvent.LocalAnalysisStatus status = parseStatus(request.localAnalysisStatus());

        if (!callEvent.updateLocalStatus(status)) {
            log.debug("통화 이벤트 상태 유지 — callEventId={}, 현재={}, 요청={}",
                    callEventId, callEvent.getLocalAnalysisStatus(), status);
        }

        return CallEventDto.UpdateResponse.from(callEvent);
    }

    private CallEvent.LocalAnalysisStatus parseStatus(String raw) {
        try {
            return CallEvent.LocalAnalysisStatus.fromClientUpdate(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_002,
                    "local_analysis_status 는 skipped, failed 중 하나여야 해요. "
                            + "분석 완료 상태는 POST /voice-analyses 가 처리합니다");
        }
    }
}