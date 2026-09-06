package com.stellarink.common.constant;

/**
 * 通用常量
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /** 请求头：Authorization: Bearer &lt;token&gt; */
    public static final String AUTH_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 网关校验 JWT 后向下游服务传递的当前用户 id 请求头 */
    public static final String GATEWAY_USER_HEADER = "X-User-Id";

    /** 拦截器解析出的当前用户 id，存放在 request attribute 中 */
    public static final String CURRENT_USER_ID = "currentUserId";
}
