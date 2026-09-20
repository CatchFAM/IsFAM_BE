package com.isfam.common.alert;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 알람 설정 등록.
 *
 * <p>{@code AntiSpoofingConfig} 와 같은 방식입니다 — {@code @ConfigurationPropertiesScan}
 * 을 쓰지 않는 프로젝트라 쓰는 쪽에서 명시적으로 등록합니다. 어떤 설정이 살아 있는지
 * 한 군데에서 보이는 편이 낫습니다.
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {
}