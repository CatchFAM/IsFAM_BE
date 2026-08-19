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

    Optional<FamilyMembership> findByFamilyIdAndUserIdAndStatus(
            Long familyId, Long userId, FamilyMembership.Status status);

    long countByFamilyIdAndStatus(Long familyId, FamilyMembership.Status status);
}