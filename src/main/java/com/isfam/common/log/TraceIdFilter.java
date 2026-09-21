package com.isfam.common.log;

import com.isfam.common.security.DeviceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 요청 하나에 trace id 를 부여해 MDC 에 싣습니다.
 *
 * <p><b>Phase 12 의 기반입니다.</b> 이번 Phase 에서 만드는 기록 테이블
 * ({@code embedding_access_logs} · {@code voiceprint_access_logs} · {@code data_deletion_logs})
 * 과 {@code spoof_checks} 는 전부 {@code trace_id} 를 갖습니다. 조사관이
 * "이 임베딩 제공과 이 파기가 같은 요청이었습니까"를 물으면 그 답이 이 값입니다.
 * 로그와 DB row 를 잇는 유일한 고리이기도 합니다.
 *
 * <p><b>필터 순서가 중요합니다.</b> {@link Order} 를 최우선으로 두어 시큐리티 필터 체인보다
 * <i>바깥</i>에서 돕니다. 안쪽에 두면 인증 실패(AUTH_009)처럼 시큐리티 단계에서 끝나는 요청에
 * trace id 가 붙지 않는데, 그런 요청이야말로 나중에 추적할 일이 생깁니다.
 * 자동 등록되는 서블릿 필터라 {@code SecurityConfig} 에 추가하지 않습니다 —
 * 거기 넣으면 체인 <i>안쪽</i>이 되어 순서가 뒤집힙니다.
 *
 * <p><b>클라이언트가 보낸 X-Trace-Id 를 받아줍니다</b>(형식이 맞을 때만). 앱이 재시도하면
 * 같은 trace id 로 묶여 "한 번의 시도가 세 번의 요청이었다"가 보입니다. 다만 외부 입력이므로
 * <ul>
 *   <li>영숫자·하이픈·밑줄 8~64자만 허용합니다 — 개행을 넣어 로그 한 줄을 조작하는
 *       로그 인젝션을 막습니다</li>
 *   <li>형식이 어긋나면 <b>거부하지 않고 새로 만듭니다</b>. 추적용 값 때문에 요청을
 *       실패시킬 이유가 없습니다</li>
 * </ul>
 *
 * <p><b>응답에도 {@code X-Trace-Id} 를 실어 보냅니다.</b> 에러 본문에는 넣지 않았습니다 —
 * {@code ErrorResponse} 형태는 api-statement.md 에 고정돼 있어 필드를 늘리면 앱과 합의가
 * 필요합니다. 헤더는 앱이 무시해도 아무 문제가 없고, 사용자가 문의할 때 개발자 도구나
 * 앱 로그에서 이 값만 받으면 서버 로그를 곧장 특정할 수 있습니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,19}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = resolveTraceId(request);

        try {
            MdcContext.put(MdcContext.TRACE_ID, traceId);
            putDeviceId(request);

            // userId 는 여기서 넣지 않습니다 — 아직 토큰을 파싱하기 전입니다.
            // JwtAuthenticationFilter 가 인증에 성공한 직후에 채웁니다.

            response.setHeader(HEADER, traceId);
            filterChain.doFilter(request, response);

        } finally {
            // 스레드 풀 재사용 때문에 반드시 비웁니다. 예외로 빠져나가도 실행돼야 해서 finally 입니다.
            MdcContext.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (StringUtils.hasText(incoming) && ACCEPTABLE.matcher(incoming.trim()).matches()) {
            return incoming.trim();
        }
        return MdcContext.newTraceId();
    }

    /**
     * {@code X-Device-Id} 는 숫자일 때만 싣습니다.
     *
     * <p>형식 검사는 {@code DeviceIdArgumentResolver} 가 별도로 하고 DEVICE_002 를 냅니다.
     * 여기서는 값의 정당성을 보는 게 아니라 <b>로그에 무엇이 들어가는지</b>만 통제합니다 —
     * 숫자가 아닌 값이 오면 그건 앱 버그이거나 조작 시도이고, 어느 쪽이든 로그에 남길
     * 이유가 없습니다.
     */
    private void putDeviceId(HttpServletRequest request) {
        String raw = request.getHeader(DeviceId.HEADER);
        if (StringUtils.hasText(raw) && DIGITS.matcher(raw.trim()).matches()) {
            MdcContext.put(MdcContext.DEVICE_ID, raw.trim());
        }
    }
}