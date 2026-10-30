package com.isfam.domain.family.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.family.entity.FamilyMembership;

import java.time.Instant;

/**
 * /family/invite-code · /invitations/** 의 응답.
 *
 * <p>요청 본문은 없습니다. 음성 공유 동의를 가입 때 받게 되면서
 * {@code voice_sharing_consent} 를 받던 IssueRequest · AcceptRequest 를 없앴습니다.
 * 구버전 앱이 본문을 보내도 컨트롤러가 읽지 않아 무시됩니다.
 */
public final class InvitationDto {

    private InvitationDto() {
    }

    /**
     * GET · POST /family/invite-code 공용 응답.
     *
     * <p>{@code @JsonInclude(ALWAYS)} 가 붙은 이유는 전역 설정이
     * {@code default-property-inclusion=non_null} 이기 때문입니다. 그대로 두면 코드가 없을 때
     * 응답이 {@code {}} 로 나가는데, 명세는 "200 + 전 필드 null" 을 요구합니다.
     * 앱이 키 존재로 분기하고 있으면 조용히 깨집니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InviteCodeResponse(
            String inviteCode,
            String inviteLink,
            String qrCodeUrl,
            Instant expiresAt,
            Instant rotatedAt
    ) {
        /** 코드가 없거나 만료·비활성 → 앱은 "초대 링크 만들기" 버튼을 띄웁니다 */
        public static InviteCodeResponse inactive() {
            return new InviteCodeResponse(null, null, null, null, null);
        }
    }

    /**
     * GET /invitations/{code} — 비인증 상태의 딥링크 진입 화면용.
     *
     * <p>여기서 내려가는 정보는 <b>가족 이름과 인원수뿐</b>입니다. 로그인 전 아무나 코드만
     * 알면 부를 수 있는 API 라 멤버 목록이나 닉네임을 넣으면 코드 무차별 대입으로
     * 남의 가족 구성이 새어 나갑니다.
     */
    public record PreviewResponse(
            String inviteCode,
            String familyName,
            int memberCount,
            Instant expiresAt
    ) {
    }

    /** POST /invitations/{code}/accept */
    public record AcceptResponse(
            Long familyId,
            String familyName,
            Long userId,
            String displayName,
            FamilyMembership.Role memberRole,
            FamilyMembership.Status status,
            Instant joinedAt
    ) {
    }
}