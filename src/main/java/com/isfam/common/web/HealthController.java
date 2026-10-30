package com.isfam.common.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /health — 스플래시(비인증)에서 부를 수 있는 두 API 중 하나 */
@Tag(name = "health", description = "헬스 체크")
@RestController
public class HealthController {

    @Operation(summary = "헬스 체크")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}