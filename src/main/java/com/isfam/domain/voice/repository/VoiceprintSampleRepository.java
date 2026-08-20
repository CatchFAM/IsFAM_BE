package com.isfam.domain.voice.repository;

import com.isfam.domain.voice.entity.VoiceprintSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VoiceprintSampleRepository extends JpaRepository<VoiceprintSample, Long> {

    /** 같은 문장 재녹음 판정 — 있으면 교체, 없으면 추가 */
    Optional<VoiceprintSample> findByUserIdAndSentenceId(Long userId, int sentenceId);

    /** 평균 재계산 · GET /me/voiceprint 의 samples[] */
    List<VoiceprintSample> findByUserIdOrderBySentenceIdAsc(Long userId);

    /**
     * 모델 계열이 바뀌었을 때 기존 샘플을 버립니다.
     *
     * <p>다른 모델로 뽑은 임베딩끼리 평균을 내면 의미 없는 벡터가 나오는데, 등록은 성공하고
     * 탐지만 조용히 망가집니다. 회원 탈퇴(Phase 9)에서도 같은 메서드를 씁니다 —
     * 생체정보는 soft delete 가 허용되지 않습니다.
     *
     * <p>파생 삭제 메서드({@code deleteByUserId}) 대신 벌크 delete 를 쓴 이유는
     * 전자가 엔티티를 전부 로딩한 뒤 한 건씩 지우기 때문입니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from VoiceprintSample s where s.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}