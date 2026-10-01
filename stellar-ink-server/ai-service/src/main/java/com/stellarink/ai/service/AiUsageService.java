package com.stellarink.ai.service;

import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.sharedmodel.vo.ai.AiTraceCallVO;
import com.stellarink.sharedmodel.vo.ai.AiUsageSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AI 调用账（E3-1）：谁、什么场景、用了多少 token、花了多少钱。
 *
 * <p><b>记账是 best-effort，绝不影响正在处理的请求</b>：一次已经成功的问答不该因为
 * 记账失败而被报成失败（这条与前端「写成功之后的刷新失败不许把它变成失败」是同一条口径）。
 * 但「不抛错」不等于「静默」—— 入库失败会打 {@code warn} 日志，因为账缺了同样是要处理的故障。
 *
 * <p><b>账记在 Java 侧的理由</b>：身份（userId/role）只在 Java；{@code ai_*} 表归 ai-service；
 * 每次 AI 调用都必经这一层（也是将来配额拦截的同一层）。
 */
public interface AiUsageService {

    /** default 方法里的日志（接口不能继承 Lombok 的 {@code @Slf4j}） */
    Logger LOGGER = LoggerFactory.getLogger(AiUsageService.class);

    /**
     * 包住一次下游调用：成功与失败都记账，异常原样抛回调用方。
     *
     * <p>把「计时 + 记账 + 异常分类」收在唯一一处，控制器里只剩一行
     * {@code usageService.around(...)} —— 各控制器各写一遍一定会分叉：
     * 某个路径忘了记失败、某个路径用了 Python 回报的耗时而不是实测耗时。
     *
     * <p>为什么是 **default 方法**：这段逻辑不碰数据库，只用下面两个记录方法，
     * 因此它属于契约而不是实现。放在接口上的另一个好处是切片测试可以直接用
     * {@code @MockBean(answer = Answers.CALLS_REAL_METHODS)} 拿到「透传但不记账」的替身，
     * 不必在每个用例里 stub 一遍（记账不是那些测试的被测对象）。
     *
     * @param usageOf 从返回值里取用量；返回值没有用量时传 {@code null}（例如评测）
     */
    default <T> T around(AiCallScene scene, Supplier<T> call, Function<T, UsageDTO> usageOf) {
        long started = System.currentTimeMillis();
        AiQuotaTicket ticket = acquireQuota(scene);
        T result;
        try {
            result = call.get();
        } catch (RuntimeException error) {
            recordFailure(scene, error, started);
            throw error;
        } finally {
            // 无论成功、失败还是被配额拒绝之后的中断，都要放掉并发闸门；
            // 漏掉的后果是「这一天的请求全都被自己的并发上限挡住」，而日志里只有 429
            releaseQuota(ticket);
        }
        // 取用量本身失败也不该把成功的调用报成失败：用量是「账」的信息，不是这次调用的结果
        UsageDTO usage = null;
        if (usageOf != null) {
            try {
                usage = usageOf.apply(result);
            } catch (RuntimeException error) {
                LOGGER.warn("AI 调用账：从返回值取用量失败，本次按「未计量」记账：scene={} error={}",
                        scene.code(), error.toString());
            }
        }
        recordSuccess(scene, usage, started);
        return result;
    }

    /**
     * 调用前的配额检查（E3-2）：超限抛 429，Redis 不可用时**放行**（fail-open，见下）。
     *
     * <p>为什么 fail-open：配额防的是「把自己的钱烧光」，不是攻击边界。
     * 反过来的口径（Redis 一抖就拒绝所有 AI 请求）会把一次缓存故障升级成整站 AI 不可用 ——
     * 那比暂时少拦几次贵得多。安全边界（如 JWT 撤销）才用 fail-closed，两者的取舍不同。
     * 放行时**必须留下 warn 日志**，否则「配额为什么没生效」会变成一个查不出来的问题。
     */
    AiQuotaTicket acquireQuota(AiCallScene scene);

    /** 放掉并发闸门；{@code ticket} 为空时什么都不做（配额关闭或 Redis 不可用时就是空票） */
    void releaseQuota(AiQuotaTicket ticket);

    /** 记一次成功调用；{@code usage} 为 {@code null} 表示上游没回报用量（**不是 0**）。 */
    void recordSuccess(AiCallScene scene, UsageDTO usage, long startedAtMillis);

    /** 记一次失败调用（异常穿透过客户端）。失败也要记，否则失败率无从统计。 */
    void recordFailure(AiCallScene scene, Throwable error, long startedAtMillis);

    /** 汇总窗口内的账（看板用）。缺单价 / 缺 token 的调用数会如实返回，不会当 0 混进金额。 */
    AiUsageSummaryVO summary(int days);

    /**
     * 按 traceId 取这次链路的全部调用记录（E3-4 回放用），按发生顺序。
     *
     * <p>放在调用账服务里而不是新建一个「回放服务」：读的是同一张表，
     * 而新建一个注入 Mapper 的 {@code @Service} 会让 ai-service 的**每个** {@code @WebMvcTest}
     * 切片都得多一个 {@code @MockBean}（这个坑已经踩过两次）。
     */
    List<AiTraceCallVO> traceCalls(String traceId);
}
