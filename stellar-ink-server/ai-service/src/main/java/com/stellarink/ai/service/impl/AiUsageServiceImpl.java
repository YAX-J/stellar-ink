package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.mapper.AiCallLogMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiCallLog;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiUsageBreakdownVO;
import com.stellarink.sharedmodel.vo.ai.AiUsageSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AI 调用账的读写与汇总。
 *
 * <p>三处刻意的口径，改之前先想清楚：
 *
 * <ol>
 *   <li><b>失败也要记</b>：只有成功记录的账无法回答「哪条链路最不稳」，
 *       而失败恰恰是最需要看的一类调用。失败时 {@code success=0}、{@code errorCode} 记异常类名
 *       （**不记报文**：上游报文可能含用户内容）。</li>
 *   <li><b>token 缺 = NULL，不是 0</b>：Agent 与评测目前都没回报 token，
 *       写成 0 会让看板显示「这些调用不花钱」。缺用量与用量为 0 是两件事。</li>
 *   <li><b>单价快照进账</b>：记账时读角色配置里的单价写进这一行。事后改单价不改写历史账目；
 *       取不到单价就留 NULL，成本按「未知」计，由 {@code unpricedCalls} 如实暴露。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    /** MDC 里的 traceId 键名（见 common-core {@code TraceIdFilter}），与响应头 {@code X-Trace-Id} 同源 */
    private static final String MDC_TRACE_ID = "traceId";

    /** 看板窗口上限：一次查 90 天够用，再长就该做预聚合而不是全表扫 */
    private static final int MAX_DAYS = 90;

    /** 模型名未知时的分组键（评测一轮会用到多个模型，故不记单一模型名） */
    private static final String UNKNOWN_KEY = "(未记录)";

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000L);

    private final AiCallLogMapper callLogMapper;

    private final AiProviderConfigMapper providerConfigMapper;

    @Override
    public void recordSuccess(AiCallScene scene, UsageDTO usage, long startedAtMillis) {
        AiCallLog row = baseRow(scene, startedAtMillis);
        row.setSuccess(1);
        if (usage != null) {
            row.setModel(usage.getModel());
            row.setPromptTokens(usage.getPromptTokens());
            row.setCompletionTokens(usage.getCompletionTokens());
            row.setTotalTokens(usage.getTotalTokens());
        }
        persist(row);
    }

    @Override
    public void recordFailure(AiCallScene scene, Throwable error, long startedAtMillis) {
        AiCallLog row = baseRow(scene, startedAtMillis);
        row.setSuccess(0);
        row.setErrorCode(error == null ? null : error.getClass().getSimpleName());
        persist(row);
    }

    @Override
    public AiUsageSummaryVO summary(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "统计窗口必须在 1 到 " + MAX_DAYS + " 天之间。");
        }
        LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        List<AiCallLog> rows = callLogMapper.selectList(
                new LambdaQueryWrapper<AiCallLog>().ge(AiCallLog::getCreatedAt, since));
        return summarize(rows, days, since);
    }

    // ------------------------------------------------------------------ 记账

    /** 组装一条账的公共部分（身份、traceId、耗时）。用 Java 实测耗时而不是上游回报值。 */
    private AiCallLog baseRow(AiCallScene scene, long startedAtMillis) {
        AiCallLog row = new AiCallLog();
        row.setScene(scene.code());
        row.setProviderRole(scene.providerRole());
        row.setTraceId(MDC.get(MDC_TRACE_ID));
        row.setUserId(safeLoginId());
        row.setRole(safeRole());
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, elapsed));
        applyPriceSnapshot(row, scene);
        return row;
    }

    /**
     * 把角色当前的单价快照进这一行。
     *
     * <p>为什么在**记账时**读单价而不是查询时 join：单价会变，而账要还原「当时花了多少」。
     * 读不到（没配角色 / 库抖动）就留 NULL —— 成本因此算不出来，由缺口计数暴露，绝不当 0。
     */
    private void applyPriceSnapshot(AiCallLog row, AiCallScene scene) {
        String providerRole = scene.providerRole();
        if (providerRole == null) {
            return;
        }
        try {
            AiProviderConfig config = providerConfigMapper.selectOne(
                    new LambdaQueryWrapper<AiProviderConfig>()
                            .select(AiProviderConfig::getPriceInputPerMillion,
                                    AiProviderConfig::getPriceOutputPerMillion)
                            .eq(AiProviderConfig::getRole, providerRole));
            if (config != null) {
                row.setPriceInput(config.getPriceInputPerMillion());
                row.setPriceOutput(config.getPriceOutputPerMillion());
            }
        } catch (RuntimeException error) {
            log.warn("AI 调用账：读取角色单价失败，本次成本按「未定价」记：role={} error={}",
                    providerRole, error.toString());
        }
    }

    private void persist(AiCallLog row) {
        try {
            callLogMapper.insert(row);
            log.debug("AI 调用账已记：scene={} success={} tokens={} latencyMs={}",
                    row.getScene(), row.getSuccess(), row.getTotalTokens(), row.getLatencyMs());
        } catch (RuntimeException error) {
            // 记账失败不抛：这次调用本身的结果由调用方决定。但要留下痕迹 —— 账缺了是要处理的
            log.warn("AI 调用账写入失败（不影响本次请求）：scene={} success={} error={}",
                    row.getScene(), row.getSuccess(), error.toString());
        }
    }

    /**
     * 取登录身份；拿不到就留空。
     *
     * <p>{@code AuthHelper} 在无 token 时会抛 401 业务异常 —— 记账路径**绝不能**把它放出去：
     * 那会把一次已经成功的调用变成 401，用户被清出登录态。
     */
    private Long safeLoginId() {
        try {
            return AuthHelper.loginId();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String safeRole() {
        try {
            Role role = AuthHelper.currentRole();
            return role == null ? null : role.name();
        } catch (RuntimeException error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 汇总

    private static AiUsageSummaryVO summarize(List<AiCallLog> rows, int days, LocalDateTime since) {
        Totals total = totalsOf(rows);
        return AiUsageSummaryVO.builder()
                .days(days)
                .since(since)
                .calls(rows.size())
                .successCalls(total.successCalls)
                .failedCalls(rows.size() - total.successCalls)
                .promptTokens(total.promptTokens)
                .completionTokens(total.completionTokens)
                .totalTokens(total.totalTokens)
                .cost(scale(total.cost))
                .unpricedCalls(total.unpricedCalls)
                .untokenizedCalls(total.untokenizedCalls)
                .byScene(breakdown(rows, AiCallLog::getScene))
                .byModel(breakdown(rows, row -> row.getModel() == null ? UNKNOWN_KEY : row.getModel()))
                .build();
    }

    private static List<AiUsageBreakdownVO> breakdown(
            List<AiCallLog> rows, Function<AiCallLog, String> keyOf) {
        Map<String, List<AiCallLog>> grouped = new LinkedHashMap<>();
        for (AiCallLog row : rows) {
            grouped.computeIfAbsent(keyOf.apply(row), key -> new ArrayList<>()).add(row);
        }
        List<AiUsageBreakdownVO> result = new ArrayList<>();
        grouped.forEach((key, group) -> {
            Totals totals = totalsOf(group);
            result.add(AiUsageBreakdownVO.builder()
                    .key(key)
                    .calls(group.size())
                    .promptTokens(totals.promptTokens)
                    .completionTokens(totals.completionTokens)
                    .totalTokens(totals.totalTokens)
                    .cost(scale(totals.cost))
                    .unpricedCalls(totals.unpricedCalls)
                    .untokenizedCalls(totals.untokenizedCalls)
                    .build());
        });
        // 调用多的排前面；同数量按键名，保证同一份数据每次输出顺序一致（便于对比与断言）
        result.sort(Comparator.comparingInt(AiUsageBreakdownVO::getCalls).reversed()
                .thenComparing(AiUsageBreakdownVO::getKey));
        return result;
    }

    private static Totals totalsOf(List<AiCallLog> rows) {
        Totals totals = new Totals();
        for (AiCallLog row : rows) {
            boolean succeeded = row.getSuccess() != null && row.getSuccess() == 1;
            if (succeeded) {
                totals.successCalls++;
            } else {
                // 失败调用**不进** token / 成本 / 缺口统计：它的用量本来就缺失或不可信，
                // 混进 untokenizedCalls 会把「上游没回报用量」说成「失败很多」。
                continue;
            }
            totals.promptTokens += intOf(row.getPromptTokens());
            totals.completionTokens += intOf(row.getCompletionTokens());
            totals.totalTokens += intOf(row.getTotalTokens());
            if (!hasTokens(row)) {
                totals.untokenizedCalls++;
                continue;
            }
            BigDecimal cost = costOf(row);
            if (cost == null) {
                totals.unpricedCalls++;
            } else {
                totals.cost = totals.cost.add(cost);
            }
        }
        return totals;
    }

    /**
     * 单条账的成本（元）；**算不出来返回 {@code null}**，不返回 0。
     *
     * <p>什么时候算不出来：没有任何 token 用量；或者用到了某种 token 却没配对应单价。
     * 部分定价（只有输入单价）同样算「算不出来」—— 报一个只含输入费用的金额会低得看不出来。
     */
    static BigDecimal costOf(AiCallLog row) {
        int prompt = intOf(row.getPromptTokens());
        int completion = intOf(row.getCompletionTokens());
        if (prompt == 0 && completion == 0) {
            return null;
        }
        if (prompt > 0 && row.getPriceInput() == null) {
            return null;
        }
        if (completion > 0 && row.getPriceOutput() == null) {
            return null;
        }
        BigDecimal cost = BigDecimal.ZERO;
        if (prompt > 0) {
            cost = cost.add(row.getPriceInput().multiply(BigDecimal.valueOf(prompt)));
        }
        if (completion > 0) {
            cost = cost.add(row.getPriceOutput().multiply(BigDecimal.valueOf(completion)));
        }
        return cost.divide(PER_MILLION, 6, RoundingMode.HALF_UP);
    }

    private static boolean hasTokens(AiCallLog row) {
        return intOf(row.getPromptTokens()) > 0
                || intOf(row.getCompletionTokens()) > 0
                || intOf(row.getTotalTokens()) > 0;
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    /** 一份聚合里的累加器；单独一个可变对象只是为了让上面的代码不必写七个局部变量 */
    private static final class Totals {
        private int successCalls;
        private int unpricedCalls;
        private int untokenizedCalls;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private BigDecimal cost = BigDecimal.ZERO;
    }
}
