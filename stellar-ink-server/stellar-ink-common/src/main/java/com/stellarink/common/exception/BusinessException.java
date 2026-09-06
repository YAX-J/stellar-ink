package com.stellarink.common.exception;

import com.stellarink.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：service 层校验失败时抛出，由全局异常处理器转为统一响应
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(ResultCode.BAD_REQUEST, message);
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
