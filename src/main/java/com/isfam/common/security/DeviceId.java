package com.isfam.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * X-Device-Id 헤더를 Long 으로 주입합니다.
 *
 * <pre>
 * public EmbeddingsResponse sync(@CurrentUser Long userId, @DeviceId Long deviceId) { ... }
 * </pre>
 *
 * <p>헤더가 없거나 숫자가 아니면 DEVICE_002(400). "이 단말이 정말 본인 것인가"는
 * DB 를 봐야 하므로 서비스 계층(Phase 2)에서 검증합니다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeviceId {

    /** 헤더 이름 — GlobalExceptionHandler 도 이 상수를 참조합니다 */
    String HEADER = "X-Device-Id";

    /** false 면 헤더가 없을 때 null 주입 */
    boolean required() default true;
}