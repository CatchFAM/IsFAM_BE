package com.isfam.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄링 활성화와 <b>풀 분리</b> (P-22).
 *
 * <p>{@code @EnableScheduling} 이 프로젝트에 없어서 주기적 점검 자체가 불가능한
 * 상태였습니다(S3-f). Phase 11 의 파기 감시·tmpfs 스위퍼, Phase 13 의 파기 배치가
 * 전부 여기에 걸려 있습니다.
 *
 * <h2>왜 빈을 두 개 선언하는가</h2>
 *
 * <p>스케줄러 풀은 {@code @Async} 와 분리해야 합니다. FCM 왕복({@code PushDispatcher})이
 * 느려질 때 파기 점검과 tmpfs 스위퍼가 같이 밀리면, 정작 감시해야 할 시점에 감시가
 * 멈춥니다.
 *
 * <p><b>그런데 {@code ThreadPoolTaskScheduler} 하나만 선언하면 분리가 아니라 병합이
 * 됩니다.</b> Boot 의 {@code applicationTaskExecutor} 는
 * {@code @ConditionalOnMissingBean(Executor.class)} 이고
 * {@code ThreadPoolTaskScheduler} 도 {@code Executor} 라서, 스케줄러를 선언하는 순간
 * 자동 설정이 물러납니다. 그러면 컨텍스트에 남는 {@code TaskExecutor} 가 스케줄러
 * 하나뿐이라 <b>{@code @Async} 가 스케줄러 풀로 흘러듭니다</b> — 예외가 나지 않아서
 * 알아채기 어려운 종류의 고장입니다({@code AppConfig} 가 {@code @EnableAsync} 를
 * 켜둔 이유와 같은 계열의 함정).
 *
 * <p>그래서 실행기와 스케줄러를 둘 다 명시합니다. 빈 이름
 * {@code applicationTaskExecutor} · {@code taskExecutor} 는 Spring 규약
 * ({@code AsyncExecutionAspectSupport.DEFAULT_TASK_EXECUTOR_BEAN_NAME})이라
 * 바꾸지 마세요. 바꾸면 위 경로가 그대로 되살아납니다.
 *
 * <p>{@code @EnableAsync} 는 {@code AppConfig} 에 있습니다 — 여기서 중복으로 켜지 않습니다.
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * {@code @Async} 전용 (푸시 발송).
     *
     * <p>Boot 기본값과 같은 역할이지만, 위 사유로 <b>반드시 명시적으로</b> 선언합니다.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("isfam-async-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    /**
     * {@code @Scheduled} 전용.
     *
     * <p>풀이 1이면 파기 점검(5분)과 tmpfs 스위퍼(10분)가 같은 분에 겹칠 때 한쪽이
     * 밀립니다. <b>Phase 13 에서 3으로 올렸습니다.</b> 보관기간 파기 배치(일 1회)는 밀린
     * 분량이 있으면 몇 분씩 스레드를 잡습니다. 2였다면 그동안 파기 점검과 스위퍼가
     * 한 스레드를 나눠 써야 하고, 감시가 밀리면 {@code PurgeMonitor} 가 stale 로 DOWN 이 됩니다.
     *
     * <p>에러 핸들러를 명시해 한 번의 실패가 다음 주기를 막지 않게 합니다.
     * 예외 메시지에는 요청 원문 조각이 섞일 수 있어 메시지를 포맷에 넣지 않고
     * 클래스명만 씁니다(P-24).
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("isfam-sched-");
        scheduler.setPoolSize(3);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(t ->
                log.error("스케줄 작업이 실패했습니다 ({})", t.getClass().getName(), t));
        return scheduler;
    }
}