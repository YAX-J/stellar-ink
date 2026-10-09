package com.stellarink.ai.service.impl;

import com.stellarink.ai.config.AiQuotaProperties;
import com.stellarink.ai.mapper.AiCallLogMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiCallLog;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.vo.ai.AiDailyUsageVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 当日用量按「场景 + 模型角色」的聚合（指标看板的数据源）。
 *
 * <p>它是**成本口径的唯一出口**：指标层不再自己算一遍成本，而是拿这里的数。
 * 所以这里要盯住三件事 —— 失败调用只计数不污染 token/成本、
 * 「有 token 没单价」与「上游没回报 token」是两个不同的缺口、成本只累加算得出来的那些。
 */
class AiDailyUsageTest {

    private final AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

    @Test
    @DisplayName("分组、三档计数与两个成本缺口各归各位")
    void groupsBySceneAndRoleWithBothGapsSeparated() {
        when(callLogMapper.selectList(any())).thenReturn(List.of(
                // 成功、有完整用量与单价 → 计价
                row("qa", "chat", 1, 100, 200, 300, new BigDecimal("1"), new BigDecimal("2")),
                // 失败：只该计进 calls，不该动 token / 成本
                row("qa", "chat", 0, null, null, null, null, null),
                // 有 token 但输入单价缺失 → 算不出成本，进 unpriced 而不是当 0
                row("qa", "chat", 1, 100, null, 100, null, null),
                // 上游没回报任何 token → 根本不知道用了多少，进 untokenized
                row("agent", "chat", 1, null, null, null, null, null)));

        List<AiDailyUsageVO> rows = dailyUsage();

        assertEquals(2, rows.size(), "应当是 qa 与 agent 两组");
        // 调用多的排前面，同一份数据每次输出顺序一致
        AiDailyUsageVO qa = rows.get(0);
        assertEquals("qa", qa.getScene());
        assertEquals("chat", qa.getProviderRole());
        assertEquals(3, qa.getCalls(), "失败也要计数，否则失败率无从统计");
        assertEquals(2, qa.getSuccessCalls());
        assertEquals(1, qa.getFailedCalls());
        assertEquals(400L, qa.getTotalTokens(), "只统计成功调用的用量");
        assertEquals(1, qa.getUnpricedCalls(), "有 token 没单价 = unpriced");
        assertEquals(0, qa.getUntokenizedCalls());
        assertEquals(0, qa.getCost().compareTo(new BigDecimal("0.0005")),
                "只有计价得了的那条进了金额：100×1 + 200×2 = 500，除以 1e6 再按 4 位小数收口");

        AiDailyUsageVO agent = rows.get(1);
        assertEquals("agent", agent.getScene());
        assertEquals(1, agent.getCalls());
        assertEquals(1, agent.getUntokenizedCalls());
        assertEquals(0, agent.getUnpricedCalls(), "「不知道用了多少」与「没配单价」必须是两个缺口");
        assertEquals(0, agent.getCost().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("今天没有任何调用时返回空列表（指标层据此清空行集，而不是留昨天的序列）")
    void emptyWhenNothingToday() {
        when(callLogMapper.selectList(any())).thenReturn(List.of());

        assertTrue(dailyUsage().isEmpty());
    }

    private List<AiDailyUsageVO> dailyUsage() {
        AiUsageServiceImpl service = new AiUsageServiceImpl(
                callLogMapper, mock(AiProviderConfigMapper.class), mock(RedisUtils.class),
                new AiQuotaProperties(), new SimpleMeterRegistry());
        return service.dailyUsage();
    }

    private static AiCallLog row(String scene, String role, int success,
                                 Integer prompt, Integer completion, Integer total,
                                 BigDecimal priceInput, BigDecimal priceOutput) {
        AiCallLog row = new AiCallLog();
        row.setScene(scene);
        row.setProviderRole(role);
        row.setSuccess(success);
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setTotalTokens(total);
        row.setPriceInput(priceInput);
        row.setPriceOutput(priceOutput);
        return row;
    }
}
