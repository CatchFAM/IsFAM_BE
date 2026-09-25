package com.isfam.common.security;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorResponse;
import com.isfam.common.log.MdcContext;
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
 *
 * <p><b>Phase 12 — 인증에 성공한 직후 {@code userId} 를 MDC 에 싣습니다.</b>
 * 여기가 서버가 "누구의 요청인지" 처음 아는 지점이라 다른 자리가 없습니다
 * ({@code TraceIdFilter} 는 이보다 바깥이라 토큰을 아직 보지 못했습니다).
 * 이후 그 요청이 찍는 모든 로그에 userId 가 따라붙어, {@code embedding_access_logs} 같은
 * 기록 테이블의 row 와 로그를 맞춰 볼 수 있습니다. <b>userId 외에 아무것도 넣지 마세요</b> —
 * 허용 키와 그 이유는 {@link MdcContext} 에 있습니다.
 *
 * <p>MDC 를 비우는 건 {@code TraceIdFilter} 의 finally 입니다. 여기서 비우면
 * 응답을 쓰는 동안의 로그에서 컨텍스트가 사라집니다.
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

            MdcContext.put(MdcContext.USER_ID, String.valueOf(payload.userId()));

        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            ErrorResponse.write(request, response, e.getErrorCode(), e.getStatus(), e.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }
}