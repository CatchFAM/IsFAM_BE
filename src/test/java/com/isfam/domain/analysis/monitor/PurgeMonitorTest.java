package com.isfam.domain.analysis.monitor;

import com.isfam.common.alert.AlertSender;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 260927 — 파기 누락은 최근 24시간만 셉니다.
 * 실패 row 는 지우지 않으므로 전체를 세면 DOWN 이 풀리지 않습니다.
 */
@ExtendWith(MockitoExtension.class)
class PurgeMonitorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Instant SINCE = NOW.minus(Duration.ofHours(24));

    @Mock SpoofCheckRepository spoofCheckRepository;
    @Mock AlertSender alertSender;

    private PurgeMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new PurgeMonitor(spoofCheckRepository, alertSender,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofHours(24));
    }

    @Test
    void countsOnlyLast24Hours() {
        given(spoofCheckRepository.countByPurgedAtIsNullAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(0L);
        given(spoofCheckRepository.countByRemotePurgedIsFalseAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(0L);

        monitor.check();

        // 전체 건수 메서드는 감시에 쓰지 않습니다
        verify(spoofCheckRepository, never()).countByPurgedAtIsNull();
        verify(spoofCheckRepository, never()).countByRemotePurgedIsFalse();
        verify(alertSender).resolve(eq(PurgeMonitor.ALERT_KEY), anyString());
        verify(alertSender).resolve(eq(PurgeMonitor.ALERT_KEY_REMOTE), anyString());
        assertThat(monitor.snapshot().isHealthy()).isTrue();
    }

    @Test
    void recentFailureFiresAlertAndIsUnhealthy() {
        given(spoofCheckRepository.countByPurgedAtIsNullAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(1L);
        given(spoofCheckRepository.countByRemotePurgedIsFalseAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(0L);

        monitor.check();

        verify(alertSender).fire(eq(PurgeMonitor.ALERT_KEY), anyString());
        assertThat(monitor.snapshot().isHealthy()).isFalse();
        assertThat(monitor.snapshot().unpurged()).isEqualTo(1L);
    }

    @Test
    void windowShorterThanTwoIntervalsIsRejected() {
        assertThatThrownBy(() -> new PurgeMonitor(spoofCheckRepository, alertSender,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofMinutes(9)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void healthIndicatorIsDownOnRecentFailure() {
        given(spoofCheckRepository.countByPurgedAtIsNullAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(0L);
        given(spoofCheckRepository.countByRemotePurgedIsFalseAndRequestedAtGreaterThanEqual(SINCE))
                .willReturn(2L);

        monitor.check();

        assertThat(new PurgeHealthIndicator(monitor).health().getStatus().getCode())
                .isEqualTo("DOWN");
    }
}