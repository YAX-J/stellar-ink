package com.stellarink.ai.config;

import com.stellarink.ai.client.PythonHealthProbe;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.sharedmodel.vo.ai.AiDailyUsageVO;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 域的成本、配额与下游可用性指标（{@code stellar.ai.*}）。
 *
 * <h2>为什么这些数必须从 {@code ai_call_log} 反推，而不是给 Python 加 /metrics</h2>
 * AI 每次调用都花钱，而「花了多少」的**唯一真相源**是 Java 侧的调用账
 * （身份、角色、单价快照、失败分类都在那边，Python 侧没有身份）。
 * 给 Python 加一套 Prometheus 指标只会得到第二套口径 —— 与
 * {@code /ai/admin/usage/summary} 对不上的那种。所以这里读的是同一张表，
 * 并且成本计算**复用** {@link AiUsageService#dailyUsage()}（口径只有一份）。
 *
 * <h2>为什么用 Gauge 而不是 Counter</h2>
 * 这三个数是「**当日累计**」，每天零点归零。Counter 只能单调递增、也不会自己归零，
 * 用 Counter 表达「今日累计」就得在跨天时手工重置，而重置漏了会得到一个只增不减的曲线
 * —— 看起来完全正常，只是数字永远不对。Gauge + 每分钟重算没有这个问题：
 * 它是**采样值**（阶跃），不是说「累计了多少次事件」。
 *
 * <h2>为什么用 MultiGauge</h2>
 * 「角色 × 场景」的组合在启动时并不确定（评测用多个模型、场景会新增）。
 * 普通 Gauge 要求注册时就固定标签集合；MultiGauge 支持每次刷新整体替换行集
 * （{@code overwrite=true}，本轮不存在的组合会被移除，不会留下昨天的幽灵序列）。
 *
 * <p>⚠️ 不要凭印象给 Builder 加 {@code strongReference(true)}：这个 Micrometer 版本的
 * {@code MultiGauge.Builder} **没有**那个方法（它属于别的计量器）。行集由 MultiGauge 自己持有，
 * 而它作为 Meter 被注册表强引用 —— {@code AiMetricsJobTest} 直接断言了刷新之后读得到值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "stellar.ink.metrics.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AiMetricsJob {

    private static final String METRIC_CALLS = "stellar.ai.daily.calls";
    private static final String METRIC_FAILURES = "stellar.ai.daily.failures";
    private static final String METRIC_TOKENS = "stellar.ai.daily.tokens";
    private static final String METRIC_COST = "stellar.ai.daily.cost.cny";
    private static final String METRIC_UNPRICED = "stellar.ai.daily.unpriced";
    private static final String METRIC_UNTOKENIZED = "stellar.ai.daily.untokenized";
    private static final String METRIC_PYTHON_AVAILABLE = "stellar.ai.python.available";

    private static final String TAG_SCENE = "scene";
    private static final String TAG_ROLE = "role";

    private final AiUsageService aiUsageService;
    private final PythonHealthProbe pythonHealthProbe;
    private final MeterRegistry meterRegistry;

    private MultiGauge calls;
    private MultiGauge failures;
    private MultiGauge tokens;
    private MultiGauge cost;

    /** 两个「成本缺口」是全局计数（它们回答的是「金额能不能信」，不需要再分场景） */
    private final AtomicLong unpriced = new AtomicLong();
    private final AtomicLong untokenized = new AtomicLong();

    /** Python 是否可用：0/1。博客的读写不受影响，但问答/写作/Copilot 全依赖它 */
    private final AtomicLong pythonAvailable = new AtomicLong();

    @PostConstruct
    void registerGauges() {
        calls = multiGauge(METRIC_CALLS, "当日 AI 调用数（含失败）按场景与模型角色");
        failures = multiGauge(METRIC_FAILURES, "当日 AI 失败调用数；失败也记账，否则失败率无从统计");
        tokens = multiGauge(METRIC_TOKENS, "当日 token 合计（只统计成功调用：失败的用量本来就缺失）");
        cost = multiGauge(METRIC_COST, "当日已定价部分的成本（**元**）；"
                + "若 stellar_ai_daily_unpriced 或 _untokenized 非 0，这个金额只是下限");

        Gauge.builder(METRIC_UNPRICED, unpriced, AtomicLong::doubleValue)
                .description("当日「有 token 但没配单价」的调用数 —— 这些调用被刻意排除在成本金额之外；"
                        + "把它们当 0 计进金额会得到一个偏低的假成本")
                .register(meterRegistry);
        Gauge.builder(METRIC_UNTOKENIZED, untokenized, AtomicLong::doubleValue)
                .description("当日「上游没回报 token」的调用数（Agent 与评测当前都属于这类）；"
                        + "它与 unpriced 是两个不同的缺口：这里是「不知道用了多少」")
                .register(meterRegistry);
        Gauge.builder(METRIC_PYTHON_AVAILABLE, pythonAvailable, AtomicLong::doubleValue)
                .description("Python AI 编排服务是否可用（0/1）。0 时问答/写作/Copilot 不可用，"
                        + "博客的读写功能不受影响")
                .register(meterRegistry);
        log.info("AI 指标已注册：{} / {} / {} / {} / {} / {} / {}",
                METRIC_CALLS, METRIC_FAILURES, METRIC_TOKENS, METRIC_COST,
                METRIC_UNPRICED, METRIC_UNTOKENIZED, METRIC_PYTHON_AVAILABLE);
    }

    /**
     * 刷新一轮。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：上一轮没跑完就不该开下一轮
     * （与语料同步、索引对账同一个理由）。
     *
     * <p>两段各自 try/catch：**数据库读不到不该连带把 Python 可用性也停更** ——
     * 那会让「Python 挂了」这件事在指标上表现为「AI 全部指标都卡住了」，
     * 排查方向直接被带偏。
     */
    @Scheduled(
            initialDelayString = "${stellar.ink.metrics.initial-delay-ms:45000}",
            fixedDelayString = "${stellar.ink.metrics.interval-ms:60000}")
    public void refresh() {
        refreshDailyUsage();
        refreshPythonAvailability();
    }

    private void refreshDailyUsage() {
        List<AiDailyUsageVO> rows;
        try {
            rows = aiUsageService.dailyUsage();
        } catch (RuntimeException error) {
            // 指标刷新失败不该影响任何业务请求：看板停在上一次的值，日志里留下原因
            log.warn("AI 成本指标刷新失败（看板将停在上一次的值）：error={}", error.toString());
            return;
        }
        if (rows == null || rows.isEmpty()) {
            // 今天还没有任何调用：清空行集，而不是留下昨天的序列
            calls.register(List.of(), true);
            failures.register(List.of(), true);
            tokens.register(List.of(), true);
            cost.register(List.of(), true);
            unpriced.set(0L);
            untokenized.set(0L);
            return;
        }

        List<MultiGauge.Row<?>> callRows = new ArrayList<>(rows.size());
        List<MultiGauge.Row<?>> failureRows = new ArrayList<>(rows.size());
        List<MultiGauge.Row<?>> tokenRows = new ArrayList<>(rows.size());
        List<MultiGauge.Row<?>> costRows = new ArrayList<>(rows.size());
        long unpricedTotal = 0L;
        long untokenizedTotal = 0L;

        for (AiDailyUsageVO row : rows) {
            Tags tags = Tags.of(TAG_ROLE, row.getProviderRole(), TAG_SCENE, row.getScene());
            callRows.add(MultiGauge.Row.of(tags, valueOrZero(row.getCalls())));
            failureRows.add(MultiGauge.Row.of(tags, valueOrZero(row.getFailedCalls())));
            tokenRows.add(MultiGauge.Row.of(tags, valueOrZero(row.getTotalTokens())));
            // 成本为 null（完全算不出来）时按 0 计入**金额**、由缺口计数如实暴露 ——
            // 这是刻意的：金额只统计已定价部分，缺口另有两个指标回答「可信度」。
            costRows.add(MultiGauge.Row.of(tags, row.getCost() == null ? 0d : row.getCost().doubleValue()));
            unpricedTotal += valueOrZero(row.getUnpricedCalls());
            untokenizedTotal += valueOrZero(row.getUntokenizedCalls());
        }

        calls.register(callRows, true);
        failures.register(failureRows, true);
        tokens.register(tokenRows, true);
        cost.register(costRows, true);
        unpriced.set(unpricedTotal);
        untokenized.set(untokenizedTotal);
        log.debug("AI 指标刷新完成：分组={} 未计价={} 未计量={}", rows.size(), unpricedTotal, untokenizedTotal);
    }

    private void refreshPythonAvailability() {
        try {
            boolean available = pythonHealthProbe.probe().available();
            pythonAvailable.set(available ? 1L : 0L);
            if (!available) {
                // 探活失败的原因只进日志（它在 AiHealthController 里也不回显细节）
                log.warn("指标：Python 编排服务探活为不可用（问答/写作/Copilot 将不可用）");
            }
        } catch (RuntimeException error) {
            pythonAvailable.set(0L);
            log.warn("指标：Python 探活本身抛异常，按不可用计：error={}", error.toString());
        }
    }

    private MultiGauge multiGauge(String name, String description) {
        return MultiGauge.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private static double valueOrZero(Number value) {
        return value == null ? 0d : value.doubleValue();
    }
}
