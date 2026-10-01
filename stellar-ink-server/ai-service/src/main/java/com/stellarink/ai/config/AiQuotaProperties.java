package com.stellarink.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 配额（E3-2）：把「谁一天能用多少」变成可调项。
 *
 * <p><b>额度定义在配置里，计数存在 Redis 里</b> —— 这两件事必须分开看：
 * 配额是「可调项」，按仓库口径放 Nacos 动态配置（改完不用重启）；
 * Redis 只负责当天的原子计数与并发闸门。把额度也塞进 Redis 会让「额度是多少」
 * 变成一个只能问 Redis 才能回答的问题，运维与排查都会变难。
 *
 * <p>三个维度的取舍：**用户**（调用数 + token）与**角色**（调用数）在调用前就能判，
 * 所以能真正拦住；**模型**维度的上限暂时只统计不拦截 —— 模型名要等 Python 回来才知道
 * （见 {@code AiUsageServiceImpl} 的注释）。
 *
 * <p>额度为 {@code 0} 或负数表示**不限**（默认全部不限）：默认值应当保守地「不拦任何人」，
 * 只有明确配了数字才开始生效 —— 反过来（默认给一个很小的额度）会让升级后所有人的功能突然不可用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stellar.ink.ai.quota")
public class AiQuotaProperties {

    /** 关掉它，配额与并发闸门完全不碰 Redis（单测与排障用） */
    private boolean enabled = true;

    /** 每个用户每天最多调用多少次；0 = 不限 */
    private int dailyCallsPerUser = 0;

    /** 每个用户每天最多消耗多少 token（输入 + 输出）；0 = 不限 */
    private int dailyTokensPerUser = 0;

    /** 每个模型角色（chat/embedding/rerank）每天最多调用多少次；0 = 不限 */
    private int dailyCallsPerRole = 0;

    /** 同一用户同时进行的 AI 请求上限；0 = 不限。用于挡住「连点十下」把额度瞬间打光 */
    private int maxConcurrentPerUser = 0;

    /** 并发闸门键的兜底有效期：进程被 kill 时计数不会永远留在 Redis 里 */
    private int inflightTtlSeconds = 300;
}
