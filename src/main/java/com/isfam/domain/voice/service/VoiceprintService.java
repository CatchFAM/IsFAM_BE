package com.isfam.domain.voice.service;

import com.isfam.common.crypto.EmbeddingCipher;
import com.isfam.common.crypto.EmbeddingCodec;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import com.isfam.domain.audit.service.DataDeletionLogger;
import com.isfam.domain.audit.service.VoiceprintAccessLogger;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.voice.dto.VoiceprintDto;
import com.isfam.domain.voice.entity.Voiceprint;
import com.isfam.domain.voice.entity.VoiceprintSample;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import com.isfam.domain.voice.repository.VoiceprintSampleRepository;
import com.isfam.infra.push.event.FamilySyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 내 목소리 등록 · 등록 상태 조회 (Phase 6) · 삭제 (Phase 14).
 *
 * <p><b>이 서비스가 다루는 임베딩은 전부 "본인 것"입니다.</b> 통화 상대방의 임베딩은
 * 어떤 경로로도 여기 들어오지 않습니다 — 그건 발신자 식별에 해당해 상대방 동의를 받을 수
 * 없고, 법 제23조 예외도 적용되지 않습니다(legal-compliance.md 3장).
 * 이 클래스에 {@code matched_user_id} 나 {@code similarity_score} 가 등장하면 설계가 틀린 겁니다.
 *
 * <p>저장은 두 단계입니다.
 * <ol>
 *   <li>{@code voiceprint_samples} — 문장별 원본 임베딩. 같은 {@code sentence_id} 는 교체</li>
 *   <li>{@code voiceprints} — 위 샘플들의 <b>평균</b>. 가족에게 배포되는 건 이 값입니다</li>
 * </ol>
 *
 * <p>둘 다 {@link EmbeddingCipher} 로 암호화해 저장합니다. 평문 벡터가 DB 에 닿는 경로를
 * 만들지 마세요.
 *
 * <p>등록에 성공하면 같은 공간 멤버 전원에게 동기화 푸시가 나갑니다 — 가족 폰이
 * {@code GET /family/embeddings} 를 다시 불러 갱신된 평균을 받아가야 하기 때문입니다.
 * 푸시는 {@link FamilySyncEvent} 발행으로 끝내고 실제 발송은 커밋 후에 일어납니다.
 *
 * <p><b>등록·삭제 모두 가족 알림함은 만들지 않습니다.</b> 등록 알림("○○님이 목소리를
 * 등록했어요")은 알림 설정 세분화 2단계에서 없앴습니다. 가족 폰의 임베딩 갱신은 data-only
 * 동기화 푸시가 맡고 있어, 알림은 사용자에게 소음일 뿐이었습니다.
 *
 * <p><b>Phase 12 — 생체정보가 들어오고 나가는 두 지점을 기록합니다</b>(P-28).
 * 등록은 수집이고, 모델 계열 변경으로 샘플을 버리는 것은 파기입니다. 둘 다
 * {@code voiceprint_access_logs} 에 남습니다.
 *
 * <p><b>Phase 14 — 목소리만 지우는 경로가 생겼습니다</b>(P-18, {@link #delete}).
 * 전에는 음성을 빼려면 회원 탈퇴밖에 없었습니다(L12-c, 법 제37조 처리정지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceprintService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final VoiceprintRepository voiceprintRepository;
    private final VoiceprintSampleRepository sampleRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final VoiceprintAccessLogger accessLogger;
    private final DataDeletionLogger dataDeletionLogger;
    private final EmbeddingCipher embeddingCipher;
    private final ModelVersionPolicy modelVersionPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ── 등록 / 재등록 ───────────────────────────────────────

    /**
     * {@code POST /me/voiceprint}.
     *
     * <p><b>검사 순서</b> — 모델 버전(MODEL_001) → 음질(VOICE_001) → 임베딩 형식(VOICE_001).
     * 버전을 먼저 보는 이유는 그게 재시도로 해결되지 않는 유일한 조건이기 때문입니다.
     * 음질 미달은 다시 녹음하면 되지만, 구버전 앱은 몇 번을 녹음해도 통과하지 못합니다 —
     * 앱이 "업데이트해 주세요"로 곧장 분기해야 합니다.
     *
     * <p><b>공간 소속 여부와 무관하게 등록됩니다</b>(명세 [정책]). 혼자 쓰는 사용자도
     * 나중에 초대를 받으면 재등록 없이 바로 공유됩니다.
     *
     * @param clientIp 접속기록의 "접속지". 컨트롤러가 {@code ClientIpResolver} 로 뽑아 넘깁니다
     */
    @Transactional
    public VoiceprintDto.RegisterResponse register(
            Long userId, VoiceprintDto.RegisterRequest request, String clientIp) {

        Instant now = clock.instant();

        modelVersionPolicy.requireSupported(request.embeddingModelVersion());

        if (VoiceQualityPolicy.isRejected(request.audioQuality())) {
            throw new BusinessException(ErrorCode.VOICE_001);
        }

        float[] embedding = decodeOrReject(request.embedding());

        // ── 모델 계열이 바뀌었으면 기존 샘플을 버립니다 ──────────
        // 다른 모델로 뽑은 벡터끼리 평균을 내면 아무 의미 없는 값이 나오는데,
        // 등록은 성공하고 탐지만 조용히 망가집니다.
        // deleteAllByUserId 는 영속성 컨텍스트를 비우므로(clearAutomatically) 이 시점 이후에
        // 엔티티를 다시 읽습니다 — 위에서 읽어둔 인스턴스는 준영속이 됩니다.
        //
        // Phase 12 — 기존 버전 문자열을 미리 꺼내 둡니다. 파기 기록에 "무슨 모델로 만든
        // 생체정보를 버렸는가"가 들어가야 하는데, 지운 뒤에는 알 방법이 없습니다.
        String existingVersion = voiceprintRepository.findByUserId(userId)
                .map(Voiceprint::getEmbeddingModelVersion)
                .orElse(null);

        boolean modelChanged = existingVersion != null
                && !modelVersionPolicy.sameFamily(existingVersion, request.embeddingModelVersion());

        if (modelChanged) {
            int removed = sampleRepository.deleteAllByUserId(userId);

            // 버리는 것도 생체정보 파기입니다(법 제21조). voiceprints row 는 아래에서
            // 새 평균으로 갱신되므로 사라지지 않습니다 — affectedVoiceprints 가 0 인 이유.
            accessLogger.recordDestroy(userId, userId,
                    VoiceprintAccessLog.Reason.MODEL_CHANGED,
                    removed, 0, existingVersion, clientIp, now);

            log.info("모델 계열 변경으로 기존 샘플 {}건 폐기 (userId={}, newVersion={})",
                    removed, userId, request.embeddingModelVersion());
        }

        // ── 샘플 upsert ─────────────────────────────────────
        VoiceprintSample.Quality quality = VoiceQualityPolicy.evaluate(request.audioQuality());
        float qualityScore = VoiceQualityPolicy.score(request.audioQuality());
        byte[] encrypted = embeddingCipher.encryptFloats(embedding);

        VoiceprintSample sample = sampleRepository
                .findByUserIdAndSentenceId(userId, request.sentenceId())
                .map(found -> {
                    found.replace(encrypted, quality, qualityScore);
                    return found;
                })
                .orElseGet(() -> VoiceprintSample.of(
                        userId, request.sentenceId(), encrypted, quality, qualityScore));

        try {
            sampleRepository.saveAndFlush(sample);
        } catch (DataIntegrityViolationException e) {
            // 같은 문장을 앱이 거의 동시에 두 번 보낸 경우.
            // uq_voiceprint_samples_user_sentence 가 잡아줍니다. 앱이 재시도하면 교체 경로로 붙습니다.
            throw new BusinessException(ErrorCode.VOICE_001,
                    "같은 문장이 처리 중이에요. 잠시 후 다시 시도해 주세요");
        }

        // ── 평균 재계산 → voiceprints 갱신 ──────────────────
        List<VoiceprintSample> samples = sampleRepository.findByUserIdOrderBySentenceIdAsc(userId);
        float[] mean = EmbeddingCodec.average(samples.stream()
                .map(item -> embeddingCipher.decryptToFloats(item.getEmbedding()))
                .toList());

        byte[] encryptedMean = embeddingCipher.encryptFloats(mean);

        Voiceprint voiceprint = voiceprintRepository.findByUserId(userId)
                .map(found -> {
                    found.updateEmbedding(encryptedMean, request.embeddingModelVersion(),
                            samples.size(), now);
                    return found;
                })
                .orElseGet(() -> Voiceprint.register(userId, encryptedMean,
                        request.embeddingModelVersion(), samples.size(), now));

        // updated_at 이 이 트랜잭션 안에서 확정돼야 합니다 —
        // GET /family/embeddings 의 since 판정이 이 값을 기준으로 돌아갑니다(Phase 7).
        voiceprintRepository.saveAndFlush(voiceprint);

        // ── 수집 기록 (P-28) ───────────────────────────────
        // 저장이 확정된 뒤에 남깁니다. 앞에서 남기면 아래가 실패했을 때
        // "수집했다"는 기록만 남고 실제 데이터는 없는 상태가 기록에 박힙니다.
        // (같은 트랜잭션이라 실제로는 함께 롤백되지만, 순서를 맞춰두면 읽기도 쉽습니다)
        accessLogger.recordRegister(userId, request.sentenceId(), samples.size(),
                voiceprint.getEmbeddingModelVersion(), clientIp, now);

        publishSync(userId, now);

        log.debug("음성 등록 — userId={}, sentenceId={}, quality={}, sampleCount={}",
                userId, request.sentenceId(), quality, samples.size());

        return new VoiceprintDto.RegisterResponse(
                true,
                voiceprint.getEmbeddingModelVersion(),
                voiceprint.getSampleCount(),
                voiceprint.getRegisteredAt());
    }

    // ── 상태 조회 ───────────────────────────────────────────

    /**
     * {@code GET /me/voiceprint}.
     *
     * <p>미등록이면 404 가 아니라 {@code voiceprint_registered=false} 인 200 입니다.
     * "아직 등록 안 한 상태"는 정상 상태이고, 앱은 이 응답으로 온보딩 화면을 그립니다.
     *
     * <p><b>기록을 남기지 않습니다.</b> 이 응답에는 임베딩이 실리지 않습니다 —
     * 등록 여부·샘플 수·문장별 품질뿐입니다. 생체정보가 서버 밖으로 나가는 경로가 아니라
     * {@code voiceprint_access_logs} 의 대상이 아니고, 온보딩 화면이 뜰 때마다 불리는
     * API 라 기록하면 접속기록이 의미 없는 row 로 덮입니다.
     */
    @Transactional(readOnly = true)
    public VoiceprintDto.StatusResponse getStatus(Long userId) {
        Optional<Voiceprint> voiceprint = voiceprintRepository.findByUserId(userId);
        if (voiceprint.isEmpty()) {
            return VoiceprintDto.StatusResponse.notRegistered();
        }

        Voiceprint found = voiceprint.get();
        List<VoiceprintDto.SampleItem> samples =
                sampleRepository.findByUserIdOrderBySentenceIdAsc(userId).stream()
                        .map(VoiceprintDto.SampleItem::from)
                        .toList();

        return new VoiceprintDto.StatusResponse(
                true,
                found.getEmbeddingModelVersion(),
                found.getSampleCount(),
                samples,
                found.getRegisteredAt());
    }

    // ── 삭제 ────────────────────────────────────────────────

    /**
     * {@code DELETE /me/voiceprint} — 계정은 두고 목소리만 지웁니다 (Phase 14, P-18).
     *
     * <p><b>가족 폰 회수는 따로 만들 필요가 없었습니다.</b> {@code GET /family/embeddings} 가
     * 음성이 없는 멤버를 since 와 무관하게 {@code removed_user_ids} 에 넣기 때문에,
     * 여기서 지우고 동기화 푸시만 보내면 가족 폰이 알아서 지웁니다. 푸시가 유실돼도
     * 6시간 주기 동기화에서 정리됩니다.
     *
     * <p><b>등록한 적 없어도 204 입니다(멱등).</b> 앱이 "삭제" 버튼을 두 번 눌렀거나
     * 첫 응답을 못 받고 재시도한 경우에 404 를 주면, 이미 원하는 상태인데 에러 화면을 보게 됩니다.
     *
     * <p><b>기록은 둘입니다.</b> 탈퇴(WithdrawalService ③)와 같은 모양입니다.
     * <ul>
     *   <li>{@code voiceprint_access_logs} DESTROY(USER_REQUEST) — 지운 게 있을 때만</li>
     *   <li>{@code data_deletion_logs} USER_REQUEST — 테이블별로, <b>0건이어도</b>.
     *       "요청을 받았고 확인했더니 지울 게 없었다"도 파기 요구 처리입니다(법 제36조)</li>
     * </ul>
     * 둘 다 같은 트랜잭션이라 기록이 실패하면 삭제도 롤백됩니다 — 지워졌는데 기록이 없는
     * 상태는 되돌릴 수 없고, 롤백은 사용자가 다시 누르면 됩니다.
     *
     * <p>공간 멤버십과 음성 공유 동의({@code voice_sharing_consented_at})는 건드리지 않습니다.
     * 다시 등록하면 동의가 살아 있어 바로 공유됩니다. 동의 자체를 거두려면 공간을 나가야 합니다.
     *
     * @param clientIp 파기 기록의 "접속지"(고시 제8조)
     */
    @Transactional
    public void delete(Long userId, String clientIp) {
        Instant startedAt = clock.instant();

        // 푸시 대상과 모델 버전은 지우기 전에 모읍니다. 아래 벌크 삭제가 영속성 컨텍스트를
        // 비우므로(clearAutomatically), 여기서 읽은 엔티티는 이후 준영속입니다 — 값만 씁니다.
        Optional<FamilyMembership> membership =
                membershipRepository.findByUserIdAndStatus(userId, ACTIVE);

        String modelVersion = voiceprintRepository.findByUserId(userId)
                .map(Voiceprint::getEmbeddingModelVersion)
                .orElse(null);

        // ── 물리 삭제 ───────────────────────────────────────
        // 생체정보는 soft delete 가 허용되지 않습니다(법 제21조, 복구 불가능한 방법).
        int voiceprints = voiceprintRepository.deleteAllByUserId(userId);
        int samples = sampleRepository.deleteAllByUserId(userId);

        // ── 기록 ────────────────────────────────────────────
        accessLogger.recordDestroy(userId, userId,
                VoiceprintAccessLog.Reason.USER_REQUEST,
                samples, voiceprints, modelVersion, clientIp, startedAt);

        Instant finishedAt = clock.instant();
        dataDeletionLogger.recordUserRequest(userId,
                DataDeletionLogger.Tables.VOICEPRINTS, voiceprints, startedAt, finishedAt);
        dataDeletionLogger.recordUserRequest(userId,
                DataDeletionLogger.Tables.VOICEPRINT_SAMPLES, samples, startedAt, finishedAt);

        // ── 가족 폰 회수 푸시 ───────────────────────────────
        // 지운 게 없으면 가족 폰에도 있을 수 없어 보내지 않습니다.
        if (voiceprints > 0 || samples > 0) {
            membership.ifPresent(found -> publishRemoval(found.getFamilyId(), userId, startedAt));
        }

        log.info("음성 삭제 — userId={}, voiceprints={}, samples={}", userId, voiceprints, samples);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * base64 → float32[192]. 형식이 틀리면 VOICE_001 입니다.
     *
     * <p>명세는 이 코드를 "음성 품질 미달"로 정의하지만, 차원 불일치도 같은 코드입니다
     * (에러 목록: "embedding 이 192개가 아니거나 is_analyzable=false").
     * 앱 입장에서는 둘 다 "이 녹음은 못 쓴다"라 분기가 같습니다.
     */
    private float[] decodeOrReject(String base64) {
        try {
            return EmbeddingCodec.decodeBase64(base64);
        } catch (IllegalArgumentException e) {
            // e.getMessage() 를 넣지 마세요 — Base64 디코더가 실패 지점의 원문 조각을
            // 메시지에 담습니다. 임베딩은 생체인식 특징정보라 조각도 로그에 남기지 않습니다.
            log.debug("임베딩 디코딩 실패 — 형식 오류");
            throw new BusinessException(ErrorCode.VOICE_001,
                    "음성 데이터 형식이 올바르지 않아요. 다시 녹음해 주세요");
        }
    }

    /**
     * 같은 공간 멤버 전원에게 동기화 푸시.
     *
     * <p>소속 공간이 없으면 아무 일도 하지 않습니다 — 등록 자체는 성공입니다.
     *
     * <p>푸시 대상에 <b>본인도 포함</b>합니다. 명세가 "같은 공간 멤버 전원"이고,
     * 본인 폰도 여러 대일 수 있어 다른 기기에서 등록한 결과를 이 폰이 알아야 합니다.
     * 본인 임베딩은 어차피 {@code GET /family/embeddings} 응답에서 제외되므로
     * 한 번 더 깨워도 받아가는 게 없습니다.
     *
     * <p><b>알림함은 만들지 않습니다</b>(알림 설정 세분화 2단계). data-only 푸시라
     * 알림창에도 아무것도 뜨지 않고, 앱이 백그라운드에서 임베딩만 갱신합니다.
     */
    private void publishSync(Long userId, Instant now) {
        Optional<FamilyMembership> membership =
                membershipRepository.findByUserIdAndStatus(userId, ACTIVE);
        if (membership.isEmpty()) {
            return;
        }

        Long familyId = membership.get().getFamilyId();

        Set<Long> syncTargets = new HashSet<>();
        membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .forEach(item -> syncTargets.add(item.getUserId()));

        eventPublisher.publishEvent(new FamilySyncEvent(
                syncTargets, FamilySyncEvent.Reason.VOICEPRINT_UPDATED, familyId, now));

        log.debug("음성 등록 동기화 — 대상 {}건 (familyId={})", syncTargets.size(), familyId);
    }

    /**
     * 음성 삭제 후 가족 폰 회수 푸시.
     *
     * <p><b>본인은 뺍니다</b> — 본인 임베딩은 원래 본인 폰으로 내려가지 않습니다
     * ({@code GET /family/embeddings} 가 본인 제외).
     *
     * <p><b>알림함은 만들지 않습니다.</b> "목소리를 지웠어요"를 가족에게 알리는 건 본인이 원한
     * 동작이 아니고, 처리정지 요구를 남에게 공개하는 셈입니다. data-only 푸시라 알림창에도
     * 아무것도 뜨지 않습니다.
     */
    private void publishRemoval(Long familyId, Long userId, Instant now) {
        Set<Long> syncTargets = new HashSet<>();
        membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .forEach(item -> syncTargets.add(item.getUserId()));
        syncTargets.remove(userId);

        if (syncTargets.isEmpty()) {
            return;
        }

        eventPublisher.publishEvent(new FamilySyncEvent(
                syncTargets, FamilySyncEvent.Reason.VOICEPRINT_DELETED, familyId, now));

        log.debug("음성 삭제 동기화 — 대상 {}건 (familyId={})", syncTargets.size(), familyId);
    }
}