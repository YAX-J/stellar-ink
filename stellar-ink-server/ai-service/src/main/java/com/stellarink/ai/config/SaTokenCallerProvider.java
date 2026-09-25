package com.stellarink.ai.config;

import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 默认的调用者来源：登录身份来自 Sa-Token，traceId 来自 MDC（由 common-core 的过滤器写入）。
 *
 * <p>拿不到登录身份时直接抛异常：这些内部调用（评测、索引重建）都是 ADMIN 动作，
 * 网关与服务内都要求登录，走到这里还没有身份说明链路出了问题，不该继续发请求。
 */
@Component
public class SaTokenCallerProvider implements InternalCallerProvider {

    /** 与 common-core {@code TraceIdFilter} 写入 MDC 的键一致 */
    static final String MDC_TRACE_ID = "traceId";

    @Override
    public Caller current() {
        Long userId = AuthHelper.loginId();
        if (userId == null) {
            // AuthHelper 正常路径下不会返回 null；真出现说明链路变了，宁可失败也不要发一个空身份
            throw new IllegalStateException("内部调用缺少登录身份：请先登录再执行该操作");
        }
        Role role = AuthHelper.currentRole();
        return new Caller(userId, role, MDC.get(MDC_TRACE_ID));
    }
}
