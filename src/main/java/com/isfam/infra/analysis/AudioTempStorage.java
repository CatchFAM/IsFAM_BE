package com.isfam.infra.analysis;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 통화 음성이 <b>서버에서 유일하게 머무는 자리</b>. 그리고 즉시 파기하는 자리.
 *
 * <p>legal-compliance.md 4장 ③의 요구사항을 코드로 옮긴 것입니다.
 * <ul>
 *   <li>tmpfs 로 수신 → 분석 → 삭제</li>
 *   <li>디스크·객체스토리지·로그에 오디오를 남기지 않음</li>
 *   <li>예외가 발생해도 반드시 삭제되도록 {@code finally} 블록에서 처리</li>
 *   <li>파기 완료 후 {@code purged_at} 기록</li>
 * </ul>
 *
 * <p><b>운영에서 {@code isfam.audio.tmp-dir} 는 tmpfs 여야 합니다</b>
 * (예: {@code /dev/shm/isfam}). 일반 디스크로 두면 파일을 지워도 블록이 남아
 * "저장하지 않는다"는 처리방침과 어긋납니다.
 *
 * <p><b>이 클래스에 "실패한 파일 보관" 같은 기능을 넣지 마세요.</b> 디버깅에 유용해
 * 보이지만, 그 순간 통화 음성이 서버에 축적되기 시작합니다. 재현이 필요하면
 * 오디오가 아니라 {@code spoof_checks} 의 메타데이터로 하세요.
 */
@Slf4j
@Component
public class AudioTempStorage {

    /** 소유자만 읽고 쓸 수 있게. 같은 호스트의 다른 프로세스가 통화 음성을 읽으면 안 됩니다 */
    private static final Set<PosixFilePermission> DIR_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final Clock clock;

    public AudioTempStorage(@Value("${isfam.audio.tmp-dir}") String tmpDir, Clock clock) {
        this.root = Path.of(tmpDir);
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(root);
            trySetPermissions(root, DIR_PERMISSIONS);
            log.info("통화 음성 임시 디렉터리: {} (운영에서는 tmpfs 여야 합니다)", root.toAbsolutePath());
        } catch (IOException e) {
            // 여기서 실패하면 spoof-check 가 통째로 동작하지 않습니다. 기동 시점에 드러나야 합니다.
            throw new IllegalStateException(
                    "임시 오디오 디렉터리를 만들 수 없습니다: " + root.toAbsolutePath(), e);
        }
    }

    /**
     * 업로드된 파일을 tmpfs 에 씁니다.
     *
     * <p>확장자를 유지하는 이유는 FastAPI 가 파일명으로 형식을 검사하기 때문입니다
     * (415 응답 조건). 파일명 자체는 UUID 로 갈아치웁니다 — 클라이언트가 보낸 이름에는
     * 통화 상대 번호나 시각이 들어 있을 수 있고, 그건 식별정보라 서버 파일시스템에
     * 남길 이유가 없습니다.
     */
    public TempAudio receive(MultipartFile file, String extension) {
        Path path = root.resolve("spoof-%s.%s".formatted(UUID.randomUUID(), extension));

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            trySetPermissions(path, FILE_PERMISSIONS);
        } catch (IOException e) {
            // 부분 기록된 파일이 남지 않게 정리하고 나갑니다
            purgeQuietly(path);
            log.error("임시 오디오 저장 실패: {}", path, e);
            throw new BusinessException(ErrorCode.COMMON_008,
                    "음성을 처리하지 못했어요. 잠시 후 다시 시도해 주세요");
        }

        return new TempAudio(path, file.getSize());
    }

    /**
     * 원본 파기. <b>반드시 {@code finally} 에서 호출하세요.</b>
     *
     * @return 파기 완료 시각. 삭제에 실패하면 null 이며, 그 경우 {@code spoof_checks.purged_at}
     * 이 null 로 남아 파기 누락으로 잡힙니다. 조용히 성공한 척하지 않는 게 중요합니다 —
     * 그 컬럼이 유일한 파기 증빙이라, 거짓으로 채우면 증빙 자체가 무의미해집니다.
     */
    public Instant purge(TempAudio audio) {
        if (audio == null) {
            return null;
        }
        try {
            Files.deleteIfExists(audio.path());
            return clock.instant();
        } catch (IOException e) {
            log.error("!!! 통화 음성 원본 파기 실패 — 즉시 확인 필요: {}", audio.path(), e);
            return null;
        }
    }

    private void purgeQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            log.error("!!! 통화 음성 원본 파기 실패 — 즉시 확인 필요: {}", path);
        }
    }

    /** 윈도우 개발 환경에는 POSIX 권한이 없습니다. 운영(리눅스)에서만 적용되면 충분합니다 */
    private void trySetPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("POSIX 권한 설정을 건너뜁니다: {}", path);
        }
    }

    /**
     * tmpfs 에 놓인 오디오 1건.
     *
     * @param sizeBytes {@code spoof_checks.audio_bytes} 로 남습니다 —
     *                  "무엇이 얼마나 들어왔는가"가 접속기록의 항목입니다
     */
    public record TempAudio(Path path, long sizeBytes) {
    }
}