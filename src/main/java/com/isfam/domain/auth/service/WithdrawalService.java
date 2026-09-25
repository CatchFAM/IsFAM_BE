package com.isfam.domain.auth.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.common.util.HashUtil;
import com.isfam.domain.analysis.repository.CallEventRepository;
import com.isfam.domain.analysis.repository.VoiceAnalysisRepository;
import com.isfam.domain.audit.entity.VoiceprintAccessLog;
import com.isfam.domain.audit.service.DataDeletionLogger;
import com.isfam.domain.audit.service.VoiceprintAccessLogger;
import com.isfam.domain.auth.entity.User;
import com.isfam.domain.auth.repository.RefreshTokenRepository;
import com.isfam.domain.auth.repository.UserRepository;
import com.isfam.domain.device.repository.DeviceRepository;
import com.isfam.domain.family.entity.FamilyMembership;
import com.isfam.domain.family.repository.FamilyMemberAliasRepository;
import com.isfam.domain.family.repository.FamilyMembershipRepository;
import com.isfam.domain.notification.repository.NotificationRepository;
import com.isfam.domain.voice.entity.Voiceprint;
import com.isfam.domain.voice.repository.VoiceprintRepository;
import com.isfam.domain.voice.repository.VoiceprintSampleRepository;
import com.isfam.infra.push.event.FamilySyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 회원 탈퇴 — {@code DELETE /auth/me}.
 *
 * <p><b>AuthService 에서 분리한 이유</b>는 이 한 메서드가 리포지토리 10개를 건드리기
 * 때문입니다. AuthService 에 넣으면 로그인·토큰 발급과 탈퇴가 같은 생성자에 섞여,
 * 어느 의존성이 어느 흐름의 것인지 읽히지 않습니다. Phase 14 의
 * {@code DELETE /me/voiceprint}(P-18)도 여기서 "임베딩 삭제 + 가족 폰 회수" 부분을
 * 재사용하게 됩니다.
 *
 * <p><b>명세의 처리 순서를 그대로 따릅니다</b>(api-statement.md, 단일 트랜잭션).
 * 순서가 정해져 있는 건 취향이 아니라 두 가지 때문입니다.
 * <ul>
 *   <li><b>②가 ④보다 먼저</b> — 푸시 대상을 status 변경 전에 모아야 합니다. 바꾼 뒤에
 *       조회하면 본인이 목록에서 빠지고, 그러면 남은 가족 폰이 "이 사람 임베딩을 지워라"를
 *       받지 못해 최대 6시간 동안 탈퇴자의 생체정보가 남습니다</li>
 *   <li><b>③이 가장 먼저 지워지는 데이터</b> — 생체정보는 soft delete 가 허용되지 않습니다
 *       (법 제21조, 복구 불가능한 방법). 뒤로 밀수록 중간에 실패했을 때 남아 있을 확률이
 *       올라갑니다</li>
 * </ul>
 *
 * <p><b>{@code spoof_checks} 는 지우지 않습니다.</b> 접속기록 보관 의무(고시 제8조, 2년)
 * 대상이라 탈퇴로 지울 수 없습니다. 대신 Phase 13 에서 탈퇴자 접속기록을 별도 스키마로
 * 옮기는 작업(P-04, 법 제21조 제3항)이 예정돼 있습니다. {@code call_events} 에 FK 를
 * 걸지 않은 것도 이 때문입니다 — 걸어두면 spoof_checks 가 call_events 를 붙잡아
 * ⑤에서 삭제가 실패합니다.
 *
 * <p><b>{@code user_agreements} 도 남깁니다.</b> 분쟁 대비 보관입니다(erd.md).
 * "동의를 받았다"는 사실은 탈퇴 후에도 증명해야 할 수 있습니다.
 *
 * <p><b>Phase 12 — 기록이 둘 붙었습니다.</b>
 * <ul>
 *   <li>③의 생체정보 파기 → {@code voiceprint_access_logs}(P-28). 사유·모델 버전까지</li>
 *   <li>지운 것 전부 → {@code data_deletion_logs}(P-29). 테이블별 건수를 같은 모양으로</li>
 * </ul>
 * 둘 다 없으면 탈퇴 후 남는 증거가 "row 가 없다"뿐인데, 그건 <b>처음부터 없었던 것과
 * 구분되지 않습니다.</b> 법 제39조가 고의·과실을 추정하므로, 증명 책임은 우리에게 있습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final FamilyMembership.Status ACTIVE = FamilyMembership.Status.ACTIVE;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FamilyMembershipRepository membershipRepository;
    private final FamilyMemberAliasRepository aliasRepository;
    private final VoiceprintRepository voiceprintRepository;
    private final VoiceprintSampleRepository sampleRepository;
    private final VoiceAnalysisRepository voiceAnalysisRepository;
    private final CallEventRepository callEventRepository;
    private final NotificationRepository notificationRepository;
    private final DeviceRepository deviceRepository;
    private final VoiceprintAccessLogger voiceprintAccessLogger;
    private final DataDeletionLogger dataDeletionLogger;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * <p><b>벌크 삭제가 영속성 컨텍스트를 비웁니다.</b> 아래 리포지토리들의
     * {@code @Modifying(clearAutomatically = true, flushAutomatically = true)} 은
     * "밀린 변경을 먼저 flush 하고, 삭제 후 컨텍스트를 clear" 합니다. 그래서
     * <ul>
     *   <li>④에서 바꾼 멤버십 상태는 ⑤의 첫 벌크 삭제가 flush 해 줍니다 — 안전</li>
     *   <li>①에서 읽은 {@code user} 인스턴스는 ⑤를 지나면 <b>준영속</b>이 됩니다.
     *       그 상태로 {@code withdraw()} 를 호출하면 변경이 DB 에 반영되지 않습니다.
     *       ⑧에서 <b>다시 읽는 이유</b>가 이것입니다</li>
     * </ul>
     *
     * @param rawPassword 본인 확인용. 틀리면 AUTH_008
     * @param clientIp    파기 기록의 "접속지"(고시 제8조). 컨트롤러가 뽑아 넘깁니다
     */
    @Transactional
    public void withdraw(Long userId, String rawPassword, String clientIp) {
        Instant startedAt = clock.instant();

        // ── ① 비밀번호 검증 → owner 공간 확인 ──────────────
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_009));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // 로그인 실패 카운트를 올리지 않습니다. 이미 인증된 세션이라 무차별 대입 경로가
            // 아니고, 여기서 계정을 잠그면 본인이 탈퇴를 못 하게 됩니다.
            throw new BusinessException(ErrorCode.AUTH_008);
        }

        Optional<FamilyMembership> membership =
                membershipRepository.findByUserIdAndStatus(userId, ACTIVE);

        // owner 는 공간을 먼저 정리해야 합니다. 그냥 탈퇴시키면 주인 없는 공간에
        // 남은 멤버들이 갇힙니다 — 초대 코드를 돌릴 사람도, 공간을 지울 사람도 없습니다.
        if (membership.isPresent() && membership.get().isOwner()) {
            throw new BusinessException(ErrorCode.FAMILY_005,
                    "가족 공간 관리자는 공간을 삭제한 뒤에 탈퇴할 수 있어요");
        }

        Long familyId = membership.map(FamilyMembership::getFamilyId).orElse(null);

        // ── ② 푸시 대상 수집 (④보다 반드시 먼저) ───────────
        Set<Long> syncTargets = collectSyncTargets(familyId, userId);

        // 지운 건수를 테이블별로 모읍니다. 순서를 유지하려고 LinkedHashMap 입니다 —
        // 조사 시 "어떤 순서로 지웠나"가 그대로 읽힙니다.
        Map<String, Integer> deleted = new LinkedHashMap<>();

        // ── ③ 생체정보 물리 삭제 ───────────────────────────
        // soft delete 가 허용되지 않습니다(법 제21조). 순서상 가장 먼저 지웁니다.
        //
        // 모델 버전을 먼저 꺼냅니다 — 파기 기록에 "무슨 모델로 만든 생체정보를 버렸는가"가
        // 들어가야 하는데, 지운 뒤에는 알 방법이 없습니다.
        String modelVersion = voiceprintRepository.findByUserId(userId)
                .map(Voiceprint::getEmbeddingModelVersion)
                .orElse(null);

        int voiceprints = voiceprintRepository.deleteAllByUserId(userId);
        int samples = sampleRepository.deleteAllByUserId(userId);
        deleted.put(DataDeletionLogger.Tables.VOICEPRINTS, voiceprints);
        deleted.put(DataDeletionLogger.Tables.VOICEPRINT_SAMPLES, samples);

        // 지운 직후에 남깁니다. 기록이 실패하면 삭제도 함께 롤백됩니다(MANDATORY 전파) —
        // 지워졌는데 기록이 없는 상태는 복구할 방법이 없고, 롤백은 재시도하면 됩니다.
        // 등록한 적 없는 사용자면 둘 다 0 이라 기록하지 않습니다.
        voiceprintAccessLogger.recordDestroy(userId, userId,
                VoiceprintAccessLog.Reason.WITHDRAWAL,
                samples, voiceprints, modelVersion, clientIp, startedAt);

        // ── ④ 멤버십 → left ────────────────────────────────
        // row 를 지우지 않습니다. 남은 가족 폰에 removed_user_ids 로 알려야 하므로
        // "예전에 멤버였다"는 사실이 남아 있어야 합니다.
        // 삭제가 아니라 상태 변경이므로 data_deletion_logs 대상이 아닙니다.
        // (③에서 컨텍스트가 clear 됐으므로 ①에서 읽은 membership 은 준영속입니다 — 다시 읽습니다)
        membershipRepository.findByUserIdAndStatus(userId, ACTIVE)
                .ifPresent(FamilyMembership::leave);

        // ── ⑤ 이용 기록 삭제 ───────────────────────────────
        // voice_analyses 를 call_events 보다 먼저 지웁니다 — call_events.voice_analysis_id 가
        // 참조하는 쪽이라 반대로 하면 참조가 떠 있는 순간이 생깁니다.
        deleted.put(DataDeletionLogger.Tables.VOICE_ANALYSES,
                voiceAnalysisRepository.deleteAllByUserId(userId));
        deleted.put(DataDeletionLogger.Tables.CALL_EVENTS,
                callEventRepository.deleteAllByUserId(userId));
        deleted.put(DataDeletionLogger.Tables.NOTIFICATIONS,
                notificationRepository.deleteAllByUserId(userId));

        // 별명은 공간과 무관하게 전량입니다 — 탈퇴자를 부르던 이름도, 그가 남을 부르던
        // 이름도 보유 근거가 사라집니다. viewer·target 양방향으로 지웁니다.
        deleted.put(DataDeletionLogger.Tables.FAMILY_MEMBER_ALIASES,
                aliasRepository.deleteAllByUserId(userId));

        // demo_sessions 는 아직 구현되지 않았습니다. 데모 기능을 붙이는 시점에
        // 여기에 삭제를 추가하세요 — 명세 ⑤ 에 포함된 테이블입니다.

        // ── ⑥ 단말 삭제 ────────────────────────────────────
        // 여기서 push_token 이 사라지므로 ⑨의 푸시는 "남은 가족"에게만 나갑니다.
        deleted.put(DataDeletionLogger.Tables.DEVICES,
                deviceRepository.deleteAllByUserId(userId));

        // ── ⑦ 세션 전량 종료 ───────────────────────────────
        // revoke 는 삭제가 아니라 무효화라 data_deletion_logs 대상이 아닙니다.
        // refresh_tokens 의 실제 파기는 Phase 13 보관기간 배치(90일)가 맡습니다.
        refreshTokenRepository.revokeAllByUserId(userId, startedAt);

        // ── ⑧ 계정 익명화 ──────────────────────────────────
        // 위 벌크 삭제들이 컨텍스트를 비웠으므로 ①의 인스턴스는 준영속입니다. 다시 읽습니다.
        // deletedAt 조건 없이 findById 를 쓰는 이유는, 이 시점에도 아직 deleted_at 이
        // 찍히기 전이지만 조건부 조회를 두 번 쓰면 의도가 흐려지기 때문입니다.
        User managed = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_009));

        managed.withdraw(startedAt, anonymizedPhoneNumber(user.getPhoneNumber(), userId));
        userRepository.saveAndFlush(managed);

        // users 는 row 를 지우지 않지만 파기 대상입니다 — 번호를 해시로 치환하고 이름을
        // 익명화하는 건 "복구 불가능한 방법"(법 제21조)의 한 형태입니다. 건수는 1 입니다.
        deleted.put(DataDeletionLogger.Tables.USERS, 1);

        // ── 파기 기록 (P-29) ───────────────────────────────
        // 전부 지운 뒤에 남깁니다. 0 건인 테이블도 포함합니다 —
        // "확인했는데 지울 게 없었다"도 파기 의무 이행입니다.
        Instant finishedAt = clock.instant();
        dataDeletionLogger.recordWithdrawal(userId, deleted, startedAt, finishedAt);

        // ── ⑨ 커밋 후 동기화 푸시 ──────────────────────────
        // PushDispatcher 가 AFTER_COMMIT 에서 받습니다. 롤백된 탈퇴에 대해 푸시가
        // 나가는 걸 막고, FCM 장애로 탈퇴가 통째로 롤백되는 것도 막습니다.
        // 이게 빠지면 탈퇴해도 남의 폰에 내 생체정보가 최대 6시간 남습니다.
        if (!syncTargets.isEmpty()) {
            eventPublisher.publishEvent(new FamilySyncEvent(
                    syncTargets, FamilySyncEvent.Reason.MEMBER_REMOVED, familyId, startedAt));
        }

        // 건수만 남깁니다. 어떤 사용자의 무엇이 지워졌는지는 userId 로 충분하고,
        // 이름·번호는 로그에 넣지 않습니다(P-24).
        log.info("회원 탈퇴 완료 — userId={}, 파기 {}, syncTargets={}",
                userId, deleted, syncTargets.size());
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 탈퇴 사실을 알려야 할 가족. <b>본인은 뺍니다</b> — ⑥에서 단말이 지워져
     * 받을 폰이 없습니다.
     *
     * <p>소속 공간이 없으면 빈 집합입니다. 그때는 내 임베딩이 남의 폰에 있을 수 없어
     * 회수할 대상도 없습니다.
     */
    private Set<Long> collectSyncTargets(Long familyId, Long userId) {
        if (familyId == null) {
            return Set.of();
        }
        Set<Long> targets = new HashSet<>();
        membershipRepository.findByFamilyIdAndStatusOrderByJoinedAtAsc(familyId, ACTIVE)
                .forEach(item -> targets.add(item.getUserId()));
        targets.remove(userId);
        return targets;
    }

    /**
     * 전화번호를 해시로 치환합니다 — 같은 번호로 재가입할 수 있어야 하는데
     * {@code users.phone_number} 에 unique 제약이 걸려 있기 때문입니다.
     *
     * <p><b>{@code userId} 를 섞는 이유</b>가 중요합니다. 번호만 해시하면,
     * 같은 사람이 재가입했다가 다시 탈퇴할 때 <b>똑같은 해시가 나와 unique 제약에
     * 걸립니다</b>. 두 번째 탈퇴가 실패하고, 그건 사용자가 계정을 지울 수 없다는 뜻입니다.
     *
     * <p>결과는 64자 hex 이고 컬럼은 100자라 들어갑니다.
     *
     * <p>이 치환 때문에 <b>탈퇴자에게는 유출 통지를 보낼 수 없습니다</b>(법 제34조).
     * Phase 15 의 유출 대응 절차서에 그 사실을 명시하기로 되어 있습니다(P-17).
     */
    private String anonymizedPhoneNumber(String phoneNumber, Long userId) {
        return HashUtil.sha256(phoneNumber + "#" + userId);
    }
}