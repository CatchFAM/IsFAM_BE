package com.isfam.common.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MDC 화이트리스트를 테스트로 고정합니다.
 *
 * <p>Phase 12 완료 기준의 "MDC 허용은 traceId·userId·deviceId 만" 항목이 이 파일입니다.
 * 리뷰로만 지키면 언젠가 디버깅 중에 {@code phoneNumber} 가 들어가고, 그 상태로 배포되면
 * 그 요청의 로그 전부가 개인정보 기록이 됩니다.
 */
class MdcContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("허용된 세 키는 MDC 에 들어간다")
    void allowedKeys() {
        MdcContext.put(MdcContext.TRACE_ID, "abcdef1234567890");
        MdcContext.put(MdcContext.USER_ID, "42");
        MdcContext.put(MdcContext.DEVICE_ID, "7");

        assertThat(MDC.get("traceId")).isEqualTo("abcdef1234567890");
        assertThat(MDC.get("userId")).isEqualTo("42");
        assertThat(MDC.get("deviceId")).isEqualTo("7");
    }

    @Test
    @DisplayName("금지 키는 조용히 버려진다 — 예외로 요청을 죽이지는 않는다")
    void forbiddenKeysAreDropped() {
        MdcContext.put("phoneNumber", "01012345678");
        MdcContext.put("displayName", "홍길동");
        MdcContext.put("embedding", "AAECAwQ=");
        MdcContext.put("audioPath", "/dev/shm/isfam/abc.wav");

        assertThat(MDC.get("phoneNumber")).isNull();
        assertThat(MDC.get("displayName")).isNull();
        assertThat(MDC.get("embedding")).isNull();
        assertThat(MDC.get("audioPath")).isNull();
    }

    @Test
    @DisplayName("빈 값은 넣지 않는다")
    void blankValueIsIgnored() {
        MdcContext.put(MdcContext.USER_ID, "  ");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("개행은 치환된다 — 로그 한 줄이 쪼개지면 이후 파싱이 전부 어긋난다")
    void newlineIsSanitized() {
        MdcContext.put(MdcContext.TRACE_ID, "abcdef12\n00000000");
        assertThat(MDC.get("traceId")).doesNotContain("\n");
    }

    @Test
    @DisplayName("64자를 넘기면 잘린다")
    void longValueIsTruncated() {
        MdcContext.put(MdcContext.TRACE_ID, "a".repeat(200));
        assertThat(MDC.get("traceId")).hasSize(64);
    }

    @Test
    @DisplayName("clear 는 스레드에 남은 컨텍스트를 전부 비운다 — 스레드 재사용 대비")
    void clearRemovesEverything() {
        MdcContext.put(MdcContext.TRACE_ID, "abcdef1234567890");
        MdcContext.put(MdcContext.USER_ID, "42");

        MdcContext.clear();

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("newTraceId 는 32자 hex 이고 매번 다르다")
    void newTraceId() {
        String first = MdcContext.newTraceId();
        String second = MdcContext.newTraceId();

        assertThat(first).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(first).isNotEqualTo(second);
    }
}