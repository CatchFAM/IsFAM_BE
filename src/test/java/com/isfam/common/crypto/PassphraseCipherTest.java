package com.isfam.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassphraseCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final PassphraseCipher cipher = new PassphraseCipher(KEY);

    @Test
    void roundTrip() {
        byte[] stored = cipher.encrypt("보리", 1L);

        assertThat(cipher.decrypt(stored, 1L)).isEqualTo("보리");
    }

    /** 같은 정답이라도 IV 가 매번 달라 암호문이 겹치지 않습니다 — 공간끼리 정답이 같은지 DB 에서 비교할 수 없게 */
    @Test
    void sameAnswerDifferentCiphertext() {
        assertThat(cipher.encrypt("보리", 1L)).isNotEqualTo(cipher.encrypt("보리", 1L));
    }

    /** AAD = family_id. 다른 공간 row 로 옮겨 붙인 암호문은 풀리지 않습니다 */
    @Test
    void otherFamilyCannotDecrypt() {
        byte[] stored = cipher.encrypt("보리", 1L);

        assertThatThrownBy(() -> cipher.decrypt(stored, 2L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bootFailsWithoutKey() {
        assertThatThrownBy(() -> new PassphraseCipher(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PassphraseCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}