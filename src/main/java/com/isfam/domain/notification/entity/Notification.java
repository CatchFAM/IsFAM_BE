package com.isfam.domain.notification.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 앱 내 알림함.
 *
 * <p><b>{@code BaseTimeEntity} 를 상속하지 않습니다.</b> ERD 상 이 테이블에는
 * {@code created_at} 만 있습니다. 상속하면 쓰지도 않는 {@code updated_at} 컬럼이 붙고,
 * {@code read_at} 을 갱신할 때마다 같이 밀립니다. 그래서 {@code @CreatedDate} 필드
 * 하나만 직접 선언했습니다 — {@code BaseTimeEntity} javadoc 이 지시하는 방식입니다.
 *
 * <p><b>INSERT 지점은 서버 내부뿐입니다.</b> {@code POST /notifications} 같은 API 는
 * 존재하지 않습니다. 있으면 아무나 남에게 가짜 위험 알림을 꽂을 수 있습니다
 * (service-flow.md 9장).
 *
 * <p><b>통화 분석 알림 3종({@code danger_call}·{@code needs_check_call}·{@code normal_call})과
 * {@code danger_share} 본문에 가족 이름·닉네임·번호를 절대 넣지 마세요.</b>
 * 서버가 발신자 매칭 결과를 안다는 뜻이 되어 legal-compliance.md 5장과 정면으로 모순됩니다.
 * 구체적 안내("엄마 목소리로 확인됨", "아빠 번호로 온 전화")는 폰이 만듭니다.
 * 반대로 {@code member_joined}·{@code member_left} 는 서버가 정당하게 아는 사실이라
 * 닉네임을 넣어도 됩니다 — 그 경계가 "통화 분석 결과인가"입니다.
 */
@Entity
@Getter
@Table(
        name = "notifications",
        indexes = @Index(
                name = "idx_notifications_user_created",
                columnList = "user_id, created_at")
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /**
     * 알림 종류.
     *
     * <p><b>통화 분석 결과는 판정별로 나뉩니다</b> (알림 설정 세분화 2단계). 설정 화면에서
     * 위험·확인필요·안전을 따로 켜고 끄기 때문입니다. 전에는 위험·확인필요가 모두
     * {@code DANGER_CALL} 하나였고, 이미 쌓인 확인필요 알림은 {@code DANGER_CALL} 로 남아 있습니다
     * (90일 뒤 파기 배치가 정리).
     *
     * <p>{@code VOICEPRINT_REGISTERED} 는 <b>더 이상 만들지 않습니다.</b> 가족 폰의 임베딩 갱신은
     * 이 알림이 아니라 data-only 동기화 푸시({@code family_sync})가 맡고 있어서, 알림은 소음일 뿐이었습니다.
     * 이미 쌓인 행이 응답에 나갈 수 있어 값은 남겨둡니다.
     *
     * <p>{@code protection_warning} 은 ERD 에 정의돼 있지만 <b>아직 발송 지점이 없습니다.</b>
     *
     * <p><b>값을 추가하면 DB CHECK 제약도 SQL 로 바꿔야 합니다.</b> Hibernate 는 기존 제약을
     * 갱신하지 않습니다 — 현재 정의: {@code db/family-passphrase-1.sql}.
     */
    public enum Type {
        /** 통화 분석 — 위험 (AI 합성·사칭 의심) */
        DANGER_CALL,
        MEMBER_JOINED,
        MEMBER_LEFT,
        /** 더 이상 생성하지 않음 — 기존 행 호환용 */
        VOICEPRINT_REGISTERED,
        PROTECTION_WARNING,
        /** 가족이 위험 통화를 공유함. 받는 사람에게 생성 */
        DANGER_SHARE,
        /** 통화 분석 — 확인 필요 */
        NEEDS_CHECK_CALL,
        /** 통화 분석 — 안전. 설정이 꺼져 있으면 알림함에도 만들지 않음 */
        NORMAL_CALL,
        /** 우리 집 암호 등록·수정. owner 를 뺀 멤버에게 */
        PASSPHRASE_UPDATED;

        /** 응답은 "danger_call" 소문자입니다 */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    /**
     * {@code NONE} 이면 앱이 탭해도 이동하지 않습니다.
     *
     * <p>{@code DANGER_SHARE} 의 {@code target_id} 는 share_id 입니다. 앱은 푸시를 받을 때
     * 폰에 저장해 둔 공유 정보를 이 값으로 찾아 화면을 그립니다 — 서버를 다시 부르지 않으므로
     * 7일 뒤 행이 지워져도 알림함은 그대로 동작합니다.
     *
     * <p>{@code FAMILY_PASSPHRASE} 는 우리 집 암호 화면입니다. 푸시 data 에는 {@code type} 이 없고
     * 이 값으로 화면을 정하므로 {@code FAMILY} 와 나눴습니다.
     */
    public enum TargetType {
        VOICE_ANALYSIS, FAMILY, SETTINGS, NONE, DANGER_SHARE, FAMILY_PASSPHRASE;

        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false, length = 100)
    private String title;

    /** 가족 이름을 넣지 않습니다 — 클래스 javadoc 참고 */
    @Column(nullable = false, length = 200)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private TargetType targetType;

    /** {@code VOICE_ANALYSIS} 면 analysis_id, {@code DANGER_SHARE} 면 share_id */
    @Column(name = "target_id")
    private Long targetId;

    /** null 이면 안 읽음 */
    @Column(name = "read_at")
    private Instant readAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Notification(Long userId, Type type, String title, String body,
                         TargetType targetType, Long targetId) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    // ── 팩토리 ──────────────────────────────────────────────
    // 생성자를 막고 타입별 팩토리만 여는 이유는 문구를 여기 한곳에 묶어두기 위해서입니다.
    // 호출부에서 자유롭게 title/body 를 만들 수 있게 하면, 언젠가 누군가
    // danger_call 본문에 가족 이름을 넣습니다.

    /**
     * {@code POST /invitations/&#123;code&#125;/accept} 성공 시, <b>본인을 제외한</b> 기존 멤버에게.
     *
     * <p>음성 공유 동의 구조의 "신규 구성원 통지" 장치입니다(P-02). 가족 소식 알림을 꺼도
     * <b>알림함에는 반드시 남깁니다</b> — 끄는 건 푸시뿐입니다(3단계).
     */
    public static Notification memberJoined(Long userId, String displayName) {
        return new Notification(userId, Type.MEMBER_JOINED,
                "새 구성원이 참여했어요",
                displayName + "님이 가족 공간에 참여했어요",
                TargetType.FAMILY, null);
    }

    /** {@code DELETE /family/members/&#123;user_id&#125;} 성공 시, 남은 멤버에게 */
    public static Notification memberLeft(Long userId, String displayName) {
        return new Notification(userId, Type.MEMBER_LEFT,
                "구성원이 나갔어요",
                displayName + "님이 가족 공간을 나갔어요",
                TargetType.FAMILY, null);
    }

    /**
     * {@code PUT /family/passphrase} 로 실제로 값이 바뀌었을 때, owner 를 뺀 active 멤버에게.
     *
     * <p><b>질문·정답을 문구에 넣지 마세요.</b> 푸시는 FCM(미국)을 거치고 잠금화면에도 뜹니다.
     * 정답이 알림에 실리면 암호를 정해두는 의미가 없어집니다. 앱은 탭하면 GET 으로 가져갑니다.
     * 그래서 문구를 파라미터로 열지 않았습니다.
     *
     * @param firstTime 처음 정한 것이면 true, 바꾼 것이면 false
     */
    public static Notification passphraseUpdated(Long userId, boolean firstTime) {
        return new Notification(userId, Type.PASSPHRASE_UPDATED,
                firstTime ? "우리 집 암호가 정해졌어요" : "우리 집 암호가 바뀌었어요",
                "가족 공간에서 확인해 보세요",
                TargetType.FAMILY_PASSPHRASE, null);
    }

    /**
     * {@code POST /voice-analyses} 에서 {@code final_decision = danger} 일 때.
     *
     * <p>본문이 "통화 분석 결과를 확인해 보세요" 로 고정돼 있고 파라미터로 열려 있지 않은 건
     * 실수 방지입니다. 여기에 이름을 끼워 넣으려면 이 메서드를 고쳐야 하고,
     * 그러면 리뷰에서 걸립니다.
     */
    public static Notification dangerCall(Long userId, Long analysisId) {
        return new Notification(userId, Type.DANGER_CALL,
                "위험한 통화가 감지됐어요",
                "통화 분석 결과를 확인해 보세요",
                TargetType.VOICE_ANALYSIS, analysisId);
    }

    /** {@code POST /voice-analyses} 에서 {@code final_decision = needs_check} 일 때. 문구 고정 */
    public static Notification needsCheckCall(Long userId, Long analysisId) {
        return new Notification(userId, Type.NEEDS_CHECK_CALL,
                "확인이 필요한 통화가 있어요",
                "통화 분석 결과를 확인해 보세요",
                TargetType.VOICE_ANALYSIS, analysisId);
    }

    /**
     * {@code POST /voice-analyses} 에서 {@code final_decision = normal} 이고 안전 결과 알림이 켜져 있을 때.
     *
     * <p><b>"가족 목소리가 맞아요" 같은 문구를 쓰지 마세요.</b> 서버는 정상 판정만 알 뿐
     * 누구 목소리인지 모르고, 알아서도 안 됩니다(L7-c-5). 정상 판정에는 모르는 사람의
     * 평범한 통화도 포함됩니다. "가족 목소리" 안내는 폰이 로컬 매칭 결과로 만듭니다.
     */
    public static Notification normalCall(Long userId, Long analysisId) {
        return new Notification(userId, Type.NORMAL_CALL,
                "통화 분석이 끝났어요",
                "이상 징후가 발견되지 않았어요",
                TargetType.VOICE_ANALYSIS, analysisId);
    }

    /**
     * {@code POST /notifications/danger-share} 성공 시, 공유한 사람을 제외한 공간 멤버 전원에게.
     *
     * <p><b>문구는 고정입니다.</b> 누가 공유했는지, 누구를 사칭했는지, 어떤 번호였는지는
     * 받는 폰이 {@code alias}(없으면 {@code display_name})로 직접 만듭니다. 별명은 보는
     * 사람마다 달라 서버 문구 하나로 만들 수 없고, 번호는 여기 들어가는 순간 푸시로
     * 미국 서버를 거칩니다.
     */
    public static Notification dangerShare(Long userId, Long shareId) {
        return new Notification(userId, Type.DANGER_SHARE,
                "위험한 통화가 공유됐어요",
                "가족이 위험한 통화를 공유했어요",
                TargetType.DANGER_SHARE, shareId);
    }

    // ── 상태 변경 ───────────────────────────────────────────

    /**
     * 멱등입니다. 이미 읽은 알림에 다시 호출해도 최초 읽은 시각을 유지합니다 —
     * 푸시를 두 번 탭했다고 시각이 뒤로 밀리면 안 됩니다.
     */
    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }
}