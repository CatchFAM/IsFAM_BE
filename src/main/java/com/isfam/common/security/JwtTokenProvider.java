package com.isfam.common.security;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 토큰 3종(access · refresh · phone_verification)의 발급·검증.
 * typ 클레임으로 용도를 구분해서, 리프레시 토큰을 Authorization 헤더에 넣는 오용을 막습니다.
 *
 * <p><b>발급 순서 주의</b> — access 의 sid 클레임이 refresh_tokens.id 입니다. 로그인/가입 시
 * <ol>
 *   <li>{@link #issueRefreshToken(long)} 으로 refresh 발급</li>
 *   <li>원문 해시를 refresh_tokens 에 INSERT → 생성된 id 획득</li>
 *   <li>그 id 로 {@link #issueAccessToken(long, long)} 호출</li>
 * </ol>
 * 순서가 강제됩니다. 로그아웃은 이 sid 로 해당 row 만 revoke 합니다.
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_SESSION_ID = "sid";
    public static final String CLAIM_PHONE_NUMBER = "phone_number";
    public static final String CLAIM_VERIFICATION_ID = "verification_id";

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String TYPE_PHONE_VERIFICATION = "phone_verification";
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    // ── 발급 ────────────────────────────────────────────────

    public IssuedToken issueAccessToken(long userId, long sessionId) {
        return issue(TYPE_ACCESS, String.valueOf(userId), properties.accessTokenTtl(),
                b -> b.claim(CLAIM_SESSION_ID, sessionId));
    }

    public IssuedToken issueRefreshToken(long userId) {
        return issue(TYPE_REFRESH, String.valueOf(userId), properties.refreshTokenTtl(), b -> {
        });
    }

    /** phone_number 를 담아, signup 입력값이 인증받은 번호와 같은지 서버가 검증할 수 있게 합니다 */
    public IssuedToken issuePhoneVerificationToken(String verificationId, String phoneNumber) {
        return issue(TYPE_PHONE_VERIFICATION, verificationId, properties.phoneVerificationTokenTtl(),
                b -> b.claim(CLAIM_VERIFICATION_ID, verificationId)
                        .claim(CLAIM_PHONE_NUMBER, phoneNumber));
    }

    // ── 검증 ────────────────────────────────────────────────

    /** 실패 시 AUTH_009 */
    public AccessTokenPayload parseAccessToken(String token) {
        Claims claims = parse(token, TYPE_ACCESS, ErrorCode.AUTH_009, HttpStatus.UNAUTHORIZED);
        Number sid = claims.get(CLAIM_SESSION_ID, Number.class);
        return new AccessTokenPayload(toUserId(claims), sid == null ? null : sid.longValue());
    }

    /** 실패 시 AUTH_002 → 앱은 재로그인 유도 */
    public RefreshTokenPayload parseRefreshToken(String token) {
        Claims claims = parse(token, TYPE_REFRESH, ErrorCode.AUTH_002, HttpStatus.UNAUTHORIZED);
        return new RefreshTokenPayload(toUserId(claims), claims.getId());
    }

    /** 실패 시 AUTH_006. 만료면 410 — 명세의 "만료된 인증 요청" */
    public PhoneVerificationPayload parsePhoneVerificationToken(String token) {
        Claims claims = parse(token, TYPE_PHONE_VERIFICATION, ErrorCode.AUTH_006, HttpStatus.GONE);
        return new PhoneVerificationPayload(
                claims.get(CLAIM_VERIFICATION_ID, String.class),
                claims.get(CLAIM_PHONE_NUMBER, String.class));
    }

    // ── 내부 ────────────────────────────────────────────────

    private IssuedToken issue(String type, String subject, Duration ttl, Consumer<JwtBuilder> customizer) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ttl);
        String jti = UUID.randomUUID().toString();

        JwtBuilder builder = Jwts.builder()
                .id(jti)
                .subject(subject)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256);
        customizer.accept(builder);

        return new IssuedToken(builder.compact(), jti, now, expiresAt);
    }

    private Claims parse(String token, String expectedType, ErrorCode errorCode, HttpStatus expiredStatus) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // typ 이 다르면 다른 용도의 토큰을 밀어 넣은 것
            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new BusinessException(errorCode);
            }
            return claims;

        } catch (ExpiredJwtException e) {
            throw new BusinessException(errorCode, expiredStatus);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(errorCode);
        }
    }

    private long toUserId(Claims claims) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new BusinessException(ErrorCode.AUTH_009);
        }
    }

    // ── 발급·파싱 결과 ──────────────────────────────────────

    /**
     * @param value     JWT 원문. 응답으로 내려주고 DB 에는 해시만 저장합니다
     * @param expiresAt refresh_tokens.expires_at / phone_verifications.token_expires_at 에 그대로 사용
     */
    public record IssuedToken(String value, String jti, Instant issuedAt, Instant expiresAt) {
    }

    /** sessionId = refresh_tokens.id. 로그아웃이 이 값으로 해당 세션만 끊습니다 */
    public record AccessTokenPayload(long userId, Long sessionId) {
    }

    public record RefreshTokenPayload(long userId, String jti) {
    }

    public record PhoneVerificationPayload(String verificationId, String phoneNumber) {
    }
}