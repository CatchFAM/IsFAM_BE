package com.isfam.domain.analysis.service;

import com.isfam.domain.analysis.dto.VoiceAnalysisDto;
import com.isfam.domain.analysis.entity.VoiceAnalysis;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /voice-analyses} 의 동적 필터.
 *
 * <p><b>{@code userId} 조건이 항상 최우선으로 붙습니다.</b> 필터를 조합하다 보면
 * 조건 하나를 빠뜨리기 쉬운데, 그게 하필 소유자 조건이면 남의 통화 이력이 통째로 노출됩니다.
 * 그래서 {@link #ownedBy} 를 시작점으로 두고 나머지를 {@code and} 로 붙이는 형태만 씁니다.
 *
 * <p>JPQL 의 {@code (:param is null or ...)} 방식을 쓰지 않은 이유는
 * {@code VoiceAnalysisRepository} javadoc 에 있습니다.
 */
final class VoiceAnalysisSpecifications {

    private VoiceAnalysisSpecifications() {
    }

    static Specification<VoiceAnalysis> search(Long userId, VoiceAnalysisDto.SearchCondition condition) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(builder.equal(root.get("userId"), userId));

            if (condition != null) {
                if (condition.dateFrom() != null) {
                    predicates.add(builder.greaterThanOrEqualTo(
                            root.get("submittedAt"), condition.dateFrom()));
                }
                if (condition.dateTo() != null) {
                    predicates.add(builder.lessThanOrEqualTo(
                            root.get("submittedAt"), condition.dateTo()));
                }
                if (condition.finalDecision() != null) {
                    predicates.add(builder.equal(
                            root.get("finalDecision"), condition.finalDecision()));
                }
                if (condition.minRiskLevel() != null) {
                    predicates.add(builder.greaterThanOrEqualTo(
                            root.get("riskLevel"), condition.minRiskLevel()));
                }
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    static Specification<VoiceAnalysis> ownedBy(Long userId) {
        return (root, query, builder) -> builder.equal(root.get("userId"), userId);
    }
}