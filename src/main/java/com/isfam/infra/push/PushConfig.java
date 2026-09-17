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
import org.springframework.context.annotation.Profile;
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
 *
 * <p><b>단 운영은 예외입니다.</b> {@link #noOpPushSender()} 에 {@code @Profile("!prod")} 가
 * 걸려 있어 prod 에서는 NoOp 이 등록되지 않습니다. prod + {@code enabled=false} 조합이면
 * {@code PushSender} 빈이 없어 기동이 실패합니다 — 의도한 동작입니다.
 * 푸시가 안 나가는 걸 몇 시간 뒤 사용자 문의로 알게 되는 것보다, 배포 시점에
 * 부팅이 안 되는 쪽이 낫습니다. 운영에서 푸시를 정말 끄고 싶으면
 * {@code isfam.push.enabled=true} 로 두고 FCM 쪽에서 막으세요.
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

    /**
     * <b>{@code @Profile("!prod")} 를 지우지 마세요.</b> 클래스 javadoc 참고 —
     * 이게 빠지면 운영에서 푸시가 조용히 전부 사라지는 상태로 정상 기동합니다.
     */
    @Bean
    @Profile("!prod")
    @ConditionalOnProperty(name = "isfam.push.enabled", havingValue = "false", matchIfMissing = true)
    public PushSender noOpPushSender() {
        log.warn("FCM 비활성 (isfam.push.enabled=false) — 동기화·알림 푸시가 발송되지 않습니다. "
                + "앱은 6시간 주기 동기화로만 갱신됩니다");
        return new NoOpPushSender();
    }
}