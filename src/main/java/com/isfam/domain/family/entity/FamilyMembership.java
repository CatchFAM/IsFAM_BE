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
     * 이 공간에서 음성 공유를 시작한 시각 — 내 임베딩이 이 공간 가족 기기로 복제되는 것의 <b>게이팅 값</b>입니다.
     * {@code EmbeddingSyncService} 가 이 값이 null 인 멤버의 임베딩을 배포하지 않습니다.
     *
     * <p>근거는 <b>가입 때 받은 음성 공유 동의</b>(user_agreements.VOICE_SHARING)입니다.
     * 공간을 만들거나(owner) 초대를 수락하는(member) 순간 그 동의를 확인해 이 값을 채웁니다.
     * 공간에서 동의를 따로 받지 않습니다.
     *
     * <p><b>nullable 입니다.</b> 동의 행이 없는 계정이면 null 로 두고 배포하지 않습니다.
     * 가입에서 필수라 정상 흐름에서는 생기지 않지만, 받은 적 없는 동의를 시각만 찍어 남기면
     * 분쟁 시 그게 동의 증빙으로 오독되므로 확인 없이 채우지 않습니다.
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

    /**
     * 공간 생성자.
     *
     * @param consentedAt 가입 때 음성 공유에 동의했으면 생성 시각, 동의 행이 없으면 null
     */
    public static FamilyMembership createOwner(Long familyId, Long userId,
                                               Instant consentedAt, Instant joinedAt) {
        return new FamilyMembership(familyId, userId, Role.OWNER, consentedAt, joinedAt);
    }

    /**
     * 초대 수락.
     *
     * @param consentedAt 가입 때 음성 공유에 동의했으면 수락 시각, 동의 행이 없으면 null
     */
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

    /**
     * 나갔던 공간에 초대로 다시 들어옴.
     *
     * <p><b>새 row 를 만들면 안 됩니다.</b> {@code (family_id, user_id)} 가 유니크라
     * insert 하면 제약 위반으로 터집니다. 이력 보존 규칙 때문에 나간 row 가 그대로 남아 있으니
     * 그 row 를 되살리는 게 유일한 경로입니다.
     *
     * <p>역할은 <b>항상 MEMBER 로 리셋</b>합니다. 예전에 owner 였다 해도 지금은 초대받아
     * 들어오는 입장이고, 그 공간엔 이미 다른 owner 가 있습니다.
     *
     * <p>{@code joinedAt} 과 공유 시작 시각도 새로 찍습니다 — 나가 있던 동안은 공유가 없었으므로
     * 이번 참여 시각부터가 이 공간에서의 공유 기간입니다.
     */
    public void rejoin(Instant consentedAt, Instant joinedAt) {
        this.memberRole = Role.MEMBER;
        this.status = Status.ACTIVE;
        this.voiceSharingConsentedAt = consentedAt;
        this.joinedAt = joinedAt;
    }
}