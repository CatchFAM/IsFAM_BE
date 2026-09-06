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

    /**
     * PUT /family/members/{userId}/alias
     *
     * <p>{@code @NotBlank} 가 없는 건 의도입니다 — null 이나 공백은 <b>해제</b>입니다.
     * 앱에서 입력란을 비우고 저장하는 흐름이 그대로 해제가 됩니다.
     */
    public record UpdateAliasRequest(
            @Size(max = 50, message = "별명은 50자를 넘을 수 없어요")
            String alias
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
     *
     * <p>alias 는 <b>이 응답을 받는 사람이</b> 붙인 별명입니다. 지정 전이면 null 이고,
     * 같은 공간의 다른 사람이 부르는 이름은 여기 들어오지 않습니다.
     * 앱은 alias 와 displayName 을 함께 표시합니다 — 입력란에 "아빠", 우측에 "김상호".
     */
    public record Member(
            Long userId,
            String displayName,
            String alias,
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

    /**
     * PUT /family/members/{userId}/alias
     *
     * <p>userId 는 <b>별명이 붙은 대상</b>입니다(요청자가 아닙니다).
     * 해제한 경우 alias 는 null 이고 updatedAt 은 해제 시각입니다.
     */
    public record UpdateAliasResponse(
            Long userId,
            String alias,
            Instant updatedAt
    ) {
    }
}