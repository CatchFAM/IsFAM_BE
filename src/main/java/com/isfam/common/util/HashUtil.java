package com.isfam.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * "원문을 저장하면 안 되지만 대조는 해야 하는" 값의 단방향 해시.
 *
 * <p>대상: refresh_tokens.token_hash, phone_verifications.code_hash / token_hash,
 * 탈퇴 시 users.phone_number 치환.
 *
 * <p><b>비밀번호에는 쓰지 마세요.</b> 비밀번호는 PasswordEncoder(BCrypt) 담당입니다.
 * 여기 값들은 UUID·JWT처럼 엔트로피가 충분한 문자열이라 SHA-256 으로 충분합니다.
 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String sha256(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("해시 대상이 null 입니다");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }

    /** 타이밍 공격을 피하는 비교 */
    public static boolean matches(String raw, String hashed) {
        if (raw == null || hashed == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256(raw).getBytes(StandardCharsets.UTF_8),
                hashed.getBytes(StandardCharsets.UTF_8));
    }
}