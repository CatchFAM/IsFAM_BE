package com.isfam.infra.push;

import com.isfam.domain.device.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * FCM 이 "이 토큰은 죽었다"고 알려준 것만 비웁니다.
 *
 * <p>별도 컴포넌트로 뺀 이유는 {@link PushDispatcher} 의 핸들러가 {@code @Async} +
 * {@code @TransactionalEventListener} 라 트랜잭션이 없기 때문입니다. {@code @Modifying}
 * 쿼리는 트랜잭션 없이는 실행되지 않고, 같은 메서드에 {@code @Transactional} 을 하나 더
 * 얹으면 프록시가 3중으로 겹칩니다. 쓰기만 분리하는 게 단순합니다.
 *
 * <p><b>row 를 지우지 않고 {@code push_token} 만 null 로 만듭니다.</b> 단말 row 에는
 * 권한 스냅샷·통화녹음 지원 여부·동기화 시각이 함께 들어 있어서, 지우면 사용자가 앱을
 * 다시 열었을 때 온보딩을 처음부터 다시 하게 됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvalidPushTokenCleaner {

    private final DeviceRepository deviceRepository;

    @Transactional
    public void clear(List<String> invalidTokens) {
        if (invalidTokens == null || invalidTokens.isEmpty()) {
            return;
        }
        int cleared = deviceRepository.clearPushTokens(invalidTokens);
        log.info("무효 푸시 토큰 정리 — {}건", cleared);
    }
}