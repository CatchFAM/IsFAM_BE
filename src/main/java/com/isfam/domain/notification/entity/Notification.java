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
 * <p><b>{@code danger_call} 본문에 가족 이름·닉네임을 절대 넣지 마세요.</b>
 * 서버가 발신자 매칭 결과를 안다는 뜻이 되어 legal-compliance.md 5장과 정면으로 모순됩니다.
 * 구체적 안내("엄마 목소리로 확인됨")는 폰 로컬 알림이 담당합니다.
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
     * {@code protection_warning} 은 ERD 에 정의돼 있지만 <b>아직 발송 지점이 없습니다.</b>
     * 명세 어디에도 이 알림을 만드는 API 가 없어서, 값만 열어두고 사용하지 않습니다.
     * (권한 회수 감지 같은 용도로 나중에 붙일 자리입니다)
     */
    public enum Type {
        DANGER_CALL, MEMBER_JOINED, MEMBER_LEFT, VOICEPRINT_REGISTERED, PROTECTION_WARNING;

        /** 응답은 "danger_call" 소문자입니다 */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }

    /** {@code NONE} 이면 앱이 탭해도 이동하지 않습니다 */
    public enum TargetType {
        VOICE_ANALYSIS, FAMILY, SETTINGS, NONE;

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

    /** {@code targetType} 이 {@code VOICE_ANALYSIS} 면 analysis_id */
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
     * <p>service-flow.md 9장 기준 "선택" 항목이지만 넣습니다. 빼면 초대 수락이
     * 알림함에 아무 흔적도 남기지 않아, 사용자 입장에서는 누가 들어왔는지 알 방법이 없습니다.
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

    /** {@code POST /me/voiceprint} 등록 성공 시 (Phase 6) */
    public static Notification voiceprintRegistered(Long userId, String displayName) {
        return new Notification(userId, Type.VOICEPRINT_REGISTERED,
                "목소리가 등록됐어요",
                displayName + "님이 목소리를 등록했어요",
                TargetType.FAMILY, null);
    }

    /**
     * {@code POST /voice-analyses} 에서 {@code final_decision != normal} 일 때 (Phase 8).
     * 명세상 <b>필수</b> 항목입니다.
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