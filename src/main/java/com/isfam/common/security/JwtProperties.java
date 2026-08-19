package com.isfam.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * application.properties 의 isfam.jwt.* 바인딩.
 * secret 은 HS256 기준 최소 32바이트. 운영에서는 환경변수로 주입하세요.
 */
@Validated
@ConfigurationProperties(prefix = "isfam.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration phoneVerificationTokenTtl
) {
}