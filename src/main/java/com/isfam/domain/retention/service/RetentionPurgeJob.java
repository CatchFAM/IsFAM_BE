package com.isfam.domain.retention.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관기간 파기 배치의 스케줄 (P-03).
 *
 * <p>매일 04:10 (Asia/Seoul). 시각과 시간대는 {@code isfam.retention.cron} ·
 * {@code isfam.retention.zone} 입니다. {@code zone} 을 명시하는 이유는 서버 기본 시간대가
 * UTC 라서입니다 — 빼먹으면 새벽 4시가 오후 1시가 되어 한낮 트래픽과 겹칩니다.
 *
 * <p>스케줄과 실행을 나눈 이유는 테스트와 수동 실행입니다. {@link RetentionPurgeService#purgeAll()}
 * 은 스케줄 없이도 부를 수 있어야 합니다(첫 가동 시 확인, 장애 후 재실행).
 *
 * <p>예외는 서비스가 테이블별로 삼키므로 여기까지 올라오지 않습니다. 올라오더라도
 * {@code SchedulingConfig} 의 에러 핸들러가 받아 다음 날 실행을 막지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class RetentionPurgeJob {

    private final RetentionPurgeService retentionPurgeService;

    @Scheduled(
            cron = "${isfam.retention.cron:0 10 4 * * *}",
            zone = "${isfam.retention.zone:Asia/Seoul}")
    public void run() {
        retentionPurgeService.purgeAll();
    }
}