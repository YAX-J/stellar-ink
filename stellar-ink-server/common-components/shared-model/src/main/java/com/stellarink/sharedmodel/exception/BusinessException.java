package com.stellarink.sharedmodel.exception;

import com.stellarink.sharedmodel.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：service 层校验失败时抛出，由全局异常处理器转为统一响应
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final Object data;

    public BusinessException(String message) {
        this(ErrorCode.PARAM_ERROR, message);
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BusinessException(Integer code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
