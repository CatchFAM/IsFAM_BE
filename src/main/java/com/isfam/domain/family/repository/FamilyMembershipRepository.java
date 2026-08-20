package com.isfam.domain.family.repository;

import com.isfam.domain.family.entity.FamilyMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FamilyMembershipRepository extends JpaRepository<FamilyMembership, Long> {

    /**
     * 내 현재 소속. 부분 유니크 인덱스(uq_membership_active_user) 덕분에 최대 1건입니다.
     * 인덱스를 안 걸면 여기서 IncorrectResultSizeDataAccessException 이 터집니다.
     */
    Optional<FamilyMembership> findByUserIdAndStatus(Long userId, FamilyMembership.Status status);

    boolean existsByUserIdAndStatus(Long userId, FamilyMembership.Status status);

    List<FamilyMembership> findByFamilyIdAndStatusOrderByJoinedAtAsc(
            Long familyId, FamilyMembership.Status status);

    /**
     * <b>status 를 안 가립니다.</b> {@code GET /family/embeddings}(Phase 7)에서
     * {@code removed_user_ids} 를 계산하려면 나간 사람의 row 가 필요합니다.
     * active 만 조회하면 강퇴당한 사람이 목록에서 사라져, 남은 가족 폰에 그 사람의
     * 생체정보가 영영 남습니다.
     *
     * <p>한 공간의 row 는 정원 8명 + 나간 이력이라 전량을 메모리에 올려도 괜찮습니다.
     */
    List<FamilyMembership> findByFamilyId(Long familyId);

    Optional<FamilyMembership> findByFamilyIdAndUserIdAndStatus(
            Long familyId, Long userId, FamilyMembership.Status status);

    /**
     * <b>status 를 안 봅니다.</b> 초대 수락에서 "예전에 나갔던 row"를 찾으려면 이게 필요합니다.
     * active 만 조회하고 없으면 insert 하는 식으로 짜면 재가입에서
     * {@code uq_membership_family_user} 위반으로 터집니다.
     */
    Optional<FamilyMembership> findByFamilyIdAndUserId(Long familyId, Long userId);

    long countByFamilyIdAndStatus(Long familyId, FamilyMembership.Status status);
}