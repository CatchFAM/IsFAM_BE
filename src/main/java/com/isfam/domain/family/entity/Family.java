package com.isfam.domain.family.entity;

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

import java.time.Instant;

/**
 * 가족 공간.
 *
 * <p><b>삭제는 소프트 삭제입니다.</b> 명세는 "families row 삭제"라고 쓰여 있지만
 * ERD 에 {@code family_memberships.family_id > families.id} 참조가 있고,
 * 같은 문장이 "owner 멤버십 status='removed'" 로 멤버십 row 를 남기라고 요구합니다.
 * 부모를 지우면서 자식을 남길 수는 없어서 {@code deleted_at} 을 추가했습니다.
 * 멤버십 이력이 그대로 남아야 Phase 5 의 {@code removed_user_ids} 계산이 안전합니다.
 *
 * <p>따라서 <b>모든 조회에 {@code deletedAt IS NULL} 이 붙어야 합니다.</b>
 * 빠뜨리면 삭제된 공간이 GET /family 에 다시 나타납니다.
 *
 * <p>invite_code · invite_code_expires_at · invite_code_rotated_at 은 매핑하지 않았습니다.
 * Phase 4 초대에서 처음 쓰이고 전부 nullable 이라, 그때 필드를 추가하면
 * ddl-auto=update 가 컬럼을 붙입니다. 반면 max_members 는 NOT NULL DEFAULT 8 이라
 * 나중에 붙이면 기존 row 를 채울 방법이 없어 지금 넣어둡니다.
 */
@Getter
@Entity
@Table(name = "families")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family extends BaseTimeEntity {

    /** FAMILY_006 판정 기준. 실제 검사는 초대 수락(Phase 4)에서 합니다 */
    public static final int DEFAULT_MAX_MEMBERS = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "max_members", nullable = false)
    private int maxMembers;

    /** null 이면 살아 있는 공간. 명세의 "families row 삭제"를 이걸로 대신합니다 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Family(String name, Long createdByUserId) {
        this.name = name;
        this.createdByUserId = createdByUserId;
        this.maxMembers = DEFAULT_MAX_MEMBERS;
    }

    public static Family create(String name, Long createdByUserId) {
        return new Family(name, createdByUserId);
    }

    public void rename(String name) {
        this.name = name;
    }

    /**
     * 공간 삭제. 호출 전에 <b>본인 외 active 멤버가 0명</b>인지 확인돼 있어야 합니다(FAMILY_007).
     * owner 멤버십을 removed 로 바꾸는 건 호출부의 몫입니다.
     */
    public void softDelete(Instant now) {
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}