package com.stellarink.common.util;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;

/** API 分页参数的统一边界，避免异常页码和无上限查询拖垮数据库。 */
public final class Pagination {

    public static final int MAX_SIZE = 100;

    private Pagination() {
    }

    public static void requireValid(Integer page, Integer size) {
        if (page == null || page < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "页码必须从 1 开始。");
        }
        if (size == null || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "每页数量必须在 1 到 100 之间。");
        }
    }
}
