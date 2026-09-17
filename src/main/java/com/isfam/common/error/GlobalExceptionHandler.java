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

/**
 * 컨트롤러 계층에서 빠져나온 예외를 ErrorResponse 로 변환.
 *
 * <p><b>예외 메시지를 로그에 찍지 마세요.</b> 여기로 올라오는 예외 중 상당수가
 * 요청 본문 조각을 메시지에 담고 있습니다. 특히 Jackson 의
 * {@code HttpMessageNotReadableException} 은 파싱이 실패한 지점의 원문을 그대로 붙여 오는데,
 * {@code POST /auth/signup} 에서 JSON 이 깨지면 {@code phone_number}·{@code password} 가
 * 평문으로 로그 파일에 남습니다. 고시 제29조가 요구하는 안전조치 위반이고,
 * 로그는 접근 통제도 파기 주기도 DB 보다 느슨해서 실제 노출 위험이 더 큽니다.
 * (legal-compliance.md L9-f · P-24)
 *
 * <p>대신 남기는 것은 <b>예외 클래스명 + 메서드 + 경로</b> 셋입니다. 어떤 요청이
 * 어디서 어떤 종류로 실패했는지는 이것만으로 충분히 좁혀지고, 값이 필요하면
 * 재현으로 확인합니다.
 *
 * <p>유일한 예외가 {@link #handleUnexpected} 입니다 — 거기는 전부 버그라
 * 스택트레이스가 있어야 고칠 수 있습니다. 대신 그 응답에는 스택트레이스가 실리지 않도록
 * {@code server.error.include-stacktrace=never} 를 운영 프로파일에 고정해 두었습니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.debug("[{}] {} {}", e.getErrorCode(), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getStatus(), e.getMessage(), request, null));
    }

    /**
     * 필드명과 검증 메시지는 우리가 {@code @NotBlank(message=...)} 등으로 직접 쓴 문자열이라
     * 사용자 입력이 섞이지 않습니다. 그대로 내려보내도 안전합니다.
     */
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

    /**
     * <b>{@code e.getMessage()} 를 되살리지 마세요.</b> 클래스 javadoc 의 이유가 정확히
     * 이 핸들러에 대한 것입니다. 디버깅에 필요해 보이지만, 그 한 줄이 회원가입 요청의
     * 평문 비밀번호를 로그로 보냅니다.
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e, HttpServletRequest request) {
        log.debug("잘못된 요청 — {} {} {}",
                e.getClass().getSimpleName(), request.getMethod(), request.getRequestURI());
        return build(ErrorCode.COMMON_001, request, null);
    }

    /** spoof-check 업로드가 상한 초과 — 앱은 구간을 줄여 재시도 */
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

    /**
     * 여기 걸리면 전부 버그입니다.
     *
     * <p>스택트레이스를 남기는 유일한 자리입니다. 고칠 방법이 이것밖에 없어서인데,
     * 대신 두 가지를 전제로 합니다 — ① 응답에는 실리지 않습니다
     * ({@code server.error.include-stacktrace=never}) ② 로그 파일 접근이 통제됩니다.
     * 여기 찍힌 트레이스에 요청 값이 섞여 들어올 가능성은 남아 있으므로,
     * 운영 로그를 외부 SaaS 로 내보내면 그 순간 수탁·국외이전 검토 대상이 됩니다
     * (legal-compliance.md L8).
     */
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