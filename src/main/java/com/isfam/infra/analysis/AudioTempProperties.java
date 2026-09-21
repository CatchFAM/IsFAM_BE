package com.isfam.infra.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 통화 음성 임시 저장소 설정.
 *
 * <p><b>{@code tmp-dir} 는 운영에서 tmpfs 여야 합니다</b>(예: {@code /dev/shm/isfam}).
 * 일반 디스크로 두면 파일을 지워도 블록이 남아 "저장하지 않는다"는 처리방침과
 * 어긋납니다. 인프라 설계 계획의 <b>swap 비활성화 · 힙 덤프 비활성화</b>도 같은 이유의
 * 한 묶음입니다 — 둘 다 tmpfs 통제를 우회해 오디오를 디스크로 보내는 경로입니다.
 *
 * @param tmpDir    tmpfs 경로
 * @param orphanAge 이 시간보다 오래된 파일은 고아로 봅니다(P-22).
 *                  <b>분석 read-timeout 보다 넉넉히 길어야 합니다</b> — 짧으면 스위퍼가
 *                  분석 중인 파일을 지워버립니다. {@link AudioTempSweeper} 가 기동 시
 *                  검사해 위반이면 부팅을 거부합니다
 */
@ConfigurationProperties(prefix = "isfam.audio")
public record AudioTempProperties(
        String tmpDir,
        Duration orphanAge
) {

    public AudioTempProperties {
        if (tmpDir == null || tmpDir.isBlank()) {
            throw new IllegalStateException("isfam.audio.tmp-dir 이 비어 있습니다");
        }
        if (orphanAge == null || orphanAge.isNegative() || orphanAge.isZero()) {
            orphanAge = Duration.ofMinutes(5);
        }
    }
}