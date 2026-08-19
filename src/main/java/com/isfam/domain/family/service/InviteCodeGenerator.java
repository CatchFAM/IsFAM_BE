package com.isfam.domain.family.service;

import com.isfam.common.error.BusinessException;
import com.isfam.common.error.ErrorCode;
import com.isfam.domain.family.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 초대 코드 문자열 생성.
 *
 * <p>코드는 카톡으로 링크를 보내는 것 말고도 <b>사람이 눈으로 읽고 손으로 입력</b>합니다
 * (앱의 "초대 코드 입력" 화면). 그래서 아래 두 가지를 지킵니다.
 *
 * <ul>
 *   <li><b>혼동 문자 제외</b> — 0/O, 1/I/L 을 뺐습니다. 이걸 안 빼면 "코드가 틀렸다"는
 *       문의가 들어오는데 서버 로그만 봐서는 원인이 안 보입니다</li>
 *   <li><b>대문자 고정 + 조회 시 정규화</b> — 딥링크가 소문자로 넘어오거나 사용자가 소문자로
 *       입력해도 같은 코드로 취급합니다</li>
 * </ul>
 *
 * <p>{@link SecureRandom} 을 쓰는 이유는 코드가 <b>인증 수단</b>이기 때문입니다.
 * 이 문자열만 알면 남의 가족 공간 이름과 인원수를 볼 수 있고, 로그인만 하면 들어갈 수도 있습니다.
 * 31^6 ≈ 8.9억이라 무차별 대입이 실용적이진 않지만, 예측 가능한 난수를 쓰면 그 계산이 무의미해집니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteCodeGenerator {

    /** 0·O·1·I·L 제외. 31자 */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    /** 명세 예시가 "AB12CD" 6자리입니다 */
    private static final int LENGTH = 6;

    /** 충돌은 사실상 안 나지만, 무한 재시도로 요청을 붙잡아두지는 않습니다 */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FamilyRepository familyRepository;

    /**
     * 아직 아무도 안 쓰는 코드를 만듭니다.
     *
     * <p>사전 조회는 유니크 제약 위반으로 500 이 나가는 걸 줄이려는 것이고, 최종 방어선은
     * {@code families.invite_code} 의 unique 제약입니다.
     */
    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!familyRepository.existsByInviteCode(code)) {
                return code;
            }
            log.warn("초대 코드 충돌 — 재시도 {}회차", attempt + 1);
        }
        // 여기까지 오면 난수원이 고장났거나 코드 공간이 포화된 것입니다. 둘 다 서버 문제입니다.
        throw new BusinessException(ErrorCode.COMMON_008);
    }

    /**
     * 딥링크·수기 입력으로 들어온 코드를 조회 가능한 형태로 맞춥니다.
     *
     * <p>URL 에서 넘어올 때 공백이나 대소문자가 섞이는 건 흔합니다. 정규화를 조회 직전에
     * 한 번만 하면 되도록 여기 모아뒀습니다.
     */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}