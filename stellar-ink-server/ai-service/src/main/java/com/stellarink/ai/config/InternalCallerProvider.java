package com.stellarink.ai.config;

import com.stellarink.sharedmodel.enums.Role;

/**
 * 内部调用的「调用者身份」来源：由 ai-service 从 Sa-Token 与 MDC 取出，签名后再发给 Python。
 *
 * <p>为什么单独抽一层：Python 不解析 Sa-Token，身份只能由 Java 传。而身份从哪里来
 * （当前登录用户、将来的系统身份）是**策略**，不该写死在签名拦截器里 ——
 * 抽出来以后单测可以注入固定身份，不必去拼 Sa-Token 上下文。
 */
public interface InternalCallerProvider {

    /**
     * 当前调用者。
     *
     * @param userId  业务用户 id（**不允许为空**：空 id 会让下游误判身份）
     * @param role    用户角色
     * @param traceId 链路追踪 id，便于两侧日志对读
     */
    record Caller(long userId, Role role, String traceId) {
    }

    /**
     * 取当前调用者。
     *
     * <p>没有登录身份时**必须抛异常**（而不是造一个默认身份）：
     * 默认成 {@code ADMIN} 就是一个提权漏洞，默认成 {@code READER} 则会让下游收到错误身份。
     * 将来真有「系统触发」的调用，应该在这里给一个显式的服务身份，而不是复用本方法的兜底。
     */
    Caller current();
}
