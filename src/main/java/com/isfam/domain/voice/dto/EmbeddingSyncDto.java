package com.isfam.domain.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /family/embeddings} 의 응답.
 *
 * <p><b>이 응답은 가족 구성원의 민감정보(생체인식 특징정보)를 담습니다.</b>
 * 담기는 조건은 세 가지 모두입니다 — ① 같은 공간의 {@code active} 멤버,
 * ② {@code voice_sharing_consented_at} 이 있는 사람(제3자 제공 동의),
 * ③ 음성을 등록한 사람. 하나라도 빠지면 {@code removed_user_ids} 로 갑니다.
 *
 * <p>본인은 항상 제외됩니다. 내 임베딩은 내 폰이 이미 갖고 있고, 굳이 한 번 더
 * 내려보내면 그만큼 노출 면적만 넓어집니다.
 */
public final class EmbeddingSyncDto {

    private EmbeddingSyncDto() {
    }

    /**
     * {@code @JsonInclude(ALWAYS)} — 전역 {@code non_null} 설정 때문에
     * 빈 {@code removed_user_ids} 나 null 이 사라지면 앱이 키 존재로 분기할 때 깨집니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EmbeddingsResponse(
            String embeddingModelVersion,

            /*
             * 다음 호출의 since 로 그대로 되돌려받는 값입니다.
             * 서버 시각이며, 폰이 자기 시계로 만든 값을 보내면 임베딩이 조용히 누락됩니다.
             */
            Instant syncedAt,

            List<MemberEmbedding> members,

            /* since 이후 탈퇴·강퇴됐거나, 동의를 안 했거나, 음성이 삭제된 멤버 */
            List<Long> removedUserIds
    ) {
    }

    /**
     * {@code embedding} 은 base64 로 감싼 float32[192] 평문입니다.
     *
     * <p>DB 에는 암호화해 저장하지만({@code EmbeddingCipher}) 응답에서는 복호화해 보냅니다.
     * 앱이 코사인 유사도를 계산하려면 실수 벡터 자체가 필요하고, 서버 키로 암호화한 값은
     * 앱이 열 수 없기 때문입니다. 구간 보호는 <b>TLS</b> 가, 단말 보호는
     * <b>Android Keystore 기반 로컬 암호화 저장소</b>가 담당합니다 —
     * 명세의 "encrypted vector" 는 이 두 겹을 가리킵니다(legal-compliance.md 4장 ①).
     *
     * <p>{@code updated_at} 은 {@code GREATEST(voiceprints.updated_at, users.updated_at,
     * family_memberships.updated_at)} 입니다. 닉네임만 바뀌어도 값이 올라가야
     * 다음 동기화에서 갱신된 이름이 내려갑니다.
     */
    public record MemberEmbedding(
            Long userId,
            String displayName,
            String embedding,
            Instant updatedAt
    ) {
    }
}