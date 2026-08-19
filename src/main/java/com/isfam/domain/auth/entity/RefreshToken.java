package com.isfam.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 세션 1건.
 *
 * <p><b>이 엔티티의 id 가 access_token JWT 의 sid 클레임입니다.</b>
 * 로그아웃이 sid 로 이 row 하나만 revoke 하므로 다른 기기 세션은 살아남습니다.
 *
 * <p>발급 순서가 강제됩니다 — refresh 발급 → 이 row INSERT → 얻은 id 로 access 발급.
 *
 * <p>device_id 는 Phase 2 의 devices 를 가리키지만 연관관계를 걸지 않았습니다.
 * 도메인이 달라 결합을 피했고, 로그인 시점에는 단말이 없을 수도 있어 nullable 입니다.
 */
@Getter
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user", columnList = "user_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id")
    private Long deviceId;

    /** 평문 저장 금지 */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 로그아웃 · 토큰 로테이션 · 비밀번호 재설정 시 기록 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    private RefreshToken(Long userId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken issue(Long userId, String tokenHash,
                                     Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(userId, tokenHash, issuedAt, expiresAt);
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public void bindDevice(Long deviceId) {
        this.deviceId = deviceId;
    }
}