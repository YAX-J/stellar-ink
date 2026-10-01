package com.stellarink.ai.service;

/**
 * 一次调用占用的配额凭据：现在是「并发闸门是否握在手里」。
 *
 * <p>为什么要有这个对象而不是让 {@code releaseQuota} 自己重新算一遍用户 id：
 * 释放时必须对应**当初申请的那一次** —— 重新推导一遍（再读一次登录态）会在
 * 登录态变化或异常路径上释放错误的键，而那种错只会表现为「并发上限慢慢失效」。
 *
 * @param userId      申请时识别到的用户；为空表示这次没有用户维度（未登录或拿不到身份）
 * @param inflightKey 并发计数键；为空表示这次没有占用闸门
 */
public record AiQuotaTicket(Long userId, String inflightKey) {

    /** 空票：配额关闭、Redis 不可用、或拿不到用户身份时使用 */
    public static final AiQuotaTicket NONE = new AiQuotaTicket(null, null);

    public boolean holdsInflight() {
        return inflightKey != null;
    }
}
