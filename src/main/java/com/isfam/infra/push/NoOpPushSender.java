package com.isfam.infra.push;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 발송하지 않고 건수만 남깁니다. {@code isfam.push.enabled=false}(기본값)일 때 주입됩니다.
 *
 * <p>Firebase 서비스 계정 키(JSON)가 없으면 {@code firebase-admin} 초기화가 실패해
 * 서버가 기동조차 못 합니다. 로컬 개발·CI·데모에서 그걸 강제하지 않으려는 구현체입니다.
 * 키를 받으면 {@code isfam.push.enabled=true} 와 {@code credentials-path} 만 채우면 되고,
 * 호출부 코드는 한 줄도 바뀌지 않습니다.
 *
 * <p><b>운영에서는 이 빈이 아예 등록되지 않습니다.</b> {@code PushConfig} 의 팩토리 메서드에
 * {@code @Profile("!prod")} 가 걸려 있어, prod 에서 {@code isfam.push.enabled=false} 로 뜨면
 * {@code PushSender} 빈이 없어 기동이 실패합니다. 푸시가 조용히 사라지는 사고를
 * 부팅 실패로 앞당기는 장치입니다 — 앱은 6시간 주기 동기화로 버티므로 증상이 늦게 나타납니다.
 *
 * <p><b>{@code data}·{@code title}·{@code body} 를 로그에 찍지 마세요.</b>
 * {@code Notification.memberJoined()} 의 본문에는 실명({@code display_name})이 들어가고,
 * {@code data} 에는 {@code userId}·{@code familyId} 가 실립니다. 로컬 전용이라도
 * 같은 코드가 스테이징에서 돌면 그대로 개인정보가 로그 파일에 쌓입니다
 * (legal-compliance.md L9-f · P-24).
 */
@Slf4j
public class NoOpPushSender implements PushSender {

    @Override
    public List<String> sendData(List<String> tokens, Map<String, String> data) {
        log.info("[푸시 비활성] data-only {}건 (키 {}개) — 발송하지 않음",
                tokens.size(), data == null ? 0 : data.size());
        return List.of();
    }

    @Override
    public List<String> sendNotification(List<String> tokens, String title, String body,
                                         Map<String, String> data) {
        log.info("[푸시 비활성] 알림 {}건 (키 {}개) — 발송하지 않음",
                tokens.size(), data == null ? 0 : data.size());
        return List.of();
    }
}