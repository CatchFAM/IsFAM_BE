// src/main/java/com/isfam/domain/family/repository/FamilyMemberAliasRepository.java
package com.isfam.domain.family.repository;

import com.isfam.domain.family.entity.FamilyMemberAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * <b>조회 메서드에는 예외 없이 viewerUserId 가 들어갑니다.</b> 별명은 뷰어별 값이라
 * viewer 조건 없는 조회를 하나라도 열어두면 그게 곧 유출 경로가 됩니다.
 * 여기에 {@code findByFamilyId} 같은 메서드를 추가하지 마세요.
 *
 * <p>삭제 계열은 반대로 viewer·target 을 <b>양방향</b>으로 지웁니다. 누가 나가면
 * "그 사람이 붙인 별명"과 "그 사람에게 붙은 별명"이 둘 다 근거를 잃기 때문입니다.
 */
public interface FamilyMemberAliasRepository extends JpaRepository<FamilyMemberAlias, Long> {

    Optional<FamilyMemberAlias> findByFamilyIdAndViewerUserIdAndTargetUserId(
            Long familyId, Long viewerUserId, Long targetUserId);

    /** members[] 조립용 배치 조회. 멤버마다 부르지 마세요 */
    List<FamilyMemberAlias> findByFamilyIdAndViewerUserId(Long familyId, Long viewerUserId);

    /**
     * 나가기 · 내보내기 정리. 해당 공간에서 그 사람이 관련된 별명을 전부 지웁니다.
     *
     * <p>{@code clearAutomatically} 로 영속성 컨텍스트가 비워지므로, 호출 전에 바꾼
     * 엔티티는 {@code flushAutomatically} 로 먼저 반영됩니다. 호출 <i>후에</i>
     * 그 엔티티를 다시 만지지 마세요 — 준영속 상태입니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FamilyMemberAlias a "
            + "where a.familyId = :familyId "
            + "and (a.viewerUserId = :userId or a.targetUserId = :userId)")
    int deleteAllByFamilyIdAndUserId(@Param("familyId") Long familyId,
                                     @Param("userId") Long userId);

    /** 공간 삭제 정리 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FamilyMemberAlias a where a.familyId = :familyId")
    int deleteAllByFamilyId(@Param("familyId") Long familyId);

    /**
     * 회원 탈퇴 정리(Phase 9). 공간과 무관하게 그 사용자가 걸린 별명을 전부 지웁니다.
     * {@code DELETE /auth/me} 처리 순서 ⑤ 에서 부릅니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FamilyMemberAlias a "
            + "where a.viewerUserId = :userId or a.targetUserId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}