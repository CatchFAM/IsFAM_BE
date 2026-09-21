package com.isfam.domain.audit.service;

import com.isfam.common.log.MdcContext;
import com.isfam.domain.audit.entity.EmbeddingAccessLog;
import com.isfam.domain.audit.repository.EmbeddingAccessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 임베딩 제3자 제공을 기록합니다 (P-16).
 *
 * <p><b>기록에 실패하면 제공도 실패해야 합니다.</b> 계획 문서의 요구사항이고, 이유는
 * 둘 중 하나를 골라야 하기 때문입니다 — ① 기록 없이 임베딩이 나간 상태
 * ② 임베딩이 안 나가고 앱이 다음 주기에 재시도하는 상태. ②는 최대 6시간 뒤에 저절로
 * 회복되지만, ①은 열람 요구나 유출 사고가 터진 뒤에야 알게 되고 그때는 복구할 방법이
 * 없습니다. 그래서 ②를 고릅니다.
 *
 * <p><b>그 선택을 {@link Propagation#MANDATORY} 로 강제합니다.</b> 호출자의 트랜잭션에
 * 반드시 올라타므로, 여기서 터지면 임베딩 조회 트랜잭션이 통째로 롤백되고 앱은 500 을
 * 받습니다. {@code REQUIRES_NEW} 로 떼어내면 정확히 반대가 됩니다 — 기록만 실패하고
 * 제공은 성공하는, 우리가 피하려던 상태입니다. 트랜잭션 밖에서 호출하면 예외가 나는데
 * 그것도 의도입니다: 기록이 롤백 단위 밖에 있으면 이 보장이 성립하지 않습니다.
 *
 * <p><b>제공이 0건이면 기록하지 않습니다.</b> 앱은 6시간 주기 + 푸시 + 포그라운드 진입마다
 * 이 API 를 부르는데, 증분이라 대부분의 호출은 members 가 비어 있습니다. 그걸 다 남기면
 * 2년 보관 대상이 "아무 일도 없었다" row 수천만 건이 되고, 정작 제공 이력을 찾을 때
 * 방해만 됩니다. 이 테이블은 <b>호출 기록이 아니라 제공 기록</b>입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingAccessLogger {

    private final EmbeddingAccessLogRepository repository;

    /**
     * @param providedUserIds 이번 응답에 임베딩이 실린 사람들. 비어 있으면 아무 일도 하지 않습니다
     * @param now             호출자가 {@code Clock} 으로 만든 시각. 같은 요청의 다른 기록과
     *                        시각이 어긋나지 않도록 여기서 새로 찍지 않습니다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProvide(Long requesterUserId,
                              Long requesterDeviceId,
                              Long familyId,
                              List<Long> providedUserIds,
                              List<Long> removedUserIds,
                              String embeddingModelVersion,
                              Instant sinceAt,
                              Instant syncedAt,
                              String clientIp,
                              Instant now) {

        if (providedUserIds == null || providedUserIds.isEmpty()) {
            return;
        }

        repository.save(EmbeddingAccessLog.provide(
                resolveTraceId(),
                requesterUserId,
                requesterDeviceId,
                familyId,
                providedUserIds,
                removedUserIds,
                embeddingModelVersion,
                sinceAt,
                syncedAt,
                clientIp,
                now));

        // 건수만 남깁니다. userId 목록을 로그에 풀면 같은 정보가 통제가 느슨한 곳에
        // 한 벌 더 생깁니다 — 이 기록의 본체는 로그가 아니라 DB row 입니다.
        log.info("임베딩 제공 기록 — provided {}건, removed {}건 (requester={}, family={})",
                providedUserIds.size(),
                removedUserIds == null ? 0 : removedUserIds.size(),
                requesterUserId, familyId);
    }

    /**
     * MDC 에 trace id 가 없으면 새로 만듭니다.
     *
     * <p>HTTP 요청이면 {@code TraceIdFilter} 가 이미 넣어 두었으므로 정상 흐름에서는
     * 항상 있습니다. 없는 경우는 배치나 테스트인데, 그때 {@code trace_id} 를 비워
     * <b>not null 제약으로 기록을 실패시키는 건 과합니다</b> — 이어 붙일 상대가 없을 뿐
     * 기록 자체는 남아야 합니다.
     */
    private String resolveTraceId() {
        String traceId = MdcContext.traceId();
        return traceId != null ? traceId : MdcContext.newTraceId();
    }
}