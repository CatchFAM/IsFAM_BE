package com.isfam.domain.auth.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 계정.
 *
 * <p>탈퇴는 물리 삭제가 아니라 {@code deleted_at} 기록 + 전화번호 해시 치환입니다.
 * 같은 번호로 재가입이 가능해야 하므로 unique 제약을 피하려면 번호를 바꿔야 합니다.
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    /** 로그인 5회 연속 실패 시 잠금 */
    public static final int MAX_FAILED_LOGIN = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, unique = true, length = 100)
    private String phoneNumber;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** 실명 */
    @Column(name = "user_name", nullable = false, length = 50)
    private String userName;

    /** 닉네임 — 가족 공간·초대 화면에 표시 */
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private User(String phoneNumber, String passwordHash, String userName, String displayName) {
        this.phoneNumber = phoneNumber;
        this.passwordHash = passwordHash;
        this.userName = userName;
        this.displayName = displayName;
        this.failedLoginCount = 0;
    }

    public static User create(String phoneNumber, String passwordHash,
                              String userName, String displayName) {
        return new User(phoneNumber, passwordHash, userName, displayName);
    }

    // ── 로그인 실패 잠금 ─────────────────────────────────────

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 실패 누적. 5회째면 10분 잠급니다 */
    public void recordLoginFailure(Instant now) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= MAX_FAILED_LOGIN) {
            this.lockedUntil = now.plus(LOCK_DURATION);
            this.failedLoginCount = 0;
        }
    }

    public void recordLoginSuccess() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    // ── 변경 ────────────────────────────────────────────────

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        recordLoginSuccess();
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** 탈퇴 — 번호를 해시로 치환해 같은 번호 재가입을 허용합니다 */
    public void withdraw(Instant now, String hashedPhoneNumber) {
        this.deletedAt = now;
        this.phoneNumber = hashedPhoneNumber;
        this.userName = "탈퇴한 사용자";
        this.displayName = "탈퇴한 사용자";
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}