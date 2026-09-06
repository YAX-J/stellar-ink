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
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // 业务错误
    PARAM_ERROR(1001, "参数错误"),
    PARAM_MISSING(1002, "缺少必填参数"),
    PARAM_TYPE_ERROR(1003, "参数类型错误"),
    JSON_PARSE_ERROR(1004, "JSON解析失败"),
    UN_PERMISSION(1005, "无权限"),

    // 数据库错误
    DATABASE_ERROR(2000, "数据库操作错误"),
    DATABASE_CONNECTION_ERROR(2001, "数据库连接错误"),
    DATABASE_TIMEOUT(2002, "数据库操作超时"),

    // 网络错误
    NETWORK_ERROR(3000, "网络错误"),
    TIMEOUT_ERROR(3001, "请求超时"),
    GATEWAY_ERROR(3002, "网关错误");

    private final Integer code;
    private final String msg;
}
