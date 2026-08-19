// src/main/java/com/isfam/common/security/JwtAuthenticationFilter.java
package com.isfam.common.security;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer &lt;access_token&gt; 을 파싱해 SecurityContext 를 채웁니다.
 *
 * <p>헤더가 없으면 통과시킵니다(비인증 허용 경로가 있으므로). 인증이 필요한 경로였다면
 * 뒤쪽 인가 단계에서 EntryPoint 가 401 을 냅니다. 헤더가 있는데 토큰이 깨진 경우에는
 * 여기서 바로 AUTH_009 를 내려 원인을 분명히 합니다.
 *
 * <p>빈으로 등록하지 않습니다 — Filter 타입 빈은 서블릿 필터로도 자동 등록돼 두 번 실행됩니다.
 * SecurityConfig 에서 직접 생성합니다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtTokenProvider.AccessTokenPayload payload =
                    tokenProvider.parseAccessToken(header.substring(BEARER_PREFIX.length()).trim());

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    new AuthPrincipal(payload.userId(), payload.sessionId()),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            ErrorResponse.write(request, response, e.getErrorCode(), e.getStatus(), e.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }
}