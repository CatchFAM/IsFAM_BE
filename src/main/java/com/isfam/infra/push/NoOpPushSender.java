package com.isfam.infra.push;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 발송하지 않고 로그만 남깁니다. {@code isfam.push.enabled=false}(기본값)일 때 주입됩니다.
 *
 * <p>Firebase 서비스 계정 키(JSON)가 없으면 {@code firebase-admin} 초기화가 실패해
 * 서버가 기동조차 못 합니다. 로컬 개발·CI·데모에서 그걸 강제하지 않으려는 구현체입니다.
 * 키를 받으면 {@code isfam.push.enabled=true} 와 {@code credentials-path} 만 채우면 되고,
 * 호출부 코드는 한 줄도 바뀌지 않습니다.
 *
 * <p><b>운영에서 이 구현체가 뜨면 푸시가 전부 조용히 사라집니다.</b> 기동 로그에서
 * "푸시 비활성" 경고를 확인하세요 — 앱은 6시간 주기 동기화로 버티므로 증상이 늦게 나타납니다.
 */
@Slf4j
public class NoOpPushSender implements PushSender {

    @Override
    public List<String> sendData(List<String> tokens, Map<String, String> data) {
        log.info("[푸시 비활성] data-only {}건 — {}", tokens.size(), data);
        return List.of();
    }

    @Override
    public List<String> sendNotification(List<String> tokens, String title, String body,
                                         Map<String, String> data) {
        log.info("[푸시 비활성] 알림 {}건 — \"{}\" / {}", tokens.size(), title, data);
        return List.of();
    }
}