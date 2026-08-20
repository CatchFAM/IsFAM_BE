package com.isfam.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * {@code voiceprints.speaker_embedding} · {@code voiceprint_samples.embedding} 저장 암호화.
 *
 * <p><b>왜 저장 시점에 암호화하나</b> — 화자 임베딩은 생체인식 특징정보로 민감정보에
 * 해당합니다(legal-compliance.md 2-3장). TLS 는 전송 구간만 가리므로, DB 덤프·백업 파일·
 * 스냅샷이 새면 그대로 노출됩니다. 법 제23조 대상 데이터라 안전조치를 전송에만 걸어두면
 * 의무를 반만 이행한 상태입니다.
 *
 * <p><b>AES-256-GCM</b> 을 쓰는 이유는 인증 태그가 붙어 변조를 감지할 수 있어서입니다.
 * CBC 였다면 누군가 바이트를 뒤집어도 조용히 복호화되어 엉뚱한 임베딩이 가족 폰으로 배포됩니다.
 *
 * <p>저장 형식은 <b>{@code IV(12B) || ciphertext || tag(16B)}</b> 한 덩어리입니다.
 * IV 컬럼을 따로 두지 않은 건 ERD 에 그 컬럼이 없기 때문이고, 어차피 IV 는 비밀이 아닙니다.
 *
 * <p><b>키를 바꾸면 기존 임베딩은 전부 복호화 불가입니다.</b> 롤백할 방법이 없으니
 * 회전이 필요하면 재암호화 마이그레이션을 먼저 돌리거나, 최후엔 전원 음성 재등록입니다.
 * 운영 키는 반드시 환경변수·시크릿 매니저로 주입하세요 — properties 파일의 값은 로컬 전용입니다.
 */
@Slf4j
@Component
public class EmbeddingCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EmbeddingCipher(@Value("${isfam.crypto.embedding-key:}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            // 부팅을 막습니다. 키 없이 뜨면 평문으로 저장되는 게 아니라 첫 등록에서 터지는데,
            // 그게 배포 후 첫 사용자에게서 발견되면 늦습니다.
            throw new IllegalStateException(
                    "isfam.crypto.embedding-key 가 없습니다. 생체정보 암호화 키는 필수입니다");
        }

        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != KEY_LENGTH) {
            throw new IllegalStateException(
                    "isfam.crypto.embedding-key 는 base64 로 인코딩된 32바이트여야 합니다 (현재 %d바이트)"
                            .formatted(raw.length));
        }

        this.key = new SecretKeySpec(raw, ALGORITHM);
        Arrays.fill(raw, (byte) 0);
    }

    /** 평문 임베딩 바이트(768B) → 저장용 바이트(12 + 768 + 16 = 796B) */
    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BIT_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plain);

            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("임베딩 암호화 실패", e);
        }
    }

    /**
     * 저장용 바이트 → 평문 임베딩 바이트.
     *
     * <p>실패하면 {@link IllegalStateException} 입니다. 여기서 조용히 null 을 돌려주면
     * 깨진 임베딩이 그대로 가족 폰에 배포됩니다 — 터지는 편이 낫습니다.
     */
    public byte[] decrypt(byte[] stored) {
        if (stored == null || stored.length <= IV_LENGTH) {
            throw new IllegalStateException("암호화된 임베딩 형식이 올바르지 않습니다");
        }
        try {
            GCMParameterSpec spec =
                    new GCMParameterSpec(TAG_BIT_LENGTH, stored, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return cipher.doFinal(stored, IV_LENGTH, stored.length - IV_LENGTH);

        } catch (GeneralSecurityException e) {
            // 키 회전 후 예전 데이터를 읽으려는 경우가 대부분입니다
            throw new IllegalStateException("임베딩 복호화 실패 — 암호화 키가 바뀌었을 수 있습니다", e);
        }
    }

    /** 편의 — 저장 바이트를 바로 float32[192] 로 */
    public float[] decryptToFloats(byte[] stored) {
        return EmbeddingCodec.toFloats(decrypt(stored));
    }

    /** 편의 — float32[192] 를 바로 저장 바이트로 */
    public byte[] encryptFloats(float[] values) {
        return encrypt(EmbeddingCodec.toBytes(values));
    }
}