package com.isfam.domain.family.entity;

import com.isfam.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 우리 집 암호 — 가족끼리 정해두는 확인 질문과 정답. <b>공간당 1개</b>입니다.
 *
 * <p>가족 목소리가 의심될 때 통화 중에 이 질문을 상대방에게 직접 물어보는 용도입니다.
 * <b>서버는 통화 내용과 대조하지 않습니다</b> — 저장하고 내려줄 뿐입니다.
 *
 * <p><b>정답은 암호화해서 저장합니다</b>({@code PassphraseCipher}, AAD = family_id).
 * 질문은 평문입니다. 앱의 프리셋("우리 집 첫 강아지 이름은?") 아니면 owner 가 직접 쓴 문장이라
 * 질문만으로는 답을 알 수 없습니다.
 *
 * <p><b>작성자 컬럼을 두지 않았습니다.</b> 쓸 수 있는 사람이 owner 하나라 기록할 가치가 낮고,
 * user 를 참조하는 순간 회원 탈퇴 처리 대상이 늘어납니다. 관리자 위임이 생겨도
 * 새 owner 가 그대로 수정 권한을 이어받으면 되므로 이 테이블은 바뀌지 않습니다.
 *
 * <p><b>공간 삭제 시 물리 삭제</b>합니다. 공간이 사라지면 보유 근거가 없습니다.
 * 나가기·강퇴로는 지우지 않습니다 — 공간에 남은 가족이 계속 씁니다.
 */
@Getter
@Entity
@Table(name = "family_passphrases", uniqueConstraints = @UniqueConstraint(
        name = "uq_passphrase_family", columnNames = "family_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyPassphrase extends BaseTimeEntity {

    public static final int QUESTION_MAX_LENGTH = 100;
    public static final int ANSWER_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(nullable = false, length = QUESTION_MAX_LENGTH)
    private String question;

    /** AES-256-GCM 암호문. 평문은 {@code PassphraseCipher.decrypt(bytes, familyId)} 로만 얻습니다 */
    @Column(name = "answer_encrypted", nullable = false, columnDefinition = "bytea")
    private byte[] answerEncrypted;

    private FamilyPassphrase(Long familyId, String question, byte[] answerEncrypted) {
        this.familyId = familyId;
        this.question = question;
        this.answerEncrypted = answerEncrypted;
    }

    /** 호출 전에 owner 권한과 공간의 기존 암호 유무를 확인하세요 */
    public static FamilyPassphrase create(Long familyId, String question, byte[] answerEncrypted) {
        return new FamilyPassphrase(familyId, question, answerEncrypted);
    }

    public void change(String question, byte[] answerEncrypted) {
        this.question = question;
        this.answerEncrypted = answerEncrypted;
    }
}