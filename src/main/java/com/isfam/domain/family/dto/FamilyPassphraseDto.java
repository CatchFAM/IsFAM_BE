package com.isfam.domain.family.dto;

import com.isfam.domain.family.entity.FamilyPassphrase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** /family/passphrase 의 요청·응답 */
public final class FamilyPassphraseDto {

    private FamilyPassphraseDto() {
    }

    /**
     * PUT /family/passphrase — 등록과 수정을 겸합니다.
     *
     * <p>질문은 앱 프리셋이든 직접 입력이든 문자열로만 받습니다. 서버는 프리셋 목록을 모릅니다.
     * 앞뒤 공백은 서버가 털고, 공백뿐이면 {@code @NotBlank} 에서 422 입니다.
     */
    public record SaveRequest(
            @NotBlank(message = "확인 질문을 입력해 주세요")
            @Size(max = FamilyPassphrase.QUESTION_MAX_LENGTH,
                    message = "질문은 100자를 넘을 수 없어요")
            String question,

            @NotBlank(message = "정답을 입력해 주세요")
            @Size(max = FamilyPassphrase.ANSWER_MAX_LENGTH,
                    message = "정답은 50자를 넘을 수 없어요")
            String answer
    ) {
        @Override
        public String toString() {
            return "SaveRequest[question=" + question + ", answer=****]";
        }
    }

    /**
     * GET · PUT 공용 응답. answer 는 <b>평문</b>입니다 — 가족이 화면에서 봐야 하는 값입니다.
     * 응답은 TLS 로만 보호되므로 로그에 이 DTO 를 통째로 찍지 마세요.
     */
    public record PassphraseResponse(
            String question,
            String answer,
            Instant updatedAt
    ) {
        @Override
        public String toString() {
            // 실수로 log.debug("{}", response) 해도 정답이 남지 않게
            return "PassphraseResponse[question=" + question + ", answer=****, updatedAt=" + updatedAt + "]";
        }
    }
}