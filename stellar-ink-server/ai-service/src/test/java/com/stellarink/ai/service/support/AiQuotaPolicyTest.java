package com.stellarink.ai.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额窗口的纯逻辑：**边界**都在这里，不连 Redis 也能钉住。
 *
 * <p>这些判据来自两类真实困惑：额度「什么时候恢复」（自然日 vs 滚动 24 小时）、
 * 以及「刚好用到上限算不算超」。写错任何一个，用户看到的都是「额度莫名其妙不够用」。
 */
class AiQuotaPolicyTest {

    @Test
    @DisplayName("窗口按自然日：23:59 的计数只剩 1 分钟，而不是活满 24 小时")
    void windowEndsAtMidnight() {
        Duration left = AiQuotaPolicy.windowUntilEndOfDay(
                LocalDateTime.of(2026, 10, 1, 23, 59, 0));

        assertEquals(Duration.ofMinutes(1), left);
    }

    @Test
    @DisplayName("窗口下限保底 1 秒：卡在 23:59:59.9 也不能给出 0 或负数（Redis 会拒绝）")
    void windowNeverGoesNonPositive() {
        assertFalse(AiQuotaPolicy.windowUntilEndOfDay(
                LocalDateTime.of(2026, 10, 1, 23, 59, 59, 999_999_999)).isZero());
    }

    @Test
    @DisplayName("键名带自然日：跨零点自动换键，不需要任何清理任务")
    void keysCarryTheDay() {
        String day = AiQuotaPolicy.dayOf(LocalDate.of(2026, 10, 1));

        assertEquals("20261001", day);
        assertEquals("stellar-ink:ai:quota:calls:user:7:20261001",
                AiQuotaPolicy.callsKeyOfUser(7L, day));
        assertEquals("stellar-ink:ai:quota:tokens:user:7:20261001",
                AiQuotaPolicy.tokensKeyOfUser(7L, day));
        assertEquals("stellar-ink:ai:quota:calls:role:chat:20261001",
                AiQuotaPolicy.callsKeyOfRole("chat", day));
        assertEquals("stellar-ink:ai:quota:calls:model:deepseek-flash:20261001",
                AiQuotaPolicy.callsKeyOfModel("deepseek-flash", day));
        assertEquals("stellar-ink:ai:quota:inflight:user:7",
                AiQuotaPolicy.inflightKeyOfUser(7L), "并发闸门与自然日无关，是「此刻在飞」的计数");
    }

    @Test
    @DisplayName("上限即允许的最大值：用满 100 次后第 101 次才拒")
    void limitIsInclusive() {
        assertFalse(AiQuotaPolicy.exhausted(99, 100), "还没到上限 → 放行");
        assertTrue(AiQuotaPolicy.exhausted(100, 100), "刚好等于上限 → 拒（第 100 次已经用掉了）");
        assertTrue(AiQuotaPolicy.exhausted(101, 100));
    }

    @Test
    @DisplayName("0 与负数表示不限：默认必须「不拦任何人」，否则升级会让功能突然不可用")
    void zeroMeansUnlimited() {
        assertFalse(AiQuotaPolicy.exhausted(1_000_000, 0));
        assertFalse(AiQuotaPolicy.exhausted(1_000_000, -1));
    }

    @Test
    @DisplayName("窗口起点是当天零点，与 dayOf 同一天")
    void windowIsAnchoredAtMidnight() {
        LocalDateTime now = LocalDateTime.of(LocalDate.of(2026, 10, 1), LocalTime.of(9, 30));

        assertEquals(LocalDate.of(2026, 10, 2).atStartOfDay(), now.plus(AiQuotaPolicy.windowUntilEndOfDay(now)));
    }
}
