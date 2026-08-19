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

import java.time.Duration;
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
 * <p>Phase 4 에서 초대 코드 3종({@code invite_code}, {@code invite_code_expires_at},
 * {@code invite_code_rotated_at})을 매핑했습니다. 전부 nullable 이라
 * {@code ddl-auto=update} 가 컬럼을 붙입니다.
 */
@Getter
@Entity
@Table(name = "families")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family extends BaseTimeEntity {

    /** FAMILY_006 판정 기준. 실제 검사는 초대 수락에서 합니다 */
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

    /**
     * 공간당 1개. null 이면 초대 비활성입니다.
     *
     * <p><b>재사용형입니다.</b> 한 링크로 온 가족이 각자 수락하므로 수락해도 소모되지 않고,
     * "이미 사용된 코드" 검사도 없습니다. 코드를 죽이는 경로는 회전(POST)과 비활성화(DELETE) 둘뿐입니다.
     *
     * <p>{@code unique} 는 발급 시 중복 회피의 최종 방어선입니다.
     * {@link com.isfam.domain.family.service.InviteCodeGenerator} 가 사전 조회로 걸러도
     * 동시 발급에서는 여기까지 와야 막힙니다.
     */
    @Column(name = "invite_code", unique = true, length = 16)
    private String inviteCode;

    /** 발급 후 72시간. null 이면 만료 판정 자체가 불가능하므로 코드도 없는 것으로 취급합니다 */
    @Column(name = "invite_code_expires_at")
    private Instant inviteCodeExpiresAt;

    @Column(name = "invite_code_rotated_at")
    private Instant inviteCodeRotatedAt;

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

    // ── 초대 코드 ───────────────────────────────────────────

    /**
     * 새 코드로 교체합니다. 기존 코드는 이 시점에 즉시 죽습니다.
     *
     * <p>호출 경로는 <b>POST /family/invite-code 뿐</b>입니다. 초대 화면 진입(GET)에서
     * 부르면 화면을 열 때마다 링크가 회전해서 방금 공유한 링크가 죽습니다.
     */
    public void rotateInviteCode(String code, Instant now, Duration ttl) {
        this.inviteCode = code;
        this.inviteCodeRotatedAt = now;
        this.inviteCodeExpiresAt = now.plus(ttl);
    }

    /**
     * 초대 비활성화(DELETE /family/invite-code) · 공간 삭제 시 호출합니다.
     *
     * <p>rotated_at 까지 비우는 이유는 남겨둬도 읽는 쪽이 없어서입니다. 코드가 없는데
     * "마지막 회전 시각"만 남으면 GET 응답의 전 필드 null 규칙과 어긋나 보입니다.
     *
     * <p>소프트 삭제된 공간의 코드를 비우지 않으면 그 문자열이 unique 제약에 영구히
     * 묶여 재발급 후보에서 빠집니다.
     */
    public void deactivateInviteCode() {
        this.inviteCode = null;
        this.inviteCodeExpiresAt = null;
        this.inviteCodeRotatedAt = null;
    }

    /**
     * 지금 이 코드로 들어올 수 있는지. 만료 시각이 없으면 코드가 있어도 false 입니다.
     *
     * <p>만료된 코드를 DB 에서 지우지는 않습니다. 스케줄러 없이도 판정이 시각 비교만으로
     * 끝나고, 지워버리면 "만료된 초대예요"(410)와 "없는 코드"(404)를 구분할 수 없습니다.
     */
    public boolean hasActiveInviteCode(Instant now) {
        return inviteCode != null && inviteCodeExpiresAt != null && inviteCodeExpiresAt.isAfter(now);
    }

    // ── 삭제 ────────────────────────────────────────────────

    /**
     * 공간 삭제. 호출 전에 <b>본인 외 active 멤버가 0명</b>인지 확인돼 있어야 합니다(FAMILY_007).
     * owner 멤버십을 removed 로 바꾸는 건 호출부의 몫입니다.
     *
     * <p>초대 코드도 함께 죽입니다. 안 죽이면 삭제된 공간의 링크로 들어온 사람이
     * {@code deletedAt IS NULL} 조건에 걸려 404 를 받는데, 앱 문구는 "만료된 초대"라
     * 결과적으로는 같지만 코드 문자열이 계속 점유됩니다.
     */
    public void softDelete(Instant now) {
        this.deletedAt = now;
        deactivateInviteCode();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}