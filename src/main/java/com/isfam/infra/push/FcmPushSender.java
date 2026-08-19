package com.isfam.infra.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 실제 FCM 발송. {@code isfam.push.enabled=true} 일 때만 빈으로 등록됩니다
 * ({@link PushConfig} 참고).
 *
 * <p><b>{@code priority=HIGH} 가 필수입니다.</b> Doze 모드에서 normal 우선순위 메시지는
 * 유지보수 창까지 최대 수 시간 지연됩니다. 동기화 푸시가 그만큼 늦으면 강퇴당한 사람 폰에
 * 남의 임베딩이 그대로 남아 있게 됩니다.
 *
 * <p>여기서 던지는 예외는 없습니다. 푸시 실패는 로그로 남기고 넘어갑니다 —
 * 앱에는 WorkManager 6시간 주기 동기화라는 안전망이 있고, 푸시 실패가 호출부로
 * 전파되면 이미 커밋된 트랜잭션 뒤에서 의미 없는 스택트레이스만 쌓입니다.
 */
@Slf4j
@RequiredArgsConstructor
public class FcmPushSender implements PushSender {

    /** FCM multicast 한 번에 보낼 수 있는 토큰 상한. 가족 8명 기준 걸릴 일은 없지만 방어입니다 */
    private static final int BATCH_SIZE = 500;

    private final FirebaseMessaging messaging;

    @Override
    public List<String> sendData(List<String> tokens, Map<String, String> data) {
        return send(tokens, null, data);
    }

    @Override
    public List<String> sendNotification(List<String> tokens, String title, String body,
                                         Map<String, String> data) {
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();
        return send(tokens, notification, data);
    }

    private List<String> send(List<String> tokens, Notification notification,
                              Map<String, String> data) {
        List<String> invalidTokens = new ArrayList<>();

        for (int start = 0; start < tokens.size(); start += BATCH_SIZE) {
            List<String> chunk = tokens.subList(start, Math.min(start + BATCH_SIZE, tokens.size()));

            MulticastMessage.Builder builder = MulticastMessage.builder()
                    .addAllTokens(chunk)
                    .putAllData(data)
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build());

            if (notification != null) {
                builder.setNotification(notification);
            }

            try {
                BatchResponse response = messaging.sendEachForMulticast(builder.build());
                collectInvalidTokens(chunk, response, invalidTokens);
            } catch (FirebaseMessagingException e) {
                // 배치 전체 실패 — 네트워크·자격증명 문제입니다. 토큰 탓이 아니므로 지우지 않습니다
                log.error("FCM 배치 발송 실패 — {}건 (errorCode={})",
                        chunk.size(), e.getMessagingErrorCode(), e);
            }
        }

        return invalidTokens;
    }

    /**
     * 개별 실패 중 <b>토큰이 죽은 경우만</b> 골라냅니다.
     *
     * <p>{@code UNREGISTERED} 는 앱 삭제·재설치, {@code INVALID_ARGUMENT} 는 형식 오류입니다.
     * 둘 다 재시도해도 영영 성공하지 않으므로 DB 에서 비웁니다.
     * {@code UNAVAILABLE}·{@code INTERNAL} 은 일시적 장애라 <b>지우면 안 됩니다</b> —
     * 여기서 지우면 FCM 장애 한 번에 전 사용자의 푸시 토큰이 날아갑니다.
     */
    private void collectInvalidTokens(List<String> chunk, BatchResponse response,
                                      List<String> invalidTokens) {
        List<SendResponse> responses = response.getResponses();

        for (int i = 0; i < responses.size(); i++) {
            SendResponse each = responses.get(i);
            if (each.isSuccessful()) {
                continue;
            }

            FirebaseMessagingException exception = each.getException();
            MessagingErrorCode errorCode = exception == null ? null : exception.getMessagingErrorCode();

            if (errorCode == MessagingErrorCode.UNREGISTERED
                    || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                invalidTokens.add(chunk.get(i));
            } else {
                log.warn("FCM 개별 발송 실패 (errorCode={}) — 토큰은 유지합니다", errorCode);
            }
        }
    }
}