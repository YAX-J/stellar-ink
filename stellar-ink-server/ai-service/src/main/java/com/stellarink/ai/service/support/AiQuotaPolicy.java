package com.stellarink.ai.service.support;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 配额的**纯逻辑**：键名、窗口、判定。不碰 Redis、不碰 Spring，因此可以直接断言。
 *
 * <p>为什么把这些从服务实现里拉出来：配额的坑全在「边界」上 —— 窗口是自然日还是 24 小时、
 * 到点时键该活多久、刚好等于上限算不算超。这些都不该埋在 Redis 调用中间，
 * 否则只能靠「连上 Redis 跑一遍」来验证。
 */
public final class AiQuotaPolicy {

    /** 计数键的统一前缀：与内容缓存、JWT 撤销列表分开，便于运维一眼认出 */
    public static final String KEY_PREFIX = "stellar-ink:ai:quota:";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private AiQuotaPolicy() {
    }

    /** 自然日窗口（不是「最近 24 小时」）：跨零点即重置，与「今天用了多少」的直觉一致 */
    public static String dayOf(LocalDate date) {
        return date.format(DAY);
    }

    /**
     * 计数键的存活时间：到**当日 24:00**，而不是固定 24 小时。
     *
     * <p>固定 24 小时会让「今天 09:00 用掉的额度」到明天 09:00 才释放，
     * 表现为「早上额度莫名其妙不够」；而按自然日算，用户对「明天就恢复了」有确定预期。
     */
    public static Duration windowUntilEndOfDay(LocalDateTime now) {
        Duration left = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
        // 至少留一秒：刚好卡在 23:59:59.9 时不至于给出 0 或负数被 Redis 拒绝
        return left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left;
    }

    public static String callsKeyOfUser(long userId, String day) {
        return KEY_PREFIX + "calls:user:" + userId + ":" + day;
    }

    public static String tokensKeyOfUser(long userId, String day) {
        return KEY_PREFIX + "tokens:user:" + userId + ":" + day;
    }

    public static String callsKeyOfRole(String providerRole, String day) {
        return KEY_PREFIX + "calls:role:" + providerRole + ":" + day;
    }

    /** 模型维度目前**只统计不拦截**（模型名要等调用回来才知道），用来看「钱花在哪个模型上」 */
    public static String callsKeyOfModel(String model, String day) {
        return KEY_PREFIX + "calls:model:" + model + ":" + day;
    }

    public static String inflightKeyOfUser(long userId) {
        return KEY_PREFIX + "inflight:user:" + userId;
    }

    /**
     * 判定：当前计数是否已经用满。
     *
     * <p>口径是「**已用 >= 上限就拒**」，即上限本身就是允许的最大值
     * （上限 100 时第 100 次仍然放行，第 101 次被拒）。
     */
    public static boolean exhausted(long used, int limit) {
        return limit > 0 && used >= limit;
    }
}
