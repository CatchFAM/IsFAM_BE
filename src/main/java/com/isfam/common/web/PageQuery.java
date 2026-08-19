package com.isfam.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 페이징 쿼리 파라미터 — {@code page}, {@code page_size}.
 *
 * <p><b>명세는 1-base, Spring Data 는 0-base 입니다.</b> 이 변환을 컨트롤러마다 손으로 하면
 * 한 군데만 틀려도 첫 페이지가 통째로 빠집니다. 변환 지점을 여기 하나로 모읍니다.
 *
 * <p>Phase 5 의 {@code GET /notifications} 가 첫 페이징 API 이고,
 * Phase 8 의 {@code GET /voice-analyses}, Phase 9 의 {@code GET /demo-sessions} 가
 * 같은 파라미터 이름·기본값을 씁니다.
 *
 * <p><b>잘못된 값은 422 가 아니라 보정합니다.</b> page=0 이나 page_size=99999 로 422 를 던지면
 * 히스토리 화면이 통째로 안 그려집니다. 목록 조회에서 그건 과한 처벌이라
 * 범위 밖 값은 조용히 기본값·상한으로 끌어옵니다.
 */
public record PageQuery(int page, int pageSize) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 상한이 필요한 이유는 {@code page_size=100000} 한 방으로 알림 전량을 끌어가는 걸
     * 막기 위해서입니다. 가족 8명 기준 알림함이 그렇게 커질 일은 없지만,
     * 페이징 API 는 전부 이 상한을 공유합니다.
     */
    public static final int MAX_PAGE_SIZE = 100;

    public static PageQuery of(Integer page, Integer pageSize) {
        int resolvedPage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int resolvedSize = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        return new PageQuery(resolvedPage, resolvedSize);
    }

    /** 정렬은 리포지토리 메서드 이름에 있는 경우(OrderBy...) 이걸 씁니다 */
    public Pageable toPageable() {
        return PageRequest.of(page - 1, pageSize);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page - 1, pageSize, sort);
    }
}