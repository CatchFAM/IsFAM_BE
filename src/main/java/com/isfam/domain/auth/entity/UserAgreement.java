package com.isfam.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 동의 이력. <b>시각과 약관 버전을 함께</b> 남깁니다(legal-compliance.md 8장).
 *
 * <p>회원 탈퇴 시에도 분쟁 대비로 보관합니다 — 익명화된 user_id 에 연결된 채로 남습니다.
 */
@Getter
@Entity
@Table(name = "user_agreements", indexes = {
        @Index(name = "idx_user_agreements_user_type", columnList = "user_id, agreement_type")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement {

    /**
     * 동의 항목. 법 제23조상 민감정보 처리와 제3자 제공은 별도 항목이어야 합니다.
     *
     * <p>PRIVACY 는 아직 수집 경로가 없습니다 — POST /auth/signup 입력에
     * privacy_agreed 필드가 없어서입니다. 명세 확정되면 SignupRequest 에 추가하세요.
     */
    public enum Type {
        /** 이용약관 (필수) */
        TERMS,
        /** 개인정보 처리방침 (필수) — 현재 미수집 */
        PRIVACY,
        /** 음성 민감정보 처리 (필수) — 내 음성을 특징값으로 변환 */
        VOICE_PRINT,
        /** 마케팅 수신 (선택) */
        MARKETING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", nullable = false, length = 20)
    private Type agreementType;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreement_version", nullable = false, length = 20)
    private String agreementVersion;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    private UserAgreement(Long userId, Type agreementType, boolean agreed,
                          String agreementVersion, Instant agreedAt) {
        this.userId = userId;
        this.agreementType = agreementType;
        this.agreed = agreed;
        this.agreementVersion = agreementVersion;
        this.agreedAt = agreedAt;
    }

    public static UserAgreement of(Long userId, Type agreementType, boolean agreed,
                                   String agreementVersion, Instant agreedAt) {
        return new UserAgreement(userId, agreementType, agreed, agreementVersion, agreedAt);
    }
}