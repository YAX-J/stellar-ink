package com.stellarink.sharedmodel.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举：定义系统中使用的标准错误码
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 成功
    SUCCESS(0, "成功"),

    // 通用错误
    NOT_FOUND(404, "资源不存在"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "拒绝访问"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // 业务错误
    PARAM_ERROR(1001, "参数错误"),
    PARAM_MISSING(1002, "缺少必填参数"),

    // 数据库错误
    DATABASE_ERROR(2000, "数据库操作错误");

    private final Integer code;
    private final String msg;
}
