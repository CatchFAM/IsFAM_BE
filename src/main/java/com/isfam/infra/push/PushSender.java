package com.isfam.infra.push;

import java.util.List;
import java.util.Map;

/**
 * FCM 발송 추상화.
 *
 * <p>인터페이스로 뺀 이유는 <b>서비스 계정 키 없이도 서버가 떠야 하기</b> 때문입니다.
 * {@code firebase-admin} 은 자격증명이 없으면 초기화 단계에서 예외를 던져
 * 애플리케이션 기동 자체가 실패합니다. 로컬·CI·데모에서 그건 과합니다.
 * {@code isfam.push.enabled=false} 면 {@link NoOpPushSender} 가 대신 주입됩니다.
 *
 * <p>두 메서드 모두 <b>전송에 실패한 무효 토큰 목록</b>을 돌려줍니다. 앱 삭제·재설치로
 * 죽은 토큰이 쌓이면 발송할 때마다 전부 실패하고, 그 실패가 정상 단말의 실패와 섞여
 * "푸시가 안 닿는다"는 문제를 진단할 수 없게 됩니다.
 */
public interface PushSender {

    /**
     * data-only 메시지. 알림창에 표시되지 않습니다.
     *
     * <p>{@code family_sync} 전용입니다. <b>{@code devices.notification_enabled} 를 보지 않습니다</b> —
     * 이건 알림이 아니라 임베딩 동기화를 위한 시스템 신호라, 사용자가 알림을 껐다고 막으면
     * 강퇴당한 사람 폰에 남의 생체정보가 남습니다.
     *
     * @return 무효 토큰 목록
     */
    List<String> sendData(List<String> tokens, Map<String, String> data);

    /**
     * 알림창 표시 + data.
     *
     * <p>{@code user_notification} 전용이고 이쪽은 {@code notification_enabled} 를 존중합니다.
     * 대상 필터링은 {@link PushDispatcher} 의 조회 단계에서 이뤄집니다.
     *
     * @return 무효 토큰 목록
     */
    List<String> sendNotification(List<String> tokens, String title, String body,
                                  Map<String, String> data);
}