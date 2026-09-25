// src/main/java/com/isfam/domain/audit/repository/VoiceprintAccessLogRepository.java
package com.isfam.domain.audit.repository;

import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 음성 수집·파기 이력 조회·기록.
 *
 * <p>{@code EmbeddingAccessLogRepository} 와 같은 이유로 {@code JpaRepository} 를
 * 상속하지 않습니다 — 법정 접속기록 테이블이라 애플리케이션 경로에 삭제 수단이
 * 있으면 안 됩니다(P-13). 마커 {@link Repository} 를 상속하고 쓸 메서드만 선언하면
 * 그 외 연산은 존재 자체가 없습니다.
 *
 * <p>Phase 13 파기 배치(2년 경과분)는 별도의 배치 전용 리포지토리에서 처리합니다.
 */
public interface VoiceprintAccessLogRepository extends Repository<VoiceprintAccessLog, Long> {

    VoiceprintAccessLog save(VoiceprintAccessLog log);

    /**
     * <b>열람권(법 제35조) 응답의 원천입니다</b> — "제 음성이 언제 등록되고 언제 파기됐나요".
     *
     * <p>탈퇴한 계정도 조회됩니다. {@code users} row 는 익명화되지만 이 테이블의
     * {@code subject_user_id} 는 그대로 남아서, "탈퇴 시점에 파기했다"는 증빙이 됩니다.
     */
    List<VoiceprintAccessLog> findBySubjectUserIdOrderByCreatedAtDesc(Long subjectUserId);

    /** 같은 요청에서 남은 기록을 모을 때 씁니다 */
    List<VoiceprintAccessLog> findByTraceId(String traceId);

    /**
     * 파기 이력만. 사고 조사에서 "이 기간에 무엇이 지워졌나"를 볼 때 씁니다.
     */
    List<VoiceprintAccessLog> findByOperationOrderByCreatedAtDesc(
            VoiceprintAccessLog.Operation operation);
}