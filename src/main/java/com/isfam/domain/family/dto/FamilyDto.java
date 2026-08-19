package com.isfam.domain.family.dto;

import com.isfam.domain.family.entity.FamilyMembership;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** /family · /family/members/** 의 요청·응답 */
public final class FamilyDto {

    private FamilyDto() {
    }

    // ── 요청 ────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank(message = "가족 공간 이름을 입력해 주세요")
            @Size(max = 50, message = "이름은 50자를 넘을 수 없어요")
            String name
    ) {
    }

    public record UpdateNameRequest(
            @NotBlank(message = "가족 공간 이름을 입력해 주세요")
            @Size(max = 50, message = "이름은 50자를 넘을 수 없어요")
            String name
    ) {
    }

    // ── 응답 ────────────────────────────────────────────────

    /**
     * POST /family · GET /family 공용 응답.
     *
     * <p>명세상 GET 출력에는 created_at 이 없지만 같은 화면을 그리는 응답이라 형태를 맞췄습니다.
     * 필드가 하나 더 오는 건 앱이 무시하면 그만이고, 두 응답이 갈리면 파싱을 두 벌 짜야 합니다.
     *
     * <p>members[] 에는 <b>본인도 포함</b>됩니다. active 멤버만 나갑니다 —
     * 나갔거나 강퇴된 사람의 row 는 남아 있지만 목록에는 보이지 않습니다.
     */
    public record FamilyResponse(
            Long familyId,
            String name,
            FamilyMembership.Role myMemberRole,
            List<Member> members,
            Instant createdAt
    ) {
    }

    /**
     * display_name 은 전역 닉네임입니다(실명 아님).
     *
     * <p>voiceprintRegistered 는 Phase 6 전까지 전원 false 입니다 —
     * voiceprints 에 INSERT 하는 경로가 아직 없기 때문입니다. 조회는 이미 붙어 있어서
     * POST /me/voiceprint 가 생기는 순간 별도 수정 없이 true 로 바뀝니다.
     */
    public record Member(
            Long userId,
            String displayName,
            FamilyMembership.Role memberRole,
            boolean voiceprintRegistered,
            Instant joinedAt
    ) {
    }

    /** PUT /family */
    public record UpdateNameResponse(
            Long familyId,
            String name,
            Instant updatedAt
    ) {
    }
}