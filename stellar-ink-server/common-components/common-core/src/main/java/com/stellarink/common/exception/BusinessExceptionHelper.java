package com.stellarink.common.exception;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;

/**
 * 业务异常工厂：统一创建入口，便于全局检索
 */
public final class BusinessExceptionHelper {

    private BusinessExceptionHelper() {
    }

    public static BusinessException of(String message) {
        return new BusinessException(message);
    }

    public static BusinessException of(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }
}
