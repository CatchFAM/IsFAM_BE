package com.isfam.infra.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 푸시 관련 빈 등록.
 *
 * <p><b>{@code @ConditionalOnMissingBean} 을 쓰지 않았습니다.</b> 그 조건은 빈 등록 순서에
 * 의존해서, 설정 클래스가 하나 더 끼면 조용히 둘 다 등록되거나 둘 다 빠집니다.
 * 대신 같은 프로퍼티를 양쪽에서 반대로 보게 해서 <b>항상 정확히 하나만</b> 뜨도록 했습니다.
 * {@code matchIfMissing = true} 덕분에 프로퍼티를 아예 안 적어도 NoOp 이 뜹니다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    /**
     * {@code FirebaseApp} 은 JVM 전역 싱글턴이라 두 번 초기화하면 예외입니다.
     * 통합 테스트에서 컨텍스트가 여러 번 뜨는 경우를 대비해 기존 인스턴스를 재사용합니다.
     */
    @Bean
    @ConditionalOnProperty(name = "isfam.push.enabled", havingValue = "true")
    public FirebaseApp firebaseApp(PushProperties properties) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        GoogleCredentials credentials;
        if (StringUtils.hasText(properties.credentialsPath())) {
            try (InputStream in = new FileInputStream(properties.credentialsPath())) {
                credentials = GoogleCredentials.fromStream(in);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }

        log.info("FCM 활성화 — 푸시를 실제로 발송합니다");
        return FirebaseApp.initializeApp(
                FirebaseOptions.builder().setCredentials(credentials).build());
    }

    @Bean
    @ConditionalOnProperty(name = "isfam.push.enabled", havingValue = "true")
    public PushSender fcmPushSender(FirebaseApp firebaseApp) {
        return new FcmPushSender(FirebaseMessaging.getInstance(firebaseApp));
    }

    @Bean
    @ConditionalOnProperty(name = "isfam.push.enabled", havingValue = "false", matchIfMissing = true)
    public PushSender noOpPushSender() {
        log.warn("FCM 비활성 (isfam.push.enabled=false) — 동기화·알림 푸시가 발송되지 않습니다. "
                + "앱은 6시간 주기 동기화로만 갱신됩니다");
        return new NoOpPushSender();
    }
}