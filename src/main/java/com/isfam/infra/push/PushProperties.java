package com.isfam.infra.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code isfam.push.*}
 *
 * <p>{@code enabled} 기본값이 {@code false} 인 건 의도적입니다. 키가 없는 환경에서
 * 기본이 {@code true} 면 서버가 안 뜨고, 원인이 "Firebase 초기화 실패"로만 찍혀
 * 처음 보는 사람은 한참 헤맵니다.
 */
@ConfigurationProperties(prefix = "isfam.push")
public record PushProperties(

        @DefaultValue("false") boolean enabled,

        /**
         * 서비스 계정 키(JSON) 경로. 비워두면 {@code GOOGLE_APPLICATION_CREDENTIALS}
         * 환경변수 등 기본 자격증명 체인을 사용합니다.
         * <b>키 파일은 절대 git 에 넣지 마세요</b> — {@code .gitignore} 확인 필요.
         */
        @DefaultValue("") String credentialsPath
) {
}