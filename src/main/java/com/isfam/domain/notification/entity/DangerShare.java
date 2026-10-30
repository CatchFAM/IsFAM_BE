package com.isfam.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.Instant;

/**
 * 위험 통화 공유 — {@code POST /notifications/danger-share}.
 *
 * <p><b>의심 번호를 여기 두는 이유.</b> 받는 가족에게 번호를 전해야 하는데, 푸시(FCM)는
 * 구글 미국 서버를 거칩니다. 통화 상대방은 우리와 계약한 회원이 아니라서 국외 이전을 허용할
 * 근거가 없습니다. 그래서 번호는 서울 리전 DB 인 이 테이블에만 두고, 푸시에는
 * {@code share_id} 만 실어 보냅니다. 받는 폰이 {@code GET /notifications/danger-share/{id}}
 * 로 가져갑니다.
 *
 * <p><b>{@code impersonatedUserId} 와 {@code phoneNumber} 는 정확히 하나만 값이 있습니다.</b>
 * <ul>
 *   <li>걸려온 번호가 가족 번호와 같음 → {@code impersonatedUserId} 만. 그 번호는 진짜 가족
 *       번호라서 보내면 가족이 그 가족을 차단합니다</li>
 *   <li>모르는 번호 → {@code phoneNumber} 만</li>
 * </ul>
 * {@code impersonatedUserId} 는 앱이 <b>번호 일치로만</b> 채웁니다. 목소리 유사도로 채우면
 * 화자 대조 결과가 서버에 오게 됩니다(L7-c-7). 서버는 그 출처를 검증할 수 없으므로 앱과의
 * 계약이고, 서버는 "같은 공간의 멤버인가"만 확인합니다.
 *
 * <p><b>수정하지 않는 행입니다.</b> 만들어지고 7일 뒤 파기 배치가 통째로 지웁니다.
 * 그래서 {@code BaseTimeEntity} 를 상속하지 않고 {@code created_at} 만 둡니다
 * ({@code Notification} 과 같은 방식). 받는 사람 명단도 따로 두지 않습니다 — 공유는 항상
 * 공간 전원에게 가므로 "그 공간의 현재 멤버 + 공유 전에 들어온 사람"으로 판정합니다.
 */
@Entity
@Getter
@Table(
        name = "danger_shares",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_danger_shares_analysis",
                columnNames = "analysis_id"),
        indexes = {
                @Index(name = "idx_danger_shares_sender", columnList = "sender_user_id"),
                @Index(name = "idx_danger_shares_created", columnList = "created_at")
        })
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DangerShare {

    /** 받는 가족이 번호를 가져갈 수 있는 기간. 지나면 파기 배치가 행을 지웁니다 */
    public static final Duration READABLE_PERIOD = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 공유한 분석. 같은 분석은 한 번만 공유합니다(409) */
    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;

    /** 공유 시점의 공간. 조회 권한 판정에 씁니다 */
    @Column(name = "family_id", nullable = false)
    private Long familyId;

    /** 걸려온 번호가 가족 번호와 같을 때 그 가족. phoneNumber 와 둘 중 하나만 */
    @Column(name = "impersonated_user_id")
    private Long impersonatedUserId;

    /**
     * 모르는 번호로 걸려온 경우의 의심 번호. 하이픈 없는 숫자만.
     * <b>로그·푸시·예외 메시지에 절대 싣지 마세요.</b>
     */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private DangerShare(Long analysisId, Long senderUserId, Long familyId,
                        Long impersonatedUserId, String phoneNumber) {
        this.analysisId = analysisId;
        this.senderUserId = senderUserId;
        this.familyId = familyId;
        this.impersonatedUserId = impersonatedUserId;
        this.phoneNumber = phoneNumber;
    }

    /** 가족 번호로 위장한 전화 */
    public static DangerShare impersonated(Long analysisId, Long senderUserId,
                                           Long familyId, Long impersonatedUserId) {
        return new DangerShare(analysisId, senderUserId, familyId, impersonatedUserId, null);
    }

    /** 모르는 번호로 걸려온 전화. 번호 형식 정리는 호출 전에 끝내세요 */
    public static DangerShare unknownNumber(Long analysisId, Long senderUserId,
                                            Long familyId, String phoneNumber) {
        return new DangerShare(analysisId, senderUserId, familyId, null, phoneNumber);
    }

    public boolean hasPhoneNumber() {
        return phoneNumber != null;
    }

    /** 7일이 지났으면 파기 배치가 돌기 전이라도 조회를 막습니다 */
    public boolean isReadableAt(Instant now) {
        return createdAt != null && now.isBefore(createdAt.plus(READABLE_PERIOD));
    }
}