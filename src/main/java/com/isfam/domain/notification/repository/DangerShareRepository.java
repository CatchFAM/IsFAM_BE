package com.isfam.domain.notification.repository;

import com.isfam.domain.notification.entity.DangerShare;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 탈퇴·분석 삭제용 삭제 메서드는 5단계에서 추가합니다.
 * 보관기간(7일) 삭제는 여기 두지 않습니다 — 파기 배치 전용 코드에만 둡니다(원칙 R-8).
 */
public interface DangerShareRepository extends JpaRepository<DangerShare, Long> {

    /** 409 판정. 동시 요청은 uq_danger_shares_analysis 가 최종으로 막습니다 */
    boolean existsByAnalysisId(Long analysisId);
}