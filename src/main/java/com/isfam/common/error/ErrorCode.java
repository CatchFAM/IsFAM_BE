package com.isfam.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드. api-statement.md 의 코드표 + 구현상 필요한 [확장] 코드.
 *
 * <p>AUTH_006 / INVITE_001 처럼 상황에 따라 상태가 갈리는 항목은 여기 값이 기본이고,
 * {@code new BusinessException(code, HttpStatus.GONE)} 으로 덮어씁니다.
 */
@Getter
public enum ErrorCode {

    AUTH_001(HttpStatus.UNAUTHORIZED, "가입되지 않은 번호예요"),
    AUTH_002(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요"),
    AUTH_003(HttpStatus.CONFLICT, "이미 가입된 번호예요"),
    AUTH_004(HttpStatus.UNPROCESSABLE_ENTITY, "인증번호가 맞지 않아요"),
    AUTH_005(HttpStatus.TOO_MANY_REQUESTS, "인증번호 발송 횟수를 초과했어요"),
    AUTH_006(HttpStatus.UNAUTHORIZED, "인증이 만료됐어요. 처음부터 다시 진행해 주세요"), // 만료된 인증 요청이면 410
    AUTH_007(HttpStatus.UNPROCESSABLE_ENTITY, "필수 약관에 동의해 주세요"),
    AUTH_008(HttpStatus.UNAUTHORIZED, "비밀번호가 맞지 않아요"),
    AUTH_009(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"), // [확장] access_token 누락·만료·위변조
    /**
     * 만 14세 미만 가입 차단 (Phase 14, P-08). {@code age_over_14} 누락·false.
     *
     * <p>AUTH_007(약관 미동의)과 나눈 이유 — 약관은 체크하면 통과하지만 이건 재시도로
     * 풀리는 오류가 아닙니다. 앱이 "동의해 주세요"로 되돌리지 않고 가입 불가 안내로 분기해야 합니다.
     */
    AUTH_010(HttpStatus.UNPROCESSABLE_ENTITY, "만 14세 미만은 가입할 수 없어요"),

    FAMILY_001(HttpStatus.NOT_FOUND, "해당 구성원을 찾을 수 없어요"),
    FAMILY_002(HttpStatus.NOT_FOUND, "소속된 가족 공간이 없어요"),
    FAMILY_003(HttpStatus.FORBIDDEN, "가족 공간 관리자만 할 수 있어요"),
    FAMILY_004(HttpStatus.CONFLICT, "이미 다른 가족 공간에 참여하고 있어요"),
    FAMILY_005(HttpStatus.CONFLICT, "관리자는 공간을 삭제한 뒤에 나갈 수 있어요"),
    FAMILY_006(HttpStatus.CONFLICT, "가족 공간 인원이 가득 찼어요"),
    FAMILY_007(HttpStatus.CONFLICT, "남아 있는 구성원이 있어 공간을 삭제할 수 없어요"),

    INVITE_001(HttpStatus.NOT_FOUND, "만료되었거나 존재하지 않는 초대예요"), // 만료·비활성이면 410
    INVITE_002(HttpStatus.CONFLICT, "이미 이 가족 공간의 구성원이에요"),
    // INVITE_003(음성 공유 동의 필요)은 없앴습니다 — 음성 공유 동의를 가입 때 받습니다(AUTH_007)

    VOICE_001(HttpStatus.UNPROCESSABLE_ENTITY, "음질이 부족해요. 조용한 곳에서 다시 녹음해 주세요"),
    /**
     * 통화 음성이 상한(6초 · 16kHz mono 기준 약 192KB)을 넘거나 WAV 헤더가 깨진 경우.
     *
     * <p><b>재시도 금지 오류입니다.</b> 같은 파일을 다시 올려도 동일하게 거부되므로
     * 앱은 해당 call_event 를 {@code PUT /call-events} 로 failed 처리하고 종료해야 합니다.
     * VOICE_001(재녹음하면 통과 가능)과 성격이 달라 코드를 나눴습니다.
     */
    VOICE_002(HttpStatus.UNPROCESSABLE_ENTITY, "분석할 수 없는 음성이에요"),

    /**
     * 통화 종료 후 자동 분석이 꺼져 있음 (알림 설정 세분화 5단계).
     *
     * <p>{@code POST /call-events} · {@code POST /call-events/&#123;id&#125;/spoof-check} ·
     * {@code POST /voice-analyses} 가 냅니다. 사용자가 분석 자체를 끈 상태라 <b>재시도 금지</b>입니다.
     * 앱은 설정이 꺼져 있으면 애초에 부르지 않아야 하고, 이 코드를 받으면 그 통화의 분석을 그만둡니다.
     * 409 인 이유 — 요청은 올바르지만 지금 계정 상태와 맞지 않습니다.
     */
    ANALYSIS_001(HttpStatus.CONFLICT, "통화 종료 후 자동 분석이 꺼져 있어요"),

    DEVICE_001(HttpStatus.UNPROCESSABLE_ENTITY, "통화 자동녹음을 지원하지 않는 기기예요"),
    /**
     * [확장] X-Device-Id 누락·형식 오류·타인 단말.
     *
     * <p>401 인 이유는 service-flow.md 12장 "자주 틀리는 지점" 에
     * {@code device_id 없이 X-Device-Id 필요한 API 호출 → 401} 로 명시돼 있어서입니다.
     * 앱의 복구 동작은 POST /devices 재등록입니다.
     */
    DEVICE_002(HttpStatus.UNAUTHORIZED, "단말 정보가 필요해요"),
    MODEL_001(HttpStatus.UNPROCESSABLE_ENTITY, "앱을 최신 버전으로 업데이트해 주세요"),

    // ── 위험 통화 공유 (POST·GET /notifications/danger-share) ──
    /** {@code impersonated_user_id}·{@code phone_number} 가 둘 다 있거나 둘 다 없음 */
    SHARE_001(HttpStatus.BAD_REQUEST, "사칭된 가족과 의심 번호 중 하나만 보내 주세요"),
    /** 본인 분석이 아님 · 사칭된 가족이 같은 공간의 멤버가 아님 */
    SHARE_002(HttpStatus.FORBIDDEN, "공유할 수 없는 통화 기록이에요"),
    /** 같은 분석을 이미 공유함 */
    SHARE_003(HttpStatus.CONFLICT, "이미 가족에게 공유한 통화예요"),
    /** 정상 판정 · 분석 후 24시간 경과 · 공유받을 가족이 없음 */
    SHARE_004(HttpStatus.UNPROCESSABLE_ENTITY, "지금은 이 통화를 공유할 수 없어요"),
    /**
     * 조회 실패 — 없음 · 다른 공간 · 공유 후 들어온 사람 · 7일 경과 · 번호 없는 공유.
     * 이유를 나누지 않습니다. 나누면 남의 공유가 존재하는지 알려주게 됩니다.
     */
    SHARE_005(HttpStatus.NOT_FOUND, "공유된 정보를 찾을 수 없어요"),

    // [확장] 공통
    COMMON_001(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요"),
    COMMON_002(HttpStatus.UNPROCESSABLE_ENTITY, "입력값을 확인해 주세요"),
    COMMON_003(HttpStatus.FORBIDDEN, "접근 권한이 없어요"),
    COMMON_004(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없어요"),
    COMMON_005(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요"),
    COMMON_006(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기가 너무 커요"),
    COMMON_007(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식이에요"),
    COMMON_008(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했어요"),
    COMMON_009(HttpStatus.BAD_GATEWAY, "분석 서버와 통신하지 못했어요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}