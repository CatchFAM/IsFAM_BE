package com.isfam.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 서비스 계층에서 던지는 모든 예외의 기본형 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getStatus(), errorCode.getMessage());
    }

    /** 같은 코드인데 안내 문구만 다른 경우 */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, errorCode.getStatus(), message);
    }

    /** AUTH_006(401/410), INVITE_001(404/410) 처럼 상태가 갈리는 경우 */
    public BusinessException(ErrorCode errorCode, HttpStatus status) {
        this(errorCode, status, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    /** 흐름 제어용이라 스택트레이스를 만들지 않습니다 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}