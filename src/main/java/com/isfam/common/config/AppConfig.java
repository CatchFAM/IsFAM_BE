package com.isfam.common.config;

import com.isfam.common.security.CurrentUserArgumentResolver;
import com.isfam.common.security.DeviceId;
import com.isfam.common.security.DeviceIdArgumentResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

/**
 * 애플리케이션 공통 설정 — 리졸버 등록 · JPA Auditing · Clock · 비동기 · OpenAPI.
 * 각각 빈 하나짜리라 파일을 나누지 않았습니다.
 *
 * <p>{@code @EnableAsync} 는 Phase 5 의 {@code PushDispatcher} 때문에 필요합니다.
 * 이게 없으면 {@code @Async} 가 조용히 무시되고 FCM 왕복이 HTTP 응답을 붙잡습니다 —
 * 에러가 나지 않아서 알아채기 어렵습니다.
 */
@Configuration
@EnableAsync
@EnableJpaAuditing
@RequiredArgsConstructor
public class AppConfig implements WebMvcConfigurer {

    private static final String BEARER = "bearerAuth";

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final DeviceIdArgumentResolver deviceIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
        resolvers.add(deviceIdArgumentResolver);
    }

    /**
     * 시각을 주입받게 해서 테스트에서 고정할 수 있게 합니다.
     * 인증번호 3분, 토큰 만료, 초대 코드 72시간처럼 시간이 판정 기준인 로직이 많습니다.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("IsFAM Server API").version("v0.0.1")
                        .description("가족 목소리 기반 보이스피싱 탐지 서비스"))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addParameters(DeviceId.HEADER, new Parameter()
                                .in("header").name(DeviceId.HEADER).required(true)
                                .description("POST /devices 응답의 device_id")
                                .schema(new StringSchema())))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}