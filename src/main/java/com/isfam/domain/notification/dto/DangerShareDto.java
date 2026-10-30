package com.isfam.domain.notification.dto;

import jakarta.validation.constraints.NotNull;

/** {@code /notifications/danger-share} 요청·응답 */
public final class DangerShareDto {

    private DangerShareDto() {
    }

    /**
     * {@code POST /notifications/danger-share}.
     *
     * <p>{@code impersonated_user_id} 와 {@code phone_number} 는 정확히 하나만 보냅니다.
     * <ul>
     *   <li>걸려온 번호가 가족 번호(GET /family 의 phone_number)와 같음 → impersonated_user_id</li>
     *   <li>모르는 번호 → phone_number</li>
     * </ul>
     * 받는 사람은 보내지 않습니다 — 항상 공간 멤버 전원(본인 제외)이고 서버가 정합니다.
     *
     * <p><b>{@code toString()} 을 덮어썼습니다.</b> 레코드 기본 toString 은 모든 필드를 찍어서,
     * 누가 이 객체를 로그에 넘기는 순간 의심 번호가 남습니다.
     */
    public record CreateRequest(
            @NotNull(message = "analysis_id 는 필수예요")
            Long analysisId,
            Long impersonatedUserId,
            String phoneNumber
    ) {
        @Override
        public String toString() {
            return "CreateRequest[analysisId=" + analysisId
                    + ", impersonatedUserId=" + impersonatedUserId
                    + ", hasPhoneNumber=" + (phoneNumber != null) + "]";
        }
    }

    /** 201 */
    public record CreateResponse(Long shareId) {
    }
}