// src/main/java/com/isfam/common/error/ErrorResponse.java
package com.isfam.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * 모든 실패 응답의 공통 형태.
 *
 * <pre>
 * { "error_code": "AUTH_008", "message": "비밀번호가 맞지 않아요",
 *   "status": 401, "path": "/auth/login", "timestamp": "2026-08-17T09:00:00Z" }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @JsonProperty("error_code") String errorCode,
        String message,
        int status,
        String path,
        Instant timestamp,
        @JsonProperty("field_errors") List<FieldError> fieldErrors
) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode, HttpStatus status, String message,
                                   HttpServletRequest request, List<FieldError> fieldErrors) {
        return new ErrorResponse(errorCode.name(), message, status.value(),
                request == null ? null : request.getRequestURI(), Instant.now(),
                (fieldErrors == null || fieldErrors.isEmpty()) ? null : fieldErrors);
    }

    /**
     * 시큐리티 필터 체인에서 직접 응답 바디를 쓸 때 사용합니다.
     * 필터 단계 예외는 {@code @RestControllerAdvice} 까지 도달하지 않아 별도 경로가 필요합니다.
     * 필드가 5개뿐이라 ObjectMapper 없이 조립합니다.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response,
                             ErrorCode errorCode, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"error_code":"%s","message":"%s","status":%d,"path":"%s","timestamp":"%s"}"""
                .formatted(errorCode.name(), escape(message), status.value(),
                        escape(request.getRequestURI()), Instant.now()));
    }

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             ErrorCode errorCode) throws IOException {
        write(request, response, errorCode, errorCode.getStatus(), errorCode.getMessage());
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}