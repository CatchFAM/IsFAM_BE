package com.isfam.domain.voice.service;

import com.isfam.common.crypto.EmbeddingCipher;
import com.isfam.common.crypto.EmbeddingCodec;
import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.model.ModelVersionPolicy;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.voice.dto.EmbeddingSyncDto;
import com.isfam.domain.voice.entity.Voiceprint;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code GET /family/embeddings} — 가족 구성원 임베딩 다운로드 (Phase 7).
 *
 * <p><b>호출 시점은 넷입니다</b>(명세 [정책]) — ① 공간 참여 직후, ② 동기화 푸시 수신,
 * ③ WorkManager 6시간 주기, ④ 앱 포그라운드 진입. ③이 있는 이유는 푸시가 유실되기 때문이고,
 * 그래서 푸시가 실패해도 최대 6시간 안에는 정리됩니다.
 *
 * <p><b>{@code removed_user_ids} 가 이 API 의 절반입니다.</b> 임베딩을 나눠주는 것보다
 * 회수하는 쪽이 법적으로 더 중요합니다 — 탈퇴·강퇴한 사람의 생체정보가 남의 폰에 남아 있으면
 * 그 시점부터 근거 없는 보유가 됩니다. 그래서 애매하면 <b>회수 쪽으로 판단</b>합니다.
 *
 * <p><b>배포 조건 세 가지를 모두 만족해야 임베딩이 나갑니다.</b>
 * <ol>
 *   <li>{@code status = ACTIVE}</li>
 *   <li>{@code voice_sharing_consented_at != null} — 제3자 제공 동의(법 제23조).
 *       {@code POST /family} 로 공간을 만든 owner 는 이 값이 null 일 수 있습니다
 *       ({@code FamilyMembership.voiceSharingConsentedAt} 주석 참고).
 *       동의 없이 임베딩을 내보내면 그 순간이 위법입니다</li>
 *   <li>{@code voiceprints} row 존재</li>
 * </ol>
 * 하나라도 어긋나면 {@code members} 가 아니라 {@code removed_user_ids} 에 들어갑니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingSyncService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final FamilyMembershipRepository membershipRepository;
    private final VoiceprintRepository voiceprintRepository;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final EmbeddingCipher embeddingCipher;
    private final ModelVersionPolicy modelVersionPolicy;
    private final Clock clock;

    /**
     * @param rawSince 지난 응답의 {@code synced_at} 을 그대로. 최초엔 null(전량)
     */
    @Transactional(readOnly = true)
    public EmbeddingSyncDto.EmbeddingsResponse getFamilyEmbeddings(
            Long userId, Long deviceId, String rawSince) {

        requireMyDevice(userId, deviceId);

        Instant since = parseSince(rawSince);

        // synced_at 은 조회를 "시작하기 전" 시각입니다.
        // 조회가 끝난 뒤 시각을 찍으면 그 사이에 커밋된 변경이 다음 since 보다 과거가 되어
        // 영영 내려가지 않습니다. 앞에서 찍으면 최악의 경우 같은 걸 한 번 더 받을 뿐입니다.
        Instant syncedAt = clock.instant();

        FamilyMembership myMembership = membershipRepository
                .findByUserIdAndStatus(userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_002));

        Long familyId = myMembership.getFamilyId();

        // status 를 안 가리고 전부 가져옵니다 — 나간 사람이 removed_user_ids 의 재료입니다
        List<FamilyMembership> all = membershipRepository.findByFamilyId(familyId);

        List<FamilyMembership> candidates = all.stream()
                .filter(item -> !item.getUserId().equals(userId))   // 본인 제외
                .filter(FamilyMembership::isActive)
                .toList();

        Set<Long> candidateIds = candidates.stream()
                .map(FamilyMembership::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, User> users = candidateIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, Voiceprint> voiceprints = candidateIds.isEmpty()
                ? Map.of()
                : voiceprintRepository.findByUserIdIn(candidateIds).stream()
                .collect(Collectors.toMap(Voiceprint::getUserId, Function.identity()));

        List<EmbeddingSyncDto.MemberEmbedding> members = new ArrayList<>();
        Set<Long> removed = new LinkedHashSet<>();

        for (FamilyMembership membership : candidates) {
            Long memberId = membership.getUserId();
            User user = users.get(memberId);
            Voiceprint voiceprint = voiceprints.get(memberId);

            // 동의가 없거나(제3자 제공 미동의) 음성이 없으면 회수 대상입니다.
            // since 와 무관하게 매번 넣습니다 — "음성이 삭제된 시각"을 서버가 알 방법이 없어서,
            // since 로 거르면 삭제 이후 첫 동기화를 놓친 폰에 임베딩이 영구히 남습니다.
            // 등록한 적 없는 멤버도 여기 들어가지만, 앱 입장에서는 지울 게 없어 무해합니다.
            if (user == null || voiceprint == null
                    || membership.getVoiceSharingConsentedAt() == null) {
                removed.add(memberId);
                continue;
            }

            Instant updatedAt = latestOf(
                    voiceprint.getUpdatedAt(),
                    user.getUpdatedAt(),
                    membership.getUpdatedAt());

            // since 이후 변경이 없으면 보내지 않습니다. 같은 값을 매번 내려보내면
            // 6시간마다 가족 전원의 생체정보가 네트워크를 왕복합니다.
            if (since != null && updatedAt != null && !updatedAt.isAfter(since)) {
                continue;
            }

            members.add(new EmbeddingSyncDto.MemberEmbedding(
                    memberId,
                    user.getDisplayName(),
                    EmbeddingCodec.encodeBase64(
                            embeddingCipher.decryptToFloats(voiceprint.getSpeakerEmbedding())),
                    updatedAt));
        }

        // ── 나간 사람 ───────────────────────────────────────
        // since 가 없으면(최초·재설치) 전 이력을 내려보냅니다. 정원이 8명이라 크기가 뻔하고,
        // 재설치 직전 로컬에 남아 있던 잔여 데이터를 앱이 확실히 정리할 수 있습니다.
        all.stream()
                .filter(item -> !item.isActive())
                .filter(item -> !item.getUserId().equals(userId))
                .filter(item -> since == null
                        || item.getUpdatedAt() == null
                        || item.getUpdatedAt().isAfter(since))
                .forEach(item -> removed.add(item.getUserId()));

        String modelVersion = resolveModelVersion(voiceprints.values());

        log.debug("임베딩 동기화 — members {}건, removed {}건 (userId={}, familyId={}, since={})",
                members.size(), removed.size(), userId, familyId, since);

        return new EmbeddingSyncDto.EmbeddingsResponse(
                modelVersion, syncedAt, members, List.copyOf(removed));
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 본인 단말인지 확인합니다. 없는 id 든 남의 단말이든 같은 DEVICE_002(401)입니다 —
     * 존재 여부를 흘리지 않고, 앱의 복구 동작(POST /devices 재등록)도 동일합니다.
     *
     * <p>여기서 굳이 확인하는 이유는 이 응답이 가족의 생체정보를 담기 때문입니다.
     * access_token 만으로 충분해 보이지만, 단말 확인을 붙여두면 어느 기기가 언제 임베딩을
     * 받아갔는지 {@code embedding_synced_at} 으로 추적됩니다.
     */
    private void requireMyDevice(Long userId, Long deviceId) {
        deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_002,
                        "단말 정보를 확인할 수 없어요. 앱을 다시 실행해 주세요"));
    }

    /**
     * {@code since} 는 지난 응답의 {@code synced_at} 을 그대로 되돌려받는 값이라
     * 항상 ISO-8601 UTC 입니다. 형식이 깨졌으면 <b>전량 조회로 흘리지 않고 400</b> 입니다 —
     * 조용히 무시하면 앱은 증분을 받았다고 믿는데 서버는 전량을 보내는, 진단이 어려운
     * 상태가 됩니다.
     */
    private Instant parseSince(String rawSince) {
        if (!StringUtils.hasText(rawSince)) {
            return null;
        }
        try {
            return Instant.parse(rawSince.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COMMON_001,
                    "since 형식이 올바르지 않아요. 지난 응답의 synced_at 을 그대로 보내주세요");
        }
    }

    /** 셋 중 가장 나중. null 은 무시합니다 */
    private Instant latestOf(Instant... values) {
        Instant latest = null;
        for (Instant value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    /**
     * 응답의 {@code embedding_model_version}.
     *
     * <p>가족 구성원이 모두 같은 버전을 쓰는 게 정상입니다. 앱 업데이트 시차로 섞이면
     * 서버 최신 버전을 내려보내고 경고를 남깁니다 — 앱이 자기 모델과 다른 버전의 임베딩을
     * 받으면 대조 결과를 신뢰할 수 없어서, 그 상황을 감지할 근거가 응답에 있어야 합니다.
     */
    private String resolveModelVersion(java.util.Collection<Voiceprint> voiceprints) {
        Set<String> versions = voiceprints.stream()
                .map(Voiceprint::getEmbeddingModelVersion)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        if (versions.size() == 1) {
            return versions.iterator().next();
        }
        if (versions.size() > 1) {
            log.warn("가족 내 임베딩 모델 버전이 섞여 있습니다: {}", versions);
        }
        return modelVersionPolicy.getLatestVersion();
    }
}