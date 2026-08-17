package com.isfam.common.security;

/**
 * 인증된 사용자. SecurityContext 의 principal.
 *
 * @param sessionId refresh_tokens.id — POST /auth/logout 이 이 값으로 해당 세션만 revoke
 */
public record AuthPrincipal(long userId, Long sessionId) {
}