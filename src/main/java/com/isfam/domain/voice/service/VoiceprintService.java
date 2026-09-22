package com.isfam.domain.voice.service;

import com.isfam.common.crypto.EmbeddingCipher;
import com.isfam.common.crypto.EmbeddingCodec;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import com.isfam.domain.audit.service.VoiceprintAccessLogger;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.notification.entity.Notification;
import com.isfam.domain.notification.service.NotificationService;
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
 * 내 목소리 등록 · 등록 상태 조회 (Phase 6).
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
 * <p><b>Phase 12 — 생체정보가 들어오고 나가는 두 지점을 기록합니다</b>(P-28).
 * 등록은 수집이고, 모델 계열 변경으로 샘플을 버리는 것은 파기입니다. 둘 다
 * {@code voiceprint_access_logs} 에 남습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceprintService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final VoiceprintRepository voiceprintRepository;
    private final VoiceprintSampleRepository sampleRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final VoiceprintAccessLogger accessLogger;
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
     * 같은 공간 멤버 전원에게 동기화 푸시 + 본인 제외 알림함.
     *
     * <p>소속 공간이 없으면 아무 일도 하지 않습니다 — 등록 자체는 성공입니다.
     *
     * <p>푸시 대상에 <b>본인도 포함</b>합니다. 명세가 "같은 공간 멤버 전원"이고,
     * 본인 폰도 여러 대일 수 있어 다른 기기에서 등록한 결과를 이 폰이 알아야 합니다.
     * 본인 임베딩은 어차피 {@code GET /family/embeddings} 응답에서 제외되므로
     * 한 번 더 깨워도 받아가는 게 없습니다.
     *
     * <p>알림함은 본인을 뺍니다. 방금 녹음을 끝낸 사람에게 "목소리가 등록됐어요"는 소음입니다.
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

        String displayName = userRepository.findById(userId)
                .map(User::getDisplayName)
                .orElse("구성원");

        Set<Long> notifyTargets = new HashSet<>(syncTargets);
        notifyTargets.remove(userId);
        notifyTargets.forEach(memberId ->
                notificationService.create(
                        Notification.voiceprintRegistered(memberId, displayName)));

        eventPublisher.publishEvent(new FamilySyncEvent(
                syncTargets, FamilySyncEvent.Reason.VOICEPRINT_UPDATED, familyId, now));

        log.debug("음성 등록 동기화 — 대상 {}건, 알림 {}건 (familyId={})",
                syncTargets.size(), notifyTargets.size(), familyId);
    }
}