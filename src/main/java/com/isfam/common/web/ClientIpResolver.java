package com.isfam.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 접속기록에 남길 클라이언트 IP.
 *
 * <p><b>왜 공통으로 뺐나.</b> 고시 제8조는 접속기록에 "접속지"를 요구합니다.
 * Phase 11 까지는 {@code spoof_checks} 한 곳만 IP 를 남겨서
 * {@code SpoofCheckController} 안에 private 메서드로 두어도 됐지만, Phase 12 에서
 * {@code embedding_access_logs} · {@code voiceprint_access_logs} 가 같은 항목을 요구합니다.
 * 복사해 두면 프록시 설정이 바뀔 때 한쪽만 고쳐지고, 그 한쪽은 <b>조용히</b> 틀린 값을
 * 2년간 쌓습니다(P-16).
 *
 * <p><b>이 값은 기록 전용입니다.</b> {@code X-Forwarded-For} 와 {@code X-Real-IP} 는
 * 클라이언트가 마음대로 보낼 수 있습니다. 인증·인가·차단 판단에 쓰면 그 순간
 * 헤더 한 줄로 우회되는 통제가 됩니다.
 *
 * <p><b>Phase 15 의존.</b> 프록시가 이 헤더를 <b>덮어쓰도록</b>(append 가 아니라 set)
 * 설정돼 있어야 첫 항목이 실제 클라이언트가 됩니다. 그 설정이 없으면 여기 남는 IP 는
 * 클라이언트가 원하는 값이고, 접속기록으로서 의미가 없습니다. 인프라 설계 계획의
 * "신뢰 프록시 설정(X-Forwarded-For set)" 항목이 이 클래스의 전제입니다.
 */
public final class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";

    /** IPv6 최대 길이(39) + 여유. 기록 테이블들의 client_ip 컬럼 길이와 맞춥니다 */
    private static final int MAX_LENGTH = 45;

    private ClientIpResolver() {
    }

    /**
     * {@code X-Forwarded-For} → {@code X-Real-IP} → {@code getRemoteAddr()} 순으로 봅니다.
     *
     * <p>{@code X-Forwarded-For} 는 {@code client, proxy1, proxy2} 형태라 첫 항목만 씁니다.
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (StringUtils.hasText(forwarded)) {
            return truncate(forwarded.split(",")[0].trim());
        }

        String realIp = request.getHeader(HEADER_REAL_IP);
        if (StringUtils.hasText(realIp)) {
            return truncate(realIp.trim());
        }

        return truncate(request.getRemoteAddr());
    }

    /**
     * 현재 요청에서 IP 를 꺼냅니다. 요청 밖(배치·{@code @Async})이면 null 입니다.
     *
     * <p><b>컨트롤러에서 {@link #resolve(HttpServletRequest)} 를 쓸 수 있으면 그쪽이 낫습니다</b> —
     * 의존성이 파라미터로 드러나고 테스트에서 가짜 요청을 넣기 쉽습니다. 이 메서드는
     * 서비스 계층 깊은 곳에서 기록을 남겨야 하는데 {@code HttpServletRequest} 를
     * 거기까지 끌고 내려가는 게 더 지저분할 때만 쓰세요.
     *
     * <p>null 을 예외로 바꾸지 않은 이유는 배치에서도 같은 기록 코드를 재사용하기
     * 때문입니다 — 파기 배치에는 접속지가 없는 게 정상이고(수행 주체가 서버),
     * 그때 {@code client_ip} 는 null 로 남습니다.
     */
    public static String resolveCurrent() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return resolve(servletAttributes.getRequest());
        }
        return null;
    }

    private static String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}