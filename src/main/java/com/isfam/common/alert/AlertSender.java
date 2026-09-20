package com.isfam.common.alert;

/**
 * 운영 알람 발송 (P-21).
 *
 * <p><b>인터페이스만 Phase 11 에서 만들고 수신처는 Phase 15 에서 붙입니다.</b>
 * 인프라 설계 계획의 결정입니다 — Slack Webhook URL 은 설정값으로 비워두고,
 * 미설정이면 no-op + WARN 1회. Phase 11 의 완료 기준은 Actuator 헬스체크 DOWN 으로
 * 검증하므로 인프라 없이 닫힙니다.
 *
 * <p><b>여기로 개인정보를 넘기지 마세요.</b> 이 경로는 리전 밖(Slack, 미국)으로
 * 나가는 유일한 경로입니다. 전화번호·이름·임베딩·오디오 경로·요청 본문이 알람 문구에
 * 들어가면 그 순간 고지 없는 국외 이전이 됩니다(L8-b). 알람에 담아도 되는 것은
 * <b>건수·테이블명·디렉터리명·에러 종류</b>까지입니다.
 *
 * <p>구현체는 예외를 밖으로 던지지 않습니다. 알람 발송 실패가 스케줄러를 멈추면
 * 정작 감시해야 할 지표를 못 보게 됩니다 — 알람은 관측 수단이지 처리 로직이 아닙니다.
 */
public interface AlertSender {

    /**
     * 이상 상태 알림. 같은 {@code key} 가 계속 이상이면 구현체가 재발송 주기로 묶습니다.
     *
     * @param key     알람 식별자. 사람이 읽을 수 있는 고정 문자열
     *                (예: {@code audio.purge.missing}). <b>값을 넣지 마세요</b> —
     *                key 마다 재발송 주기가 따로 잡혀 알람이 폭주합니다
     * @param summary 한 줄 요약. <b>개인정보 금지</b>
     */
    void fire(String key, String summary);

    /**
     * 해소 알림. {@link #fire} 된 적 없는 key 면 아무것도 하지 않습니다 —
     * 기동할 때마다 "정상입니다" 알람이 오면 아무도 안 보게 됩니다.
     */
    void resolve(String key, String summary);
}