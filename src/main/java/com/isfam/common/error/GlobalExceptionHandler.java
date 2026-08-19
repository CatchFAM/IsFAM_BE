package com.isfam.common.error;

import com.isfam.common.security.DeviceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/** 컨트롤러 계층에서 빠져나온 예외를 ErrorResponse 로 변환 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.debug("[{}] {} {}", e.getErrorCode(), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getStatus(), e.getMessage(), request, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<ErrorResponse.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.COMMON_002, request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return build(ErrorCode.COMMON_002, request, fields);
    }

    /** X-Device-Id 는 전용 코드로 내려야 앱이 "단말 재등록" 분기를 탈 수 있습니다 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e,
                                                             HttpServletRequest request) {
        return build(DeviceId.HEADER.equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.DEVICE_002 : ErrorCode.COMMON_001, request, null);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e, HttpServletRequest request) {
        log.debug("잘못된 요청: {}", e.getMessage());
        return build(ErrorCode.COMMON_001, request, null);
    }

    /** spoof-check 업로드가 5MB 초과 — 앱은 구간을 줄여 재시도 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e,
                                                        HttpServletRequest request) {
        return build(ErrorCode.COMMON_006, request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e,
                                                         HttpServletRequest request) {
        return build(ErrorCode.COMMON_007, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        return build(ErrorCode.COMMON_005, request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e,
                                                          HttpServletRequest request) {
        return build(ErrorCode.COMMON_004, request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e,
                                                            HttpServletRequest request) {
        return build(ErrorCode.COMMON_003, request, null);
    }

    /** 여기 걸리면 전부 버그입니다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("처리되지 않은 예외: {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(ErrorCode.COMMON_008, request, null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, HttpServletRequest request,
                                                List<ErrorResponse.FieldError> fields) {
        HttpStatus status = code.getStatus();
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, status, code.getMessage(), request, fields));
    }
}