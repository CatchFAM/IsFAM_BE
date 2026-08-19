// src/main/java/com/isfam/common/entity/BaseTimeEntity.java
package com.isfam.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * created_at / updated_at 자동 관리.
 *
 * <p>ERD 상 created_at 만 있는 테이블(call_events, notifications, demo_sessions 등)은
 * 이걸 상속하지 말고 엔티티에 @CreatedDate 필드 하나만 직접 선언하세요.
 *
 * <p>시각은 전부 {@link Instant}. API 응답이 ISO-8601 UTC 이고,
 * family/embeddings 의 since 처럼 서버가 준 값을 되받는 흐름이 있어
 * 타임존이 섞이면 임베딩이 조용히 누락됩니다(service-flow.md 7장).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}