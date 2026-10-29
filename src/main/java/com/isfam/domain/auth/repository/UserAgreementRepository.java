package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {

    List<UserAgreement> findByUserId(Long userId);

    /**
     * 주어진 사람들 중 해당 항목에 <b>동의한(agreed=true)</b> 사람의 id.
     *
     * <p>GET /family 가 {@code PHONE_SHARING} 으로 번호 노출 대상을 고를 때 씁니다.
     * 멤버마다 조회하지 않고 한 번에 가져옵니다(공간당 최대 8명).
     *
     * <p>행이 없으면 결과에 들어가지 않습니다 — 이 항목이 생기기 전에 가입한 계정은
     * 동의한 적이 없으므로 번호가 나가지 않습니다.
     */
    @Query("""
            select distinct a.userId
              from UserAgreement a
             where a.userId in :userIds
               and a.agreementType = :type
               and a.agreed = true
            """)
    List<Long> findAgreedUserIds(@Param("userIds") Collection<Long> userIds,
                                 @Param("type") UserAgreement.Type type);
}