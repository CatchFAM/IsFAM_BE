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
     * <p>가입 시 4종이 한꺼번에 기록됩니다(AuthService.saveAgreements).
     *
     * <p><b>값을 추가할 때는 DB 제약도 함께 고치세요.</b> Hibernate 가 테이블을 만들 때
     * {@code agreement_type} 에 {@code CHECK (... IN (...))} 를 붙이고,
     * {@code ddl-auto=update} 는 값이 늘어도 그 제약을 고치지 않습니다 —
     * 새 값의 INSERT 가 제약 위반으로 실패합니다(db/phase13-0.sql 과 같은 이유).
     */
    public enum Type {
        /** 이용약관 (필수) */
        TERMS,
        /** 개인정보 처리방침 (필수) — Phase 14 P-06 부터 가입 시 기록 */
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