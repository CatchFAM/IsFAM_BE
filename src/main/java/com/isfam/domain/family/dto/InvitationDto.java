package com.isfam.domain.family.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isfam.domain.family.entity.FamilyMembership;

import java.time.Instant;

/** /family/invite-code · /invitations/** 의 요청·응답 */
public final class InvitationDto {

    private InvitationDto() {
    }

    // ── 요청 ────────────────────────────────────────────────

    /**
     * POST /family/invite-code 요청 본문.
     *
     * <p><b>[명세 확장]</b> 명세의 입력은 "-" 이고 본문 없이도 동작합니다.
     * {@code voice_sharing_consent} 는 링크를 만드는 사람(주로 owner)의 제3자 제공 동의를
     * 받기 위한 선택 필드입니다.
     *
     * <p>필요한 이유: legal-compliance.md 8장은 민감정보 처리 동의와 제3자 제공 동의를
     * 별도 항목으로 요구하는데, 후자를 받는 지점이 명세상 초대 수락뿐입니다. 공간을 만든
     * owner 는 그 경로를 지나지 않으므로 동의 없이 임베딩이 남의 기기로 복제됩니다.
     * 링크 생성은 "이제부터 내 임베딩이 밖으로 나간다"는 첫 시점이라 동의 획득 지점으로 맞습니다.
     *
     * <p>보내지 않으면 아무것도 기록되지 않습니다 — 받은 적 없는 동의를 시각만 찍어 남기면
     * 분쟁에서 그게 증빙으로 오독되기 때문에, 기본값 true 같은 처리는 하지 않습니다.
     */
    public record IssueRequest(Boolean voiceSharingConsent) {
    }

    /**
     * POST /invitations/{code}/accept 요청 본문.
     *
     * <p><b>{@code @NotNull} 을 쓰지 않습니다.</b> 붙이면 누락 시 COMMON_002 가 나가는데,
     * 명세는 이 상황을 422 INVITE_003 으로 규정합니다. 앱이 "음성 공유 동의가 필요해요"
     * 분기를 타야 하므로 검증을 서비스로 내렸습니다. false 와 누락을 같은 코드로 다룹니다.
     */
    public record AcceptRequest(Boolean voiceSharingConsent) {
    }

    // ── 응답 ────────────────────────────────────────────────

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