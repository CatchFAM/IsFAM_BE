package com.isfam.infra.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 딥보이스 분석 서버 전용 {@link RestClient}.
 *
 * <p><b>타임아웃을 명시하는 게 이 설정의 존재 이유입니다.</b> 기본값은 무한 대기라,
 * FastAPI 가 응답하지 않으면 톰캣 스레드가 그대로 잠깁니다. 통화가 몰리는 시간대에
 * 그런 요청이 쌓이면 분석과 무관한 API(로그인·가족 조회)까지 같이 멈춥니다.
 *
 * <p><b>{@code RestClient.Builder} 를 주입받지 않고 {@code RestClient.builder()} 를
 * 직접 부릅니다.</b> Spring Boot 4 는 자동 설정 모듈이 잘게 쪼개져 있어서,
 * {@code spring-boot-starter-webmvc} 만으로는 {@code RestClient.Builder} 빈이
 * 등록되지 않습니다(기동 실패: "required a bean of type RestClient$Builder").
 * 정적 팩토리는 스타터 구성과 무관하게 항상 동작합니다.
 *
 * <p>대신 Boot 가 설정한 {@code ObjectMapper} 도 따라오지 않습니다 —
 * {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 가 적용되지 않는다는 뜻입니다.
 * 그래서 {@link AntiSpoofingResponse} 는 모든 필드에 {@code @JsonProperty} 로
 * 이름을 명시했습니다. 이 두 결정은 한 쌍이라 한쪽만 되돌리면 조용히 깨집니다.
 */
@Configuration
@EnableConfigurationProperties(AntiSpoofingProperties.class)
public class AntiSpoofingConfig {

    @Bean
    public RestClient antiSpoofingRestClient(AntiSpoofingProperties properties) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}