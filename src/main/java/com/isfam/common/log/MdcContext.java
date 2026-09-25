package com.isfam.common.log;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;

/**
 * 로그 컨텍스트(MDC)에 넣을 수 있는 값을 <b>세 개로 고정</b>합니다 — traceId · userId · deviceId.
 *
 * <p><b>왜 화이트리스트인가.</b> MDC 는 한 번 넣으면 그 스레드가 찍는 모든 로그 줄에
 * 자동으로 따라붙습니다. 편해서 자꾸 늘리게 되는데, 여기에 {@code phoneNumber} 나
 * {@code displayName} 을 한 번 넣으면 그 요청의 로그 전부가 개인정보 기록이 됩니다.
 * 로그 파일은 DB 보다 접근 통제도 파기 주기도 느슨해서(P-24 와 같은 이유) 노출 위험이 더 큽니다.
 * 금지 항목은 다음과 같고, 예외는 없습니다.
 * <ul>
 *   <li>{@code phoneNumber} · {@code userName} · {@code displayName} — 식별정보</li>
 *   <li>임베딩(base64 조각 포함) — 생체인식 특징정보. 조각도 안 됩니다</li>
 *   <li>오디오 파일 경로·파일명 — tmpfs 경로가 로그에 남으면 파기 통제의 의미가 줄어듭니다</li>
 * </ul>
 *
 * <p><b>허용한 셋은 왜 괜찮은가.</b> {@code userId} 와 {@code deviceId} 는 내부 일련번호라
 * 그 자체로는 사람을 지목하지 못하고, DB 를 봐야 연결됩니다. 접속기록(고시 제8조)이 요구하는
 * "정보주체" 항목도 이 형태이고, 법적 기록의 본체는 로그가 아니라 {@code spoof_checks} ·
 * {@code embedding_access_logs} 같은 테이블입니다(Phase 12). 로그의 역할은 그 테이블의 row 와
 * 같은 요청을 이어 붙이는 것뿐이라 {@code traceId} 하나면 충분합니다.
 *
 * <p><b>넣는 경로를 여기 하나로 좁힙니다.</b> {@code MDC.put()} 을 코드 곳곳에서 직접 부르면
 * 위 규칙을 리뷰로만 지켜야 합니다. 이 클래스를 거치면 허용되지 않은 키가 <b>조용히 버려지고</b>
 * 경고가 남습니다 — 예외를 던지지 않는 이유는, 잘못된 로깅 때문에 요청이 500 으로 죽는 쪽이
 * 더 나쁘기 때문입니다. 값은 경고에도 찍지 않습니다(그 값이 문제라서 막은 건데 경고로 흘리면
 * 같은 일이 됩니다).
 *
 * <p>비우는 책임은 {@link TraceIdFilter} 에 있습니다. HTTP 스레드는 재사용되므로,
 * 지우지 않으면 다음 요청의 로그에 이전 요청의 traceId 가 붙습니다.
 */
@Slf4j
public final class MdcContext {

    /** 요청 하나를 식별합니다. spoof_checks.trace_id 와 같은 값입니다(12-5) */
    public static final String TRACE_ID = "traceId";

    /** 인증된 사용자. JwtAuthenticationFilter 가 토큰 파싱 직후 넣습니다 */
    public static final String USER_ID = "userId";

    /** X-Device-Id 헤더. 값이 숫자가 아니면 넣지 않습니다 */
    public static final String DEVICE_ID = "deviceId";

    private static final Set<String> ALLOWED = Set.of(TRACE_ID, USER_ID, DEVICE_ID);

    /** 외부에서 받은 trace id 에 허용하는 형태. 로그 인젝션(개행·제어문자) 차단용입니다 */
    private static final int MAX_VALUE_LENGTH = 64;

    private MdcContext() {
    }

    /**
     * 허용 키만 MDC 에 넣습니다. 그 외에는 버리고 경고 한 줄을 남깁니다.
     *
     * <p>값이 비어 있으면 아무 일도 하지 않습니다 — 빈 문자열을 넣으면 로그 패턴에
     * {@code [,,]} 같은 빈 자리가 생겨 읽기만 나빠집니다.
     */
    public static void put(String key, String value) {
        if (!ALLOWED.contains(key)) {
            // 값은 절대 찍지 않습니다. 막으려던 게 그 값입니다.
            log.warn("허용되지 않은 MDC 키 — 무시합니다: {}", key);
            return;
        }
        if (!StringUtils.hasText(value)) {
            return;
        }
        MDC.put(key, truncate(value));
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    /**
     * 현재 요청의 trace id. 없으면 null 입니다.
     *
     * <p>배치·스케줄러 스레드에서는 null 이 정상입니다 — MDC 는 스레드 로컬이라
     * {@code @Async} 나 {@code @Scheduled} 로 넘어가면 따라가지 않습니다. 그런 자리에서는
     * {@link #newTraceId()} 로 새로 만들어 작업 단위에 붙이세요(Phase 13 파기 배치가 이 형태입니다).
     */
    public static String traceId() {
        return MDC.get(TRACE_ID);
    }

    /** 32자 hex. 하이픈을 빼는 건 로그 한 줄의 폭을 줄이려는 것뿐입니다 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 스레드에 남은 컨텍스트를 전부 비웁니다.
     *
     * <p>HTTP 스레드는 풀에서 재사용됩니다. 비우지 않으면 다음 요청이 <b>남의 userId</b> 를
     * 달고 로그를 찍습니다 — 접속기록을 만들자고 한 일이 기록을 오염시키는 결과가 됩니다.
     */
    public static void clear() {
        MDC.clear();
    }

    private static String truncate(String value) {
        String sanitized = value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH);
        // 개행이 들어오면 로그 한 줄이 두 줄로 쪼개져 이후 파싱이 전부 어긋납니다.
        return sanitized.replace('\n', '_').replace('\r', '_');
    }
}