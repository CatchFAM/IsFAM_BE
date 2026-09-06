// src/main/java/com/isfam/domain/family/entity/FamilyMemberAlias.java
package com.isfam.domain.family.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가족 구성원 별명 — <b>보는 사람마다 다른 값</b>입니다.
 *
 * <p>같은 사람을 딸은 "아빠", 아내는 "여보"로 부릅니다. 값이 (viewer × target) 쌍마다
 * 하나씩이라 {@code users.display_name} 이나 {@code family_memberships} 에는 담기지 않습니다 —
 * 거기 넣으면 대상당 1개밖에 못 가집니다. 그래서 별도 테이블입니다.
 *
 * <p><b>단방향입니다.</b> target 본인도 자기에게 붙은 별명을 볼 수 없어야 합니다.
 * "아내가 나를 '여보'로 저장했다"가 노출되면 viewer 가 입력한 정보를 제3자에게 제공한 것이
 * 됩니다. 조회는 반드시 {@code viewer_user_id = 호출자} 조건과 함께 하세요 —
 * 이 필터가 빠지면 예외 없이 조용히 전원의 별명이 나갑니다.
 *
 * <p><b>서버 알림 문구에 쓸 때.</b> 금지되는 건 "통화 상대가 누구인가"를 지목하는
 * 것입니다 — 그건 매칭 결과라 서버가 모르는 값입니다. "누구의 통화인가"를 가리키는 건
 * 서버가 정당하게 아는 사실이라 별명을 써도 됩니다.
 * 현재 danger_call 은 본인에게만 가므로 쓸 자리가 없습니다.
 *
 * <p><b>관계가 끝나면 물리 삭제</b>입니다(나가기·강퇴·공간 삭제·회원 탈퇴).
 * 같은 공간 멤버가 아니게 되는 순간 보유 근거가 사라집니다. {@code deleted_at} 을 두지 마세요.
 *
 * <p>유니크 키에 {@code family_id} 가 들어간 이유: 1인 1공간 제약은 {@code status='ACTIVE'}
 * 부분 유니크라 <i>현재</i> 소속만 보장하고, {@code family_memberships} 는
 * {@code removed_user_ids} 계산 때문에 나간 row 를 남깁니다. family_id 가 없으면
 * A공간에서 붙인 별명이 B공간까지 따라옵니다.
 */
@Getter
@Entity
@Table(
        name = "family_member_aliases",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_alias_family_viewer_target",
                columnNames = {"family_id", "viewer_user_id", "target_user_id"}),
        indexes = {
                @Index(name = "idx_alias_family_target",
                        columnList = "family_id, target_user_id"),
                @Index(name = "idx_alias_viewer",
                        columnList = "viewer_user_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyMemberAlias extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 별명을 지정한 사람. 이 값이 곧 조회 주체입니다 */
    @Column(name = "viewer_user_id", nullable = false)
    private Long viewerUserId;

    /** 별명이 붙는 사람 */
    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(nullable = false, length = 50)
    private String alias;

    private FamilyMemberAlias(Long familyId, Long viewerUserId, Long targetUserId, String alias) {
        this.familyId = familyId;
        this.viewerUserId = viewerUserId;
        this.targetUserId = targetUserId;
        this.alias = alias;
    }

    /**
     * 호출 전에 {@code viewerUserId != targetUserId} 와 두 사람이 같은 공간의 active 멤버인지를
     * 확인하세요. 여기서는 검사하지 않습니다 — 판정에 리포지토리가 필요합니다.
     */
    public static FamilyMemberAlias of(Long familyId, Long viewerUserId,
                                       Long targetUserId, String alias) {
        return new FamilyMemberAlias(familyId, viewerUserId, targetUserId, alias);
    }

    /** 해제는 이 메서드가 아니라 row 삭제입니다. alias 는 not null 입니다 */
    public void rename(String alias) {
        this.alias = alias;
    }
}