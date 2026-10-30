package com.isfam.domain.notification.repository;

import com.isfam.domain.notification.entity.DangerShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <b>보관기간(7일) 삭제는 여기 두지 않습니다</b> — 파기 배치 전용 코드
 * ({@code RetentionPurgeRepository})에만 있습니다(원칙 R-8).
 * 여기 있는 삭제는 정보주체의 행위(탈퇴·분석 삭제)에 딸려 지우는 것뿐입니다.
 */
public interface DangerShareRepository extends JpaRepository<DangerShare, Long> {

    /** 409 판정. 동시 요청은 uq_danger_shares_analysis 가 최종으로 막습니다 */
    boolean existsByAnalysisId(Long analysisId);

    /**
     * 회원 탈퇴 — 탈퇴자가 <b>보낸</b> 공유와 탈퇴자를 <b>사칭한</b> 공유를 지웁니다.
     *
     * <ul>
     *   <li>보낸 공유: 의심 번호가 들어 있고, 공유한 사람이 사라지면 보유 근거도 사라집니다</li>
     *   <li>사칭한 공유: 번호는 없지만 탈퇴자의 user_id 가 남습니다. 지워도 잃는 정보가 없습니다</li>
     * </ul>
     * 받은 쪽은 이미 폰에 저장했으므로 받는 사람 화면에는 영향이 없습니다.
     *
     * <p>{@code WithdrawalService} 의 다른 벌크 삭제와 같은 옵션입니다 — 밀린 변경을 먼저 flush 하고,
     * 삭제 후 영속성 컨텍스트를 비웁니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DangerShare d where d.senderUserId = :userId or d.impersonatedUserId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 분석 삭제 — 그 분석의 공유를 지웁니다. 분석당 공유는 최대 1건입니다(유니크).
     *
     * <p>{@code clearAutomatically} 를 켜지 않습니다. {@code VoiceAnalysisService.delete} 가
     * 이 뒤에 앞서 읽은 분석 엔티티를 지우는데, 컨텍스트를 비우면 그 엔티티가 준영속이 됩니다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from DangerShare d where d.analysisId = :analysisId")
    int deleteByAnalysisId(@Param("analysisId") Long analysisId);
}