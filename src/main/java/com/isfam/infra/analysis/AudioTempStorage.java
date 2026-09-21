package com.isfam.infra.analysis;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * <p><b>{@code finally} 만으로는 부족합니다</b>(S3-e). 프로세스가 OOM 이나 SIGKILL 로
 * 죽으면 그 블록은 실행되지 않고 tmpfs 에 통화 음성이 그대로 남습니다. 그래서 Phase 11
 * 에서 {@link #purgeAll()}(기동 시 전량 청소)과 {@link #purgeOlderThan(Instant)}
 * (10분 주기 고아 파일 스위퍼)을 추가했습니다. 호출은 {@link AudioTempSweeper} 가 합니다.
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

    /** 우리가 만든 분석용 원본의 접두사 */
    private static final String FILE_PREFIX = "spoof-";

    /**
     * 스위퍼가 훑는 패턴. <b>디렉터리를 통째로 비우지 않습니다.</b>
     *
     * <p>설정을 잘못 건드려 {@code tmp-dir} 이 {@code /tmp} 나 {@code /dev/shm} 자체를
     * 가리키게 되면 전량 삭제는 남의 파일까지 지웁니다. 패턴으로 좁히면 오설정의
     * 피해가 우리 파일로 한정됩니다.
     *
     * <ul>
     *   <li>{@code spoof-*} — 이 클래스가 만든 분석용 원본</li>
     *   <li>{@code upload_*} — <b>톰캣 멀티파트 스풀 파일</b>.
     *       {@code spring.servlet.multipart.location} 을 같은 tmpfs 로,
     *       {@code file-size-threshold} 를 0 으로 잡아 통화 음성이 기본 임시
     *       디렉터리(일반 디스크)로 새지 않게 했습니다(인프라 설계 계획).
     *       정상 종료 시에는 톰캣이 요청 끝에 스스로 지우지만, 프로세스가 죽으면
     *       남습니다 — 우리 파일만 훑으면 이쪽을 놓칩니다</li>
     * </ul>
     */
    private static final String[] SWEEP_GLOBS = {FILE_PREFIX + "*", "upload_*"};

    private static final int RIFF_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int FMT_CHUNK_MIN_BYTES = 16;

    private final Path root;
    private final Clock clock;

    public AudioTempStorage(AudioTempProperties properties, Clock clock) {
        this.root = Path.of(properties.tmpDir());
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

    /** 스위퍼가 로그에 쓸 디렉터리 경로 */
    public Path root() {
        return root;
    }

    /**
     * 업로드된 파일을 tmpfs 에 씁니다.
     *
     * <p>파일명은 UUID 로 갈아치웁니다 — 클라이언트가 보낸 이름에는 통화 상대 번호나
     * 시각이 들어 있을 수 있고, 그건 식별정보라 서버 파일시스템에 남길 이유가 없습니다.
     */
    public TempAudio receive(MultipartFile file, String extension) {
        Path path = root.resolve(FILE_PREFIX + UUID.randomUUID() + "." + extension);

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
     * WAV 헤더를 읽어 길이를 계산합니다. 분석 서버로 보내기 <b>전에</b> 거부하기 위한 것입니다(P-20).
     *
     * <p>기존에는 길이를 FastAPI 응답의 {@code duration_seconds} 로 사후에 알았습니다.
     * 그 시점에는 이미 통화 음성 전체를 받아 분석까지 마친 뒤라, "30초만 받는다"는
     * 주장의 근거가 되지 못했습니다.
     *
     * <p>헤더만 훑으므로 오디오 데이터 자체는 읽지 않습니다.
     *
     * @return 파싱 실패 시 {@link Probe#invalid()}
     */
    public Probe probe(TempAudio audio) {
        if (audio == null) {
            return Probe.invalid();
        }

        try (SeekableByteChannel channel = Files.newByteChannel(audio.path(), StandardOpenOption.READ)) {
            ByteBuffer header = read(channel, RIFF_HEADER_BYTES);
            if (header == null
                    || !"RIFF".equals(tag(header, 0))
                    || !"WAVE".equals(tag(header, 8))) {
                return Probe.invalid();
            }

            long byteRate = 0;
            long dataBytes = -1;
            long position = RIFF_HEADER_BYTES;
            long fileSize = channel.size();

            while (position + CHUNK_HEADER_BYTES <= fileSize) {
                channel.position(position);
                ByteBuffer chunk = read(channel, CHUNK_HEADER_BYTES);
                if (chunk == null) {
                    break;
                }

                String id = tag(chunk, 0);
                long size = Integer.toUnsignedLong(chunk.getInt(4));
                long body = position + CHUNK_HEADER_BYTES;

                if ("fmt ".equals(id) && size >= FMT_CHUNK_MIN_BYTES) {
                    channel.position(body);
                    ByteBuffer fmt = read(channel, FMT_CHUNK_MIN_BYTES);
                    if (fmt == null) {
                        return Probe.invalid();
                    }
                    byteRate = Integer.toUnsignedLong(fmt.getInt(8));
                } else if ("data".equals(id)) {
                    // 스트리밍 파일은 크기를 0 이나 0xFFFFFFFF 로 적어둡니다. 실제 파일 크기로 대체합니다.
                    dataBytes = (size == 0 || size == 0xFFFFFFFFL) ? fileSize - body : size;
                }

                if (byteRate > 0 && dataBytes >= 0) {
                    break;
                }

                // 청크는 짝수 바이트 경계에 정렬됩니다
                position = body + size + (size % 2);
            }

            if (byteRate <= 0 || dataBytes < 0) {
                return Probe.invalid();
            }
            return Probe.of((double) dataBytes / byteRate);

        } catch (IOException e) {
            log.error("WAV 헤더를 읽지 못했습니다: {}", audio.path(), e);
            return Probe.invalid();
        }
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

    /**
     * 남아 있는 파일을 전부 지웁니다. <b>기동 시점 전용</b>(P-22 · S3-③).
     *
     * <p>기동 시점에 이 디렉터리에 파일이 있다는 것은 이전 프로세스가 {@code finally} 를
     * 실행하지 못하고 죽었다는 뜻입니다. 나이를 볼 필요가 없습니다 — 이 JVM 이 만든
     * 파일은 아직 하나도 없으니 전부 고아입니다.
     *
     * <p><b>웹 서버가 요청을 받기 전에 호출해야 합니다.</b> 트래픽이 들어온 뒤에 부르면
     * 방금 업로드된 파일을 지울 수 있습니다.
     *
     * <p><b>같은 tmpfs 를 두 JVM 이 공유하면 안 됩니다.</b> 한쪽 기동이 다른 쪽의
     * 분석 중인 파일을 지웁니다. 호스트당 애플리케이션 1개가 전제입니다.
     */
    public Sweep purgeAll() {
        return sweep(null);
    }

    /**
     * {@code cutoff} 보다 오래된 파일을 지웁니다. 10분 주기 스위퍼용(P-22).
     *
     * <p>살아 있는 동안의 안전망입니다 — 분석 스레드가 {@code finally} 를 못 타고
     * 사라지는 경우에도 파일이 영원히 남지 않게 합니다.
     *
     * @param cutoff 이 시각보다 <b>수정 시각이 이른</b> 파일만 지웁니다.
     *               분석 중인 파일을 지우지 않도록 호출부가 여유를 둡니다
     */
    public Sweep purgeOlderThan(Instant cutoff) {
        return sweep(cutoff);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * @param cutoff null 이면 전량. <b>예외를 던지지 않습니다</b> —
     *               스위퍼가 죽으면 안전망이 사라집니다
     */
    private Sweep sweep(Instant cutoff) {
        int deleted = 0;
        int failed = 0;

        for (String glob : SWEEP_GLOBS) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root, glob)) {
                for (Path path : files) {
                    try {
                        if (!Files.isRegularFile(path)) {
                            continue;
                        }
                        if (cutoff != null
                                && !Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                            continue;
                        }
                        if (Files.deleteIfExists(path)) {
                            deleted++;
                        }
                    } catch (IOException e) {
                        failed++;
                        log.error("!!! tmpfs 잔여 파일을 지우지 못했습니다: {}", path, e);
                    }
                }
            } catch (IOException e) {
                log.error("!!! tmpfs 디렉터리를 읽지 못했습니다: {} (패턴 {})",
                        root.toAbsolutePath(), glob, e);
                failed++;
            }
        }

        return new Sweep(deleted, failed);
    }

    private ByteBuffer read(SeekableByteChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return null;
            }
        }
        return buffer;
    }

    private String tag(ByteBuffer buffer, int offset) {
        return new String(new char[]{
                (char) (buffer.get(offset) & 0xFF),
                (char) (buffer.get(offset + 1) & 0xFF),
                (char) (buffer.get(offset + 2) & 0xFF),
                (char) (buffer.get(offset + 3) & 0xFF)
        });
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

    /**
     * 청소 결과.
     *
     * <p><b>{@code deleted} 도 0 이어야 정상입니다.</b> 지웠다는 건 원래 남아 있었다는
     * 뜻이고, 그건 {@code finally} 가 실행되지 않은 요청이 있었다는 증거입니다.
     *
     * @param deleted 지운 파일 수
     * @param failed  지우지 못한 파일 수. <b>이쪽이 더 심각합니다</b> —
     *                통화 음성이 tmpfs 에 계속 남아 있다는 뜻입니다
     */
    public record Sweep(int deleted, int failed) {

        public boolean isClean() {
            return deleted == 0 && failed == 0;
        }

        public int total() {
            return deleted + failed;
        }
    }

    /**
     * WAV 헤더 파싱 결과.
     *
     * @param valid           RIFF/WAVE 헤더를 읽었는지
     * @param durationSeconds 유효할 때만 값이 있습니다
     */
    public record Probe(boolean valid, Double durationSeconds) {

        static Probe invalid() {
            return new Probe(false, null);
        }

        static Probe of(double durationSeconds) {
            return new Probe(true, durationSeconds);
        }
    }
}