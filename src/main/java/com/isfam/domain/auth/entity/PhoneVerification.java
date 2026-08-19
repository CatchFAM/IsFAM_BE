package com.isfam.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 휴대폰 번호 인증 1건.
 *
 * <p>수명은 세 단계입니다.
 * <ol>
 *   <li>발송 — code_hash 저장, 3분 유효</li>
 *   <li>확인 — verified_at 기록, phone_verification_token 발급(해시만 저장, 5분 유효)</li>
 *   <li>소비 — signup 또는 비밀번호 재설정에서 consumed_at 기록. <b>1회용</b></li>
 * </ol>
 *
 * <p>평문은 code 도 token 도 저장하지 않습니다.
 */
@Getter
@Entity
@Table(name = "phone_verifications", indexes = {
        @Index(name = "idx_phone_verifications_phone_created", columnList = "phone_number, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneVerification {

    /** 이 횟수만큼 틀리면 해당 verification_id 폐기 */
    public static final int MAX_ATTEMPT = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID. POST /auth/phone/send 응답값 */
    @Column(name = "verification_id", nullable = false, unique = true, length = 36)
    private String verificationId;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    /** 평문 저장 금지. 데모는 123456 고정 */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 발송 후 3분 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** phone_verification_token 해시 */
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /** signup·비밀번호 재설정에서 소비된 시각 */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private PhoneVerification(String verificationId, String phoneNumber,
                              String codeHash, Instant expiresAt) {
        this.verificationId = verificationId;
        this.phoneNumber = phoneNumber;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    public static PhoneVerification issue(String verificationId, String phoneNumber,
                                          String codeHash, Instant expiresAt) {
        return new PhoneVerification(verificationId, phoneNumber, codeHash, expiresAt);
    }

    // ── 상태 판정 ───────────────────────────────────────────

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isAttemptExceeded() {
        return attemptCount >= MAX_ATTEMPT;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isTokenExpired(Instant now) {
        return tokenExpiresAt == null || tokenExpiresAt.isBefore(now);
    }

    // ── 전이 ────────────────────────────────────────────────

    public void recordFailedAttempt() {
        this.attemptCount++;
    }

    public void markVerified(Instant now, String tokenHash, Instant tokenExpiresAt) {
        this.verifiedAt = now;
        this.tokenHash = tokenHash;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /** 1회용 소비. 토큰 해시도 지워 재사용 여지를 없앱니다 */
    public void markConsumed(Instant now) {
        this.consumedAt = now;
        this.tokenHash = null;
    }
}