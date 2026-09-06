package com.stellarink.sharedmodel.exception;

import com.stellarink.sharedmodel.enums.ErrorCode;
import lombok.Getter;

/**
 * 系统异常：非业务预期的底层错误
 */
@Getter
public class SystemException extends RuntimeException {

    private final Integer code;

    public SystemException(String message) {
        super(message);
        this.code = ErrorCode.SYSTEM_ERROR.getCode();
    }

    public SystemException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public SystemException(String message, Throwable cause) {
        super(message, cause);
        this.code = ErrorCode.SYSTEM_ERROR.getCode();
    }
}
