package com.isfam.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * {@code family_passphrases.answer_encrypted} 저장 암호화 — 우리 집 암호의 정답.
 *
 * <p><b>해시가 아니라 암호화인 이유.</b> 가족이 정답을 화면에서 봐야 통화 중 상대방의 대답을
 * 맞춰볼 수 있습니다. 되돌릴 수 없는 해시로는 그게 안 됩니다.
 *
 * <p><b>평문으로 두지 않는 이유.</b> "아빠 고향", "첫 강아지 이름" 같은 답은 그 자체로
 * 가족의 개인정보이고, 흔히 다른 서비스의 계정 복구 질문 답과 겹칩니다. DB 덤프·백업이 새면
 * 그대로 노출되니 임베딩과 같은 방식(AES-256-GCM)으로 저장 시점에 암호화합니다.
 *
 * <p><b>키를 임베딩과 나눈 이유.</b> 임베딩 키를 회전하면 생체정보 재암호화 마이그레이션이
 * 필요한데, 같은 키를 쓰면 암호문까지 그 작업에 묶입니다. 반대로 이 키가 새도 생체정보는
 * 안전합니다. 키가 없으면 부팅을 막는 것도 {@link EmbeddingCipher} 와 같습니다.
 *
 * <p><b>AAD 에 family_id 를 겁니다.</b> 암호문을 다른 공간의 row 로 옮겨 붙이면 복호화가
 * 실패합니다. DB 를 만질 수 있는 사람이 A 공간의 정답을 B 공간 화면에 띄우는 걸 막습니다.
 *
 * <p>저장 형식은 {@code IV(12B) || ciphertext || tag(16B)} 한 덩어리입니다.
 */
@Component
public class PassphraseCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public PassphraseCipher(@Value("${isfam.crypto.passphrase-key:}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            throw new IllegalStateException(
                    "isfam.crypto.passphrase-key 가 없습니다. 우리 집 암호 암호화 키는 필수입니다");
        }

        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != KEY_LENGTH) {
            throw new IllegalStateException(
                    "isfam.crypto.passphrase-key 는 base64 로 인코딩된 32바이트여야 합니다 (현재 %d바이트)"
                            .formatted(raw.length));
        }

        this.key = new SecretKeySpec(raw, ALGORITHM);
        Arrays.fill(raw, (byte) 0);
    }

    public byte[] encrypt(String plain, long familyId) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BIT_LENGTH, iv));
            cipher.updateAAD(aad(familyId));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("우리 집 암호 암호화 실패", e);
        }
    }

    /**
     * 실패하면 {@link IllegalStateException} 입니다. 키가 바뀌었거나, 다른 공간의 암호문이
     * 옮겨 붙었거나, 바이트가 변조된 경우입니다. 빈 문자열로 넘기지 않습니다 —
     * 가족 화면에 "정답 없음"이 뜨는 것보다 오류가 드러나는 편이 낫습니다.
     */
    public String decrypt(byte[] stored, long familyId) {
        if (stored == null || stored.length <= IV_LENGTH) {
            throw new IllegalStateException("암호화된 우리 집 암호 형식이 올바르지 않습니다");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BIT_LENGTH, stored, 0, IV_LENGTH));
            cipher.updateAAD(aad(familyId));
            byte[] plain = cipher.doFinal(stored, IV_LENGTH, stored.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "우리 집 암호 복호화 실패 — 키가 바뀌었거나 다른 공간의 값일 수 있습니다", e);
        }
    }

    private static byte[] aad(long familyId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(familyId).array();
    }
}