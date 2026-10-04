package com.stellarink.ai.corpus.config;

import com.stellarink.ai.corpus.service.CorpusSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 语料投影的定时同步（默认每 5 分钟一次）。
 *
 * <p>为什么用**定时对账**而不是「发布文章时回调一次」：
 * 事件会丢（服务重启、网络抖动、并发写），而漏一次就永久不一致；定时全量对账每轮都会自愈。
 * 「发布后几秒内可检索」是后续可加的优化（nudge），不是正确性的前提。
 *
 * <p>它**不消耗模型额度**：只调 content-service 的清单接口 + 写 `ai_content_snapshot`。
 * 真正花钱的是之后的嵌入（索引重建/增量重嵌），那是另一条链路。
 *
 * <p>开关与间隔都在配置里（`stellar.ink.content.sync-enabled` / `sync-interval-ms`），
 * 生产要关就改配置，不必改代码。关掉之后仍可用 `POST /ai/admin/corpus/sync` 手动同步。
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stellar.ink.content.sync-enabled", havingValue = "true", matchIfMissing = true)
public class CorpusSyncJob {

    private final CorpusSyncService corpusSyncService;

    /**
     * 首次延迟 30s（避开启动期），之后按间隔重复。
     *
     * <p>`fixedDelay` 而不是 `fixedRate`：这一轮没跑完就不该开下一轮 ——
     * 上游慢的时候 fixedRate 会堆叠出一串并发同步，反而更容易把内容服务压住。
     */
    @Scheduled(initialDelayString = "${stellar.ink.content.sync-initial-delay-ms:30000}",
            fixedDelayString = "${stellar.ink.content.sync-interval-ms:300000}")
    public void sync() {
        try {
            corpusSyncService.sync();
        } catch (RuntimeException error) {
            // sync() 自己已经吞掉失败并回计数；这里兜住的是更意外的异常，
            // 保证一个定时任务不会因为一次异常就再也不执行（Spring 会继续调度，但日志要留痕）
            log.warn("语料定时同步出现未预期异常（已忽略，下一轮继续）：{}", error.toString());
        }
    }
}
