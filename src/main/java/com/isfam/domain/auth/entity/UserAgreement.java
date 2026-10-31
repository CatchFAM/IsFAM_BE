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
 * <p><b>행을 고치지 않고 쌓습니다.</b> 동의를 바꾸면(지금은 마케팅만 가능) 새 행이 추가되고,
 * 현재 상태는 그 항목의 <b>가장 최근 행</b>입니다. 언제 동의했고 언제 거부했는지가 그대로 남아야
 * 분쟁·실태점검 때 증명할 수 있습니다.
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
     * 현재 약관 버전. 가입 화면·설정 화면 문구가 바뀌면 올리세요.
     *
     * <p>v1.1 — 가족과 전화번호 공유(PHONE_SHARING) 항목 추가.
     * <p>v1.2 — 음성 정보 가족 공유(VOICE_SHARING)를 초대 수락에서 가입 화면으로 이동.
     *
     * <p>가입(AuthService)과 마케팅 수신 변경(SettingsService)이 같은 값을 씁니다 —
     * 마케팅 문구는 가입 화면의 그 항목과 같은 문구이기 때문입니다.
     */
    public static final String CURRENT_VERSION = "v1.2";

    /**
     * 동의 항목. 법 제23조상 민감정보 처리와 제3자 제공은 별도 항목이어야 합니다.
     *
     * <p>가입 시 7종이 한꺼번에 기록됩니다(AuthService.saveAgreements).
     *
     * <p><b>값을 추가할 때는 DB 제약도 함께 고치세요.</b> Hibernate 가 테이블을 만들 때
     * {@code agreement_type} 에 {@code CHECK (... IN (...))} 를 붙이고,
     * {@code ddl-auto=update} 는 값이 늘어도 그 제약을 고치지 않습니다 —
     * 새 값의 INSERT 가 제약 위반으로 실패합니다(db/phase13-0.sql 과 같은 이유).
     * 현재 정의: db/phone-sharing-1.sql
     */
    public enum Type {
        /** 만 14세 이상 확인 (필수) — 동의가 아니라 확인. 생년월일 대신 이 사실만 남깁니다 (P-08) */
        AGE_OVER_14,
        /** 이용약관 (필수) */
        TERMS,
        /** 개인정보 처리방침 (필수) — Phase 14 P-06 부터 가입 시 기록 */
        PRIVACY,
        /** 음성 민감정보 처리 (필수) — 내 음성을 특징값으로 변환 */
        VOICE_PRINT,
        /**
         * 마케팅 수신 (선택).
         *
         * <p><b>유일하게 가입 후에 바꿀 수 있는 항목입니다</b> — 설정 화면의 마케팅 정보 수신 토글
         * ({@code PUT /settings/marketing}). 바꿀 때마다 행이 하나씩 쌓이고, 현재 상태와
         * 화면에 보이는 처리일("2026.07.12 수신 거부")은 가장 최근 행입니다.
         * 그래서 이 항목은 {@code existsBy...AgreedTrue} 로 판정하면 안 됩니다 —
         * 과거에 한 번이라도 동의했으면 지금 거부 중이어도 true 가 나옵니다.
         */
        MARKETING,
        /**
         * 음성 제3자 제공 (가입 시 필수) — 내 임베딩이 가족 기기로 복제되는 것 (P-07).
         *
         * <p><b>가입 때 한 번</b> 받습니다. 받는 사람은 "내가 참여하는 가족 공간의 현재 및 향후 구성원"이고,
         * 공간을 만들거나 다른 공간에 들어가도 새로 받지 않습니다 — PHONE_SHARING 과 같은 구조입니다.
         * 가입 화면에서는 VOICE_PRINT 와 <b>다른 줄</b>이어야 합니다(제23조, 민감정보는 다른 동의와 섞지 않음).
         *
         * <p>공간을 만들거나 초대를 수락할 때 이 행(agreed=true)이 있어야
         * {@code family_memberships.voice_sharing_consented_at} 이 채워지고 목소리가 배포됩니다.
         * 그 컬럼은 "이 공간에서 지금 공유 중인가"를, 이 행은 "언제 어떤 문구에 동의했나"를 답합니다.
         */
        VOICE_SHARING,
        /**
         * 전화번호 제3자 제공 (가입 시 필수) — 내 휴대폰 번호가 같은 공간 가족에게 보이는 것.
         *
         * <p>{@code GET /family} 가 이 행이 {@code agreed=true} 인 멤버에게만 {@code phone_number} 를
         * 채웁니다. 앱은 걸려온 번호가 이 값과 같을 때만 위험 공유의 {@code impersonated_user_id} 를
         * 채웁니다 — 목소리 유사도로는 채우지 않습니다.
         *
         * <p>VOICE_SHARING 과 마찬가지로 <b>가입 때 한 번</b> 받고, 공간을 나가거나 다시 들어가도
         * 새로 받지 않습니다. 철회는 회원 탈퇴입니다. 공간을 나가면 번호가 더 내려가지 않는 건
         * active 멤버만 조회하기 때문이지 동의 철회가 아닙니다.
         */
        PHONE_SHARING
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