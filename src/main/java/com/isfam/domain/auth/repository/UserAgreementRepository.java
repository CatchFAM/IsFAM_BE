package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     *
     * <p><b>{@code MARKETING} 에는 쓰지 마세요</b> — 바꿀 수 있는 항목이라 과거 동의 행이
     * 남아 있습니다. {@link #findFirstByUserIdAndAgreementTypeOrderByAgreedAtDescIdDesc} 로 최신 행을 보세요.
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

    /**
     * 해당 항목에 동의했는가.
     *
     * <p>공간 생성·초대 수락에서 {@code VOICE_SHARING} 으로 목소리 공유 시작 여부를 판정할 때 씁니다.
     * 가입에서 필수라 정상 계정이면 항상 true 입니다. false 면 동의 근거가 없는 것이므로
     * 공유를 켜지 않습니다(동의 없는 제3자 제공 방지).
     *
     * <p><b>{@code MARKETING} 에는 쓰지 마세요</b> — 위와 같은 이유입니다.
     */
    boolean existsByUserIdAndAgreementTypeAndAgreedTrue(Long userId, UserAgreement.Type agreementType);

    /**
     * 해당 항목의 <b>가장 최근 행</b> = 현재 상태 (알림 설정 세분화 4단계).
     *
     * <p>마케팅 수신처럼 가입 후에 바꿀 수 있는 항목에 씁니다. 같은 시각에 두 행이 생기는
     * 경우(빠른 연속 토글)를 위해 id 로 한 번 더 정렬합니다.
     */
    Optional<UserAgreement> findFirstByUserIdAndAgreementTypeOrderByAgreedAtDescIdDesc(
            Long userId, UserAgreement.Type agreementType);
}