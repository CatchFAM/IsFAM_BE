package com.isfam.domain.analysis.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.analysis.entity.CallEvent;
import com.isfam.domain.analysis.entity.SpoofCheck;
import com.isfam.domain.analysis.repository.CallEventRepository;
import com.isfam.domain.analysis.repository.SpoofCheckRepository;
import com.isfam.domain.device.entity.Device;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.infra.analysis.AntiSpoofingClient;
import com.isfam.infra.analysis.AudioTempStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 260926 — 수집 상한 6초와 분석 서버 실패 기록(P-20 재개).
 *
 * <p>공통 불변식: <b>음성이 tmpfs 에 들어온 요청은 결과가 무엇이든 row 1건 + purged_at</b>.
 */
@ExtendWith(MockitoExtension.class)
class SpoofCheckServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long DEVICE_ID = 2L;
    private static final Long CALL_EVENT_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant PURGED_AT = NOW.plusMillis(300);

    /** 5초 · 16kHz · mono · 16bit + 44B 헤더 */
    private static final long FIVE_SECONDS_BYTES = 160_044L;

    @Mock SpoofCheckRepository spoofCheckRepository;
    @Mock CallEventRepository callEventRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock AntiSpoofingClient antiSpoofingClient;
    @Mock AudioTempStorage audioTempStorage;

    private SpoofCheckService service;

    private final MockMultipartFile file =
            new MockMultipartFile("audio_file", "call.wav", "audio/wav", new byte[]{1});

    @BeforeEach
    void setUp() {
        service = new SpoofCheckService(spoofCheckRepository, callEventRepository,
                deviceRepository, antiSpoofingClient, audioTempStorage,
                Clock.fixed(NOW, ZoneOffset.UTC));

        CallEvent callEvent = mock(CallEvent.class);
        given(callEvent.getId()).willReturn(CALL_EVENT_ID);
        given(deviceRepository.findByIdAndUserId(DEVICE_ID, USER_ID))
                .willReturn(Optional.of(mock(Device.class)));
        given(callEventRepository.findByIdAndUserId(CALL_EVENT_ID, USER_ID))
                .willReturn(Optional.of(callEvent));
        given(audioTempStorage.purge(any())).willReturn(PURGED_AT);
        // lenient — 저장 실패 테스트가 이 스텁을 덮어씁니다
        lenient().when(spoofCheckRepository.save(any(SpoofCheck.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── 분석 서버 실패 (P-20 재개) ──────────────────────────

    @Test
    void analysisServerFailureStillLeavesRowAndRethrows() {
        givenReceived(FIVE_SECONDS_BYTES, 5.0);
        given(antiSpoofingClient.detect(any()))
                .willThrow(new BusinessException(ErrorCode.COMMON_009));

        assertThatThrownBy(this::call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COMMON_009);

        SpoofCheck saved = captureSaved();
        assertThat(saved.getAnalysisStatus()).isEqualTo(SpoofCheck.AnalysisStatus.FAILED);
        assertThat(saved.getOperation()).isEqualTo(SpoofCheck.Operation.DETECT);
        assertThat(saved.getFailureType()).isEqualTo("COMMON_009");
        assertThat(saved.getPurgedAt()).isEqualTo(PURGED_AT);
        assertThat(saved.getDurationSeconds()).isEqualTo(5.0);
        assertThat(saved.getAudioBytes()).isEqualTo((int) FIVE_SECONDS_BYTES);
        assertThat(saved.getSpoofScore()).isNull();
        assertThat(saved.getRemotePurged()).isNull();
        assertThat(saved.getTraceId()).isNotBlank();
    }

    @Test
    void unexpectedExceptionRecordsClassNameOnly() {
        givenReceived(FIVE_SECONDS_BYTES, 5.0);
        given(antiSpoofingClient.detect(any()))
                .willThrow(new IllegalStateException("민감할 수 있는 메시지"));

        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class);

        assertThat(captureSaved().getFailureType()).isEqualTo("IllegalStateException");
    }

    @Test
    void failureToSaveFailureRowDoesNotHideOriginalError() {
        givenReceived(FIVE_SECONDS_BYTES, 5.0);
        given(antiSpoofingClient.detect(any()))
                .willThrow(new BusinessException(ErrorCode.COMMON_009));
        given(spoofCheckRepository.save(any(SpoofCheck.class)))
                .willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(this::call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COMMON_009);
        verify(audioTempStorage).purge(any());
    }

    // ── 6초 상한 ───────────────────────────────────────────

    @Test
    void headerDurationOverSixSecondsIsRejectedBeforeAnalysis() {
        givenReceived(FIVE_SECONDS_BYTES, 6.1);

        assertThatThrownBy(this::call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VOICE_002);

        verify(antiSpoofingClient, never()).detect(any());
        SpoofCheck saved = captureSaved();
        assertThat(saved.getRejectedReason()).isEqualTo(SpoofCheck.RejectedReason.DURATION_EXCEEDED);
        assertThat(saved.getPurgedAt()).isEqualTo(PURGED_AT);
    }

    /** 헤더를 조작해 짧아 보이게 해도 바이트 상한에서 걸립니다 */
    @Test
    void sizeOverLimitIsRejectedWithoutReadingHeader() {
        given(audioTempStorage.receive(any(), any()))
                .willReturn(new AudioTempStorage.TempAudio(Path.of("x.wav"), 197_000L));

        assertThatThrownBy(this::call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VOICE_002);

        verify(audioTempStorage, never()).probe(any());
        verify(antiSpoofingClient, never()).detect(any());
        assertThat(captureSaved().getRejectedReason())
                .isEqualTo(SpoofCheck.RejectedReason.SIZE_EXCEEDED);
    }

    // ── 내부 ──────────────────────────────────────────────

    private void givenReceived(long bytes, double headerSeconds) {
        AudioTempStorage.TempAudio audio = new AudioTempStorage.TempAudio(Path.of("x.wav"), bytes);
        given(audioTempStorage.receive(any(), any())).willReturn(audio);
        given(audioTempStorage.probe(audio)).willReturn(new AudioTempStorage.Probe(true, headerSeconds));
    }

    private void call() {
        service.check(USER_ID, DEVICE_ID, CALL_EVENT_ID, file, "127.0.0.1");
    }

    private SpoofCheck captureSaved() {
        ArgumentCaptor<SpoofCheck> captor = ArgumentCaptor.forClass(SpoofCheck.class);
        verify(spoofCheckRepository).save(captor.capture());
        return captor.getValue();
    }
}