package com.isfam.domain.family.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 공간 멤버십.
 *
 * <p><b>나가기·강퇴해도 row 를 지우지 않습니다.</b> Phase 5 의 동기화에서
 * {@code removed_user_ids} 를 계산하려면 "예전엔 멤버였다"는 사실이 남아 있어야 합니다.
 * 지우면 남은 가족 폰에서 그 사람 임베딩이 영영 삭제되지 않습니다.
 *
 * <p><b>1인 1공간 제약은 이 클래스로 표현되지 않습니다.</b> ERD 주석대로
 * {@code status='ACTIVE'} 인 row 에 대한 부분 유니크 인덱스가 필요한데 JPA 로는 못 씁니다.
 * 별도 SQL 로 직접 도세요(phase3-indexes.sql). 인덱스 없이 FAMILY_004 검사만 두면
 * 동시 요청에서 한 사람이 두 공간에 들어갑니다.
 */
@Getter
@Entity
@Table(name = "family_memberships", uniqueConstraints = {
        @UniqueConstraint(name = "uq_membership_family_user",
                columnNames = {"family_id", "user_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyMembership extends BaseTimeEntity {

    /** 공간 관리 권한입니다. 부모/자식 구분이 아닙니다 */
    public enum Role {
        OWNER, MEMBER;

        /** 응답은 "owner" 소문자입니다 */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    public enum Status {
        ACTIVE, LEFT, REMOVED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 10)
    private Role memberRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /**
     * 제3자 제공 동의 시각 — 내 임베딩이 가족 기기로 복제되는 것에 대한 동의입니다.
     *
     * <p><b>nullable 입니다.</b> ERD 는 NOT NULL 이지만 POST /family 에는 동의 입력 필드가
     * 없습니다(초대 수락에만 있음). 공간을 갓 만든 owner 는 아직 임베딩을 받아갈 상대가
     * 없으므로 제공 자체가 발생하지 않습니다. 받은 적 없는 동의를 시각만 찍어 남기면
     * 분쟁 시 그게 동의 증빙으로 오독됩니다 — 그래서 null 로 둡니다.
     *
     * <p>Phase 4 에서 다른 사람이 이 공간에 들어오는 시점에 owner 에게도 동의를 받아
     * 이 값을 채우는 흐름이 필요합니다.
     */
    @Column(name = "voice_sharing_consented_at")
    private Instant voiceSharingConsentedAt;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    private FamilyMembership(Long familyId, Long userId, Role memberRole,
                             Instant voiceSharingConsentedAt, Instant joinedAt) {
        this.familyId = familyId;
        this.userId = userId;
        this.memberRole = memberRole;
        this.status = Status.ACTIVE;
        this.voiceSharingConsentedAt = voiceSharingConsentedAt;
        this.joinedAt = joinedAt;
    }

    /** 공간 생성자. voice_sharing_consent 입력이 없는 경로라 동의 시각은 null 입니다 */
    public static FamilyMembership createOwner(Long familyId, Long userId, Instant joinedAt) {
        return new FamilyMembership(familyId, userId, Role.OWNER, null, joinedAt);
    }

    /** 초대 수락(Phase 4). voice_sharing_consent=true 를 받은 시각이 들어갑니다 */
    public static FamilyMembership join(Long familyId, Long userId,
                                        Instant consentedAt, Instant joinedAt) {
        return new FamilyMembership(familyId, userId, Role.MEMBER, consentedAt, joinedAt);
    }

    public boolean isOwner() {
        return memberRole == Role.OWNER;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** 본인이 나감 */
    public void leave() {
        this.status = Status.LEFT;
    }

    /** owner 가 내보냄 · 공간 삭제로 owner 멤버십 정리 */
    public void remove() {
        this.status = Status.REMOVED;
    }

    public void consentVoiceSharing(Instant consentedAt) {
        this.voiceSharingConsentedAt = consentedAt;
    }
}