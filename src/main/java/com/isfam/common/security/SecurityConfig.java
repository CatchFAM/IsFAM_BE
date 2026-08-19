package com.isfam.common.security;

import com.isfam.common.error.ErrorCode;
import com.isfam.common.error.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 무상태(JWT) 시큐리티 설정.
 *
 * <p>비인증 허용 목록은 api-statement.md 의 "권한" 열이 '-' 인 API 와 1:1로 맞췄습니다.
 * 빠뜨리기 쉬운 두 가지:
 * <ul>
 *   <li>{@code PUT /auth/password} — 비밀번호를 잊은 상태라 access_token 이 없습니다.
 *       인증 경로로 두면 재설정이 영원히 안 됩니다</li>
 *   <li>{@code GET /invitations/&#123;code&#125;} 는 비인증, {@code POST .../accept} 는 인증.
 *       패턴 {@code /invitations/*} 는 단일 세그먼트만 매칭하므로 accept 는 걸리지 않습니다</li>
 * </ul>
 *
 * <p>owner 권한(FAMILY_003)은 여기가 아니라 서비스 계층에서 처리합니다.
 * DB 를 봐야 판단되는 조건이라 URL 패턴으로는 결정되지 않습니다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    private static final String[] PUBLIC_GET = {
            "/health", "/model-info", "/invitations/*"
    };
    private static final String[] PUBLIC_POST = {
            "/auth/phone/send", "/auth/phone/verify", "/auth/signup", "/auth/login", "/auth/refresh"
    };
    private static final String[] PUBLIC_PUT = {
            "/auth/password"
    };
    private static final String[] DOCS = {
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        // 토큰 없이 보호 API 접근
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponse.write(request, response, ErrorCode.AUTH_009))
                        // 인증은 됐는데 인가에서 막힘
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponse.write(request, response, ErrorCode.COMMON_003)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.PUT, PUBLIC_PUT).permitAll()
                        .requestMatchers(DOCS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}