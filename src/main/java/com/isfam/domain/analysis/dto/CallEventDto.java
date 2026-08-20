package com.isfam.domain.analysis.dto;

import com.isfam.domain.analysis.entity.CallEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * {@code /call-events} 의 요청·응답.
 *
 * <p><b>여기에 통화 상대방 정보를 추가하지 마세요.</b> 전화번호는 식별정보라
 * 서버로 보내면 안 됩니다(legal-compliance.md 3장). 앱이 화면에 이름을 띄우고 싶으면
 * 로컬 DB에서 해결해야 합니다.
 */
public final class CallEventDto {

    private CallEventDto() {
    }

    // ── 등록 ────────────────────────────────────────────────

    /**
     * {@code detected_at} 은 <b>앱이 녹음 파일 생성을 감지한 시각</b>입니다.
     * 서버 수신 시각과 다를 수 있습니다 — 비행기 모드나 데이터 오프 상태에서
     * 분석이 끝나고 나중에 한꺼번에 올라오는 경우가 있어서, 서버 시각으로 덮으면
     * 히스토리 순서가 실제 통화 순서와 어긋납니다.
     */
    public record CreateRequest(
            @NotNull(message = "detected_at 이 필요해요")
            Instant detectedAt
    ) {
    }

    public record CreateResponse(
            Long callEventId,
            CallEvent.LocalAnalysisStatus localAnalysisStatus
    ) {
        public static CreateResponse from(CallEvent callEvent) {
            return new CreateResponse(callEvent.getId(), callEvent.getLocalAnalysisStatus());
        }
    }

    // ── 상태 갱신 ───────────────────────────────────────────

    /**
     * {@code PUT /call-events/&#123;id&#125;} — <b>{@code skipped}·{@code failed} 만</b> 허용됩니다.
     *
     * <p>{@code analyzed_on_device} 는 이 API 로 보내지 않습니다(명세 [정책]).
     * {@code POST /voice-analyses} 가 성공하면 서버가 같은 트랜잭션에서 상태와
     * {@code voice_analysis_id} 를 함께 갱신합니다. 앱이 양쪽을 다 부르면
     * 중간에 끊겼을 때 상태가 어긋납니다.
     *
     * <p>enum 으로 직접 바인딩하지 않고 문자열로 받는 이유는, 허용되지 않는 값이 왔을 때
     * "이 값은 서버가 정한다"는 안내를 낼 수 있게 하기 위해서입니다.
     * 타입 변환에 맡기면 일반적인 COMMON_001 만 나가고 앱 개발자는 왜 막혔는지 모릅니다.
     */
    public record UpdateRequest(
            @NotBlank(message = "local_analysis_status 가 필요해요")
            String localAnalysisStatus
    ) {
    }

    public record UpdateResponse(
            Long callEventId,
            CallEvent.LocalAnalysisStatus localAnalysisStatus
    ) {
        public static UpdateResponse from(CallEvent callEvent) {
            return new UpdateResponse(callEvent.getId(), callEvent.getLocalAnalysisStatus());
        }
    }
}