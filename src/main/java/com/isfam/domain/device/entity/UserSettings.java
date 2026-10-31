package com.isfam.domain.device.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.domain.Persistable;

/**
 * 계정 단위 설정 — 사용자당 1행 ({@code user_id} 가 PK).
 *
 * <p><b>왜 devices 가 아니라 여기인가.</b> 예전 {@code /settings} 는 전부 {@code devices} 컬럼이라
 * 폰마다 따로 잡혔습니다. 알림·자동분석은 "이 사람이 원하는 것"이라 계정 단위로 옮겼고,
 * OS 권한 3종(알림·마이크·파일)은 폰마다 실제로 다르므로 {@code devices} 에 그대로 둡니다.
 *
 * <p><b>통화분석 알림(마스터)은 저장하지 않습니다.</b> 위험·확인필요·안전 셋 중 하나라도 켜져 있으면
 * 켜진 것으로 계산합니다({@link #isAnalysisNotificationEnabled}). 따로 저장하면 "마스터 OFF 인데
 * 안전은 ON" 같은, 화면에서 만들 수 없는 조합이 DB 에 생길 수 있습니다.
 * <ul>
 *   <li>마스터 OFF → 셋 다 OFF</li>
 *   <li>마스터 ON → 셋 다 ON (이전 값을 복원하지 않음 — 안전도 켜짐)</li>
 *   <li>하나를 켜면 마스터도 ON, 셋을 다 끄면 마스터도 OFF — 계산값이라 자동으로 맞습니다</li>
 * </ul>
 *
 * <p><b>알림함 규칙</b>(3단계에서 적용) — 토글 OFF 는 "푸시만 안 감, 알림함엔 쌓임"입니다.
 * 예외는 안전 하나로, 안전 OFF 면 알림함에도 남기지 않습니다(정상 통화마다 쌓이므로).
 *
 * <p><b>{@link Persistable} 을 구현한 이유.</b> PK 를 직접 넣는 엔티티라 그냥 {@code save()} 하면
 * Spring Data 가 기존 행으로 보고 merge 합니다. 같은 사용자 행이 이미 있으면 조용히 덮어쓰게 되니,
 * 새로 만든 인스턴스는 INSERT 로 가게 해서 중복이면 PK 위반으로 드러나게 했습니다.
 *
 * <p>기본값은 설정 화면 기본 상태와 같습니다. 컬럼 DEFAULT 도 같은 값이라
 * 마이그레이션({@code db/notification-settings-1.sql})과 어긋나지 않습니다.
 */
@Getter
@Entity
@Table(name = "user_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSettings extends BaseTimeEntity implements Persistable<Long> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** 통화 종료 후 자동 분석. OFF 면 분석 자체가 꺼짐 — 서버도 분석 API 를 거부(5단계) */
    @ColumnDefault("true")
    @Column(name = "auto_analysis_enabled", nullable = false)
    private boolean autoAnalysisEnabled;

    /** 통화분석 알림 — 위험(AI 합성·사칭 의심) */
    @ColumnDefault("true")
    @Column(name = "danger_notification_enabled", nullable = false)
    private boolean dangerNotificationEnabled;

    /** 통화분석 알림 — 확인 필요 */
    @ColumnDefault("true")
    @Column(name = "needs_check_notification_enabled", nullable = false)
    private boolean needsCheckNotificationEnabled;

    /** 통화분석 알림 — 안전 결과. 유일하게 OFF 면 알림함에도 남기지 않는 항목 */
    @ColumnDefault("false")
    @Column(name = "normal_notification_enabled", nullable = false)
    private boolean normalNotificationEnabled;

    /** 가족 소식 — 구성원 참여·탈퇴 (이후 암호문 변경·관리자 위임 추가 예정) */
    @ColumnDefault("true")
    @Column(name = "family_news_enabled", nullable = false)
    private boolean familyNewsEnabled;

    /**
     * 가족이 보낸 위험 통화 공유를 알림창에 띄울지. <b>앱 화면에는 노출하지 않는 항목</b>이고 기본 ON.
     *
     * <p>꺼도 푸시 자체는 계속 보냅니다 — 번호를 받아가라는 신호이기도 해서, 안 보내면 7일 뒤
     * 서버에서 지워져 영영 못 받습니다. 이 값은 푸시의 {@code show_notification} 에만 쓰입니다(3단계).
     */
    @ColumnDefault("true")
    @Column(name = "danger_share_enabled", nullable = false)
    private boolean dangerShareEnabled;

    @Transient
    private boolean isNew = true;

    private UserSettings(Long userId) {
        this.userId = userId;
        this.autoAnalysisEnabled = true;
        this.dangerNotificationEnabled = true;
        this.needsCheckNotificationEnabled = true;
        this.normalNotificationEnabled = false;
        this.familyNewsEnabled = true;
        this.dangerShareEnabled = true;
    }

    /** 가입 시점 기본값. 저장하지 않고 읽기 기본값으로만 써도 됩니다 */
    public static UserSettings defaults(Long userId) {
        return new UserSettings(userId);
    }

    // ── 계산값 ──────────────────────────────────────────────

    /** 통화분석 알림(마스터) — 셋 중 하나라도 켜져 있으면 ON */
    public boolean isAnalysisNotificationEnabled() {
        return dangerNotificationEnabled || needsCheckNotificationEnabled || normalNotificationEnabled;
    }

    // ── 변경 ────────────────────────────────────────────────

    public void changeAutoAnalysis(boolean enabled) {
        this.autoAnalysisEnabled = enabled;
    }

    /** 마스터 토글 — 셋을 한꺼번에. 켤 때도 이전 값을 복원하지 않고 전부 켭니다 */
    public void changeAnalysisNotification(boolean enabled) {
        this.dangerNotificationEnabled = enabled;
        this.needsCheckNotificationEnabled = enabled;
        this.normalNotificationEnabled = enabled;
    }

    public void changeDangerNotification(boolean enabled) {
        this.dangerNotificationEnabled = enabled;
    }

    public void changeNeedsCheckNotification(boolean enabled) {
        this.needsCheckNotificationEnabled = enabled;
    }

    public void changeNormalNotification(boolean enabled) {
        this.normalNotificationEnabled = enabled;
    }

    public void changeFamilyNews(boolean enabled) {
        this.familyNewsEnabled = enabled;
    }

    public void changeDangerShare(boolean enabled) {
        this.dangerShareEnabled = enabled;
    }

    // ── Persistable ─────────────────────────────────────────

    @Override
    public Long getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}