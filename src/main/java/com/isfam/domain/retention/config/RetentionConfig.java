package com.isfam.domain.retention.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 보관기간 설정 등록.
 *
 * <p>{@code AlertConfig}·{@code AntiSpoofingConfig} 와 같은 방식입니다 —
 * {@code @ConfigurationPropertiesScan} 을 쓰지 않는 프로젝트라 쓰는 쪽에서 명시적으로 등록합니다.
 */
@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfig {
}