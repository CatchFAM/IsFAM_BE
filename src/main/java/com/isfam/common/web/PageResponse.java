package com.isfam.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 페이징 응답 공통 형태 — {@code { items, page, page_size, total } }.
 *
 * <p><b>{@code GET /notifications} 는 이걸 쓰지 않습니다.</b> 명세상 응답에
 * {@code unread_count} 가 {@code items} 와 {@code page} 사이에 끼어 있어서
 * 필드 순서를 맞추려면 별도 레코드가 필요합니다
 * ({@code NotificationDto.ListResponse}). 대신 Phase 8 의 {@code GET /voice-analyses},
 * Phase 9 의 {@code GET /demo-sessions} 는 그대로 씁니다.
 *
 * <p>{@code @JsonInclude(ALWAYS)} 를 건 이유는 {@code application.properties} 의
 * {@code spring.jackson.default-property-inclusion=non_null} 때문입니다. 그 설정 아래에서는
 * 결과가 0건일 때 {@code items} 가 통째로 사라져 앱이 NPE 를 맞습니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long total
) {

    public static <E, T> PageResponse<T> of(Page<E> source, PageQuery query, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                query.page(),
                query.pageSize(),
                source.getTotalElements());
    }
}