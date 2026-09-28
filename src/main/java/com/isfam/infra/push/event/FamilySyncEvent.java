package com.isfam.infra.push.event;

import java.time.Instant;
import java.util.Set;

/**
 * 가족 구성에 변화가 생겼음을 알리는 <b>data-only</b> 푸시 이벤트.
 * 알림창에는 아무것도 표시되지 않고 앱만 깨어나 {@code GET /family/embeddings} 를 부릅니다.
 *
 * <p><b>{@code targetUserIds} 는 상태 변경 <i>전에</i> 수집해야 합니다.</b>
 * 바꾼 뒤에 조회하면 나간 사람·강퇴당한 사람이 목록에서 빠져, 그 폰에 남의 생체정보가
 * 최대 6시간 그대로 남습니다. service-flow.md 12장이 자주 틀리는 지점으로 꼽는 항목입니다.
 *
 * <p>이 이벤트는 {@code AFTER_COMMIT} 에서만 소비됩니다. 트랜잭션 안에서 FCM 을 부르면
 * 외부 I/O 가 DB 락을 잡고, 롤백된 변경에 대해 푸시가 나가고, 반대로 FCM 장애로
 * 멤버십 가입이 통째로 롤백됩니다.
 */
public record FamilySyncEvent(
        Set<Long> targetUserIds,
        Reason reason,
        Long familyId,
        Instant occurredAt
) {

    /**
     * 앱은 <b>{@code reason} 과 무관하게 항상 {@code GET /family/embeddings} 를 호출</b>합니다.
     * 푸시 내용만 믿고 로컬을 지우면 안 됩니다 — 이 값은 로그·디버깅용에 가깝습니다.
     *
     * <p>ERD 초안에 있던 {@code family_deleted} 는 뺐습니다. {@code DELETE /family} 는
     * owner 혼자 남았을 때만 가능해서 발송 대상이 항상 0명이고, owner 본인 폰은
     * 다음 {@code GET /family/embeddings} 에서 404(FAMILY_002)를 받아 스스로 정리합니다.
     * 쏠 일이 없는 값을 열어두면 언젠가 누가 씁니다.
     */
    public enum Reason {
        /** {@code POST /me/voiceprint} (Phase 6) */
        VOICEPRINT_UPDATED,
        /**
         * {@code DELETE /me/voiceprint} (Phase 14, P-18).
         *
         * <p>앱 동작은 다른 값과 같습니다 — {@code GET /family/embeddings} 를 부르면 이 사람이
         * {@code removed_user_ids} 로 내려와 로컬에서 지워집니다. UPDATED 와 나눈 건
         * 발송 로그에서 "등록"과 "삭제"를 구분하기 위해서입니다.
         */
        VOICEPRINT_DELETED,
        /** {@code POST /invitations/&#123;code&#125;/accept} */
        MEMBER_JOINED,
        /** {@code DELETE /family/members/&#123;user_id&#125;}, {@code DELETE /auth/me} (Phase 9) */
        MEMBER_REMOVED;

        public String toPayload() {
            return name().toLowerCase();
        }
    }
}