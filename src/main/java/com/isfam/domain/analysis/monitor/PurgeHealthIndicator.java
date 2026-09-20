package com.isfam.domain.analysis.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 파기 누락을 Actuator 헬스체크에 노출합니다 (P-21 · S3-②).
 *
 * <p><b>알람이 유실돼도 상태로 남습니다.</b> Slack Webhook 이 아직 없는 Phase 11 에서는
 * 이쪽이 사실상 유일한 관측 수단이고, Phase 15 이후에도 "알람을 못 봤다"와
 * "문제가 없었다"를 구별하는 근거가 됩니다.
 *
 * <pre>
 *   GET /actuator/health   →  503 {"status":"DOWN"}   ← purged_at IS NULL 이 0 이 아님
 * </pre>
 *
 * <p><b>세부 정보는 내보내지 않습니다.</b>
 * {@code management.endpoint.health.show-details=never} 로 잠가 두었습니다 —
 * 미인증으로 열린 경로라 "파기 실패 몇 건"이 밖에서 보이면 사고 시점을 알려주는
 * 꼴이 됩니다. 건수는 로그와 알람으로 봅니다. {@code withDetail} 은 나중에 인증 뒤로
 * 옮길 때를 위해 채워만 둡니다.
 *
 * <p><b>로드밸런서 헬스체크로 이 경로를 쓰지 마세요.</b> 파기 실패 1건에 전 인스턴스가
 * 타깃 그룹에서 빠져 서비스가 멈춥니다. LB 는 기존 {@code GET /health} 를 씁니다.
 *
 * <p>Boot 4 에서 헬스 계약 타입이 {@code org.springframework.boot.actuate.health} 에서
 * {@code org.springframework.boot.health.contributor} 로 옮겨졌습니다.
 * Boot 3 예제를 그대로 붙이면 컴파일되지 않습니다.
 *
 * <p>빈 이름이 헬스 컴포넌트 키가 됩니다 — {@code components.audioPurge}.
 */
@Component("audioPurge")
@RequiredArgsConstructor
public class PurgeHealthIndicator implements HealthIndicator {

    private final PurgeMonitor purgeMonitor;

    @Override
    public Health health() {
        PurgeMonitor.Snapshot snapshot = purgeMonitor.snapshot();

        if (!snapshot.isChecked()) {
            // 기동 직후. UP 으로 속이지 않습니다 — 아직 아무것도 확인하지 않았습니다
            return Health.unknown()
                    .withDetail("reason", "아직 파기 점검을 수행하지 않았습니다")
                    .build();
        }

        if (snapshot.stale()) {
            return Health.down()
                    .withDetail("reason", "파기 점검 스케줄이 멈췄습니다")
                    .withDetail("checkedAt", snapshot.checkedAt().toString())
                    .build();
        }

        Health.Builder builder = snapshot.isHealthy() ? Health.up() : Health.down();

        return builder
                .withDetail("unpurged", snapshot.unpurged())
                .withDetail("checkedAt", snapshot.checkedAt().toString())
                .build();
    }
}