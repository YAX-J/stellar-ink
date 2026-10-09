package com.stellarink.ai.config;

import com.stellarink.ai.client.PythonHealthProbe;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.sharedmodel.vo.ai.AiDailyUsageVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 成本 / 配额 / 下游可用性指标（{@code stellar.ai.*}）。
 *
 * <p>这一层最容易出的错不是「数值算错」（成本口径在 {@code AiDailyUsageTest} 里），而是
 * **把上一轮的值当成这一轮的事实**：刷新失败时清零会显示成「今天没花钱」，
 * 探活失败时连带把成本也停更则会让人以为是数据库坏了。两条都在这里钉住。
 */
class AiMetricsJobTest {

    private final AiUsageService aiUsageService = mock(AiUsageService.class);
    private final PythonHealthProbe pythonHealthProbe = mock(PythonHealthProbe.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("按场景与模型角色发布四组数，两个成本缺口各自独立")
    void publishesUsageByRoleAndScene() {
        when(aiUsageService.dailyUsage()).thenReturn(List.of(
                AiDailyUsageVO.builder()
                        .scene("qa").providerRole("chat")
                        .calls(10).successCalls(8).failedCalls(2).totalTokens(1500L)
                        .cost(new BigDecimal("1.2500"))
                        .unpricedCalls(3).untokenizedCalls(4)
                        .build()));
        when(pythonHealthProbe.probe())
                .thenReturn(PythonHealthProbe.ProbeResult.available("stellar-ink-ai", "0.1.0"));

        refreshAndRegister();

        assertEquals(10d, gauge("stellar.ai.daily.calls"), "调用数含失败");
        assertEquals(2d, gauge("stellar.ai.daily.failures"));
        assertEquals(1500d, gauge("stellar.ai.daily.tokens"));
        assertEquals(1.25d, gauge("stellar.ai.daily.cost.cny"), 1e-9);
        assertEquals(3d, meterRegistry.get("stellar.ai.daily.unpriced").gauge().value(),
                "有 token 没单价：被刻意排除在金额之外，必须单独可见");
        assertEquals(4d, meterRegistry.get("stellar.ai.daily.untokenized").gauge().value(),
                "上游没回报 token：另一个缺口，不能与 unpriced 合并");
        assertEquals(1d, meterRegistry.get("stellar.ai.python.available").gauge().value());
    }

    @Test
    @DisplayName("读库失败时保留上一轮的值：清零会显示成「今天没花钱」")
    void keepsPreviousValuesWhenRefreshFails() {
        when(aiUsageService.dailyUsage()).thenReturn(List.of(
                AiDailyUsageVO.builder().scene("qa").providerRole("chat")
                        .calls(7).failedCalls(0).totalTokens(100L)
                        .cost(new BigDecimal("0.5000")).unpricedCalls(0).untokenizedCalls(0).build()));
        when(pythonHealthProbe.probe())
                .thenReturn(PythonHealthProbe.ProbeResult.available("stellar-ink-ai", "0.1.0"));
        AiMetricsJob job = refreshAndRegister();
        assertEquals(7d, gauge("stellar.ai.daily.calls"));

        when(aiUsageService.dailyUsage()).thenThrow(new IllegalStateException("db down"));
        job.refresh();

        assertEquals(7d, gauge("stellar.ai.daily.calls"), "失败不该把已有数字清零");
        assertEquals(0.5d, gauge("stellar.ai.daily.cost.cny"), 1e-9);
    }

    @Test
    @DisplayName("今天还没有调用时清空行集，而不是留昨天的序列")
    void clearsRowsWhenNothingToday() {
        when(aiUsageService.dailyUsage()).thenReturn(List.of(
                AiDailyUsageVO.builder().scene("qa").providerRole("chat")
                        .calls(5).failedCalls(0).totalTokens(1L)
                        .cost(BigDecimal.ONE).unpricedCalls(1).untokenizedCalls(1).build()));
        when(pythonHealthProbe.probe())
                .thenReturn(PythonHealthProbe.ProbeResult.available("stellar-ink-ai", "0.1.0"));
        AiMetricsJob job = refreshAndRegister();

        when(aiUsageService.dailyUsage()).thenReturn(List.of());
        job.refresh();

        assertNull(meterRegistry.find("stellar.ai.daily.calls").gauge(),
                "跨天或清空后不能留下上一天的幽灵序列");
        assertEquals(0d, meterRegistry.get("stellar.ai.daily.unpriced").gauge().value());
        assertEquals(0d, meterRegistry.get("stellar.ai.daily.untokenized").gauge().value());
    }

    @Test
    @DisplayName("探活抛异常按不可用计，但成本指标照常更新（两类问题不能互相牵连）")
    void pythonProbeFailureDoesNotStopCostMetrics() {
        when(aiUsageService.dailyUsage()).thenReturn(List.of(
                AiDailyUsageVO.builder().scene("agent").providerRole("chat")
                        .calls(2).failedCalls(1).totalTokens(50L)
                        .cost(new BigDecimal("0.0100")).unpricedCalls(0).untokenizedCalls(0).build()));
        when(pythonHealthProbe.probe()).thenThrow(new IllegalStateException("probe blew up"));

        refreshAndRegister();

        assertEquals(0d, meterRegistry.get("stellar.ai.python.available").gauge().value());
        assertEquals(2d, gauge("stellar.ai.daily.calls"), "探活失败不该停更成本指标");
    }

    private AiMetricsJob refreshAndRegister() {
        AiMetricsJob job = new AiMetricsJob(aiUsageService, pythonHealthProbe, meterRegistry);
        job.registerGauges();
        job.refresh();
        return job;
    }

    private double gauge(String name) {
        return meterRegistry.get(name).tag("role", "chat").gauge().value();
    }
}
