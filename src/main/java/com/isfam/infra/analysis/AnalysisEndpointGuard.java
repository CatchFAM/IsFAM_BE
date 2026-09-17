package com.isfam.infra.analysis;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 운영 기동 시 분석 서버 URL 을 검사합니다. 조건에 맞지 않으면 기동을 거부합니다(P-25).
 *
 * <p>이 구간으로 통화 음성이 전송됩니다. 안전성 확보조치 기준 제7조가 생체인식정보의
 * 전송 구간 암호화를 요구하므로 https 를 기본으로 하되, VPC 밖으로 나가지 않는
 * 사설망 http 는 허용합니다. 공인 IP + http 면 인터넷 구간을 평문으로 지나갑니다.
 *
 * <p>로컬은 {@code http://localhost:8000} 이고 개발 중 주소를 바꿔 테스트할 일이
 * 있으므로 운영에서만 검사합니다.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class AnalysisEndpointGuard {

    private final AntiSpoofingProperties properties;

    @PostConstruct
    void verify() {
        String baseUrl = properties.baseUrl();

        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "isfam.fastapi.base-url 이 비어 있습니다. 운영에서는 필수입니다");
        }

        URI uri = parseOrFail(baseUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();

        if (host == null) {
            throw new IllegalStateException(
                    "isfam.fastapi.base-url 에서 호스트를 읽을 수 없습니다: " + baseUrl);
        }

        if ("https".equals(scheme)) {
            log.info("분석 서버 연결 — https ({})", host);
            return;
        }

        if (!"http".equals(scheme)) {
            throw new IllegalStateException(
                    "isfam.fastapi.base-url 의 프로토콜은 http 또는 https 여야 합니다: " + baseUrl);
        }

        if (!isPrivateAddress(host)) {
            throw new IllegalStateException("""
                    isfam.fastapi.base-url 이 평문 http 인데 사설망 주소가 아닙니다.
                      설정값: %s

                    이 구간으로 통화 음성이 전송됩니다.
                    https 로 바꾸거나, 분석 서버를 같은 VPC 안에 두세요."""
                    .formatted(baseUrl));
        }

        log.info("분석 서버 연결 — 사설망 http ({})", host);
    }

    private URI parseOrFail(String baseUrl) {
        try {
            return new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "isfam.fastapi.base-url 형식이 올바르지 않습니다: " + baseUrl);
        }
    }

    /**
     * 운영에서는 {@code analysis.internal} 같은 내부 DNS 이름을 쓰므로 문자열로
     * {@code 10.} 을 찾지 않고 실제로 해석합니다. 해석 실패는 거부입니다 —
     * 판단할 수 없는 주소를 통과시키면 검사를 넣은 의미가 없습니다.
     */
    private boolean isPrivateAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(
                    "isfam.fastapi.base-url 의 호스트를 해석할 수 없습니다: " + host);
        }
    }
}