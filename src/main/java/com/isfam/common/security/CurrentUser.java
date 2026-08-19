package com.isfam.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 사용자를 컨트롤러 파라미터로 주입합니다.
 *
 * <pre>
 * public FamilyResponse get(@CurrentUser Long userId) { ... }
 * public void logout(@CurrentUser AuthPrincipal principal) { ... }  // sessionId 가 필요할 때
 * </pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}