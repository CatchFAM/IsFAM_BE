package com.isfam.domain.family.repository;

import com.isfam.domain.family.entity.Family;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 조회 조건에 항상 {@code deletedAt IS NULL} 이 붙습니다.
 * 빼면 삭제된 공간이 GET /family 에 다시 나타납니다.
 */
public interface FamilyRepository extends JpaRepository<Family, Long> {

    Optional<Family> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 초대 코드 발급 시 중복 확인용.
     *
     * <p><b>{@code deletedAt} 조건이 없습니다.</b> unique 제약은 삭제 여부를 안 가리므로,
     * 살아 있는 공간만 보고 발급하면 삭제된 공간이 쥐고 있는 코드와 충돌합니다.
     * (삭제 시 코드를 비우기는 하지만, 그 이전 데이터가 남아 있을 수 있습니다)
     */
    boolean existsByInviteCode(String inviteCode);

    /** GET /invitations/{code} — 미리보기. 삭제된 공간의 코드는 없는 코드입니다 */
    Optional<Family> findByInviteCodeAndDeletedAtIsNull(String inviteCode);

    /**
     * POST /invitations/{code}/accept 전용.
     *
     * <p>행 잠금을 거는 이유는 <b>FAMILY_006(정원 8명)</b> 때문입니다. 카운트와 insert 사이가
     * 열려 있으면 8명째 자리를 두 명이 동시에 통과해 9명이 됩니다. 1인 1공간(FAMILY_004)은
     * 부분 유니크 인덱스가 막아주지만 정원은 막아주는 제약이 없어서 잠금이 유일한 방어입니다.
     *
     * <p>잠금 단위가 공간 하나라 같은 가족의 동시 수락만 직렬화됩니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Family f where f.inviteCode = :inviteCode and f.deletedAt is null")
    Optional<Family> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);
}