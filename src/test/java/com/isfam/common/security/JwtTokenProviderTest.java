package com.isfam.common.security;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스프링 컨텍스트 없이 도는 순수 단위 테스트 */
class JwtTokenProviderTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-secret-test-secret-test-secret-0123456789",
            Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(5));

    private final JwtTokenProvider provider = new JwtTokenProvider(PROPERTIES, Clock.systemUTC());

    @Test
    @DisplayName("access 토큰에 userId 와 sessionId(sid)가 실린다")
    void issueAndParseAccessToken() {
        JwtTokenProvider.IssuedToken token = provider.issueAccessToken(1L, 42L);

        JwtTokenProvider.AccessTokenPayload payload = provider.parseAccessToken(token.value());

        assertThat(payload.userId()).isEqualTo(1L);
        assertThat(payload.sessionId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("refresh 토큰을 access 자리에 넣으면 AUTH_009 로 거부한다")
    void rejectTokenTypeMismatch() {
        JwtTokenProvider.IssuedToken refresh = provider.issueRefreshToken(1L);

        assertThatThrownBy(() -> provider.parseAccessToken(refresh.value()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_009);
    }

    @Test
    @DisplayName("만료된 phone_verification_token 은 AUTH_006 + 410 이다")
    void expiredPhoneVerificationTokenIsGone() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        JwtTokenProvider.IssuedToken token =
                new JwtTokenProvider(PROPERTIES, past).issuePhoneVerificationToken("uuid-1", "01012345678");

        assertThatThrownBy(() -> provider.parsePhoneVerificationToken(token.value()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AUTH_006);
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.GONE);
                });
    }

    @Test
    @DisplayName("서명이 다른 토큰은 거부한다")
    void rejectForgedSignature() {
        JwtProperties other = new JwtProperties("another-secret-another-secret-another-0123456789",
                Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(5));
        JwtTokenProvider.IssuedToken forged =
                new JwtTokenProvider(other, Clock.systemUTC()).issueAccessToken(1L, 1L);

        assertThatThrownBy(() -> provider.parseAccessToken(forged.value()))
                .isInstanceOf(BusinessException.class);
    }
}