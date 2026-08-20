package com.isfam.domain.analysis.repository;

import com.isfam.domain.analysis.entity.CallEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CallEventRepository extends JpaRepository<CallEvent, Long> {

    /**
     * <b>{@code userId} 조건이 핵심입니다.</b> {@code findById} 로 찾고 나중에 소유자를
     * 비교하면, 비교를 빠뜨린 순간 남의 통화 이벤트에 분석 결과를 붙일 수 있게 됩니다.
     * 조회 단계에서 막으면 그럴 여지가 없습니다.
     */
    Optional<CallEvent> findByIdAndUserId(Long id, Long userId);

    /**
     * 회원 탈퇴 시 삭제 (Phase 9 ⑤).
     *
     * <p><b>{@code spoof_checks} 는 함께 지우지 않습니다</b> — 접속기록 보관 의무 대상이라
     * 탈퇴 삭제 목록에 들어 있지 않습니다({@code SpoofCheck} javadoc 참고).
     * 그래서 이 테이블에 FK 제약을 거는 것도 피했습니다. 걸어두면 탈퇴 시
     * spoof_checks 가 call_events 를 붙잡아 삭제가 실패합니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CallEvent c where c.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}