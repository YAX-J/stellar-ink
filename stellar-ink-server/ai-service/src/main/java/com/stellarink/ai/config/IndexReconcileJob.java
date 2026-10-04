package com.stellarink.ai.config;

import com.stellarink.aiclient.client.PythonAiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 定时对账式增量索引（默认**关闭**，要显式打开）。
 *
 * <p>它是「发布/删除之后自动生效」那条链路的自动执行体：调 Python 的
 * `/admin/index/reconcile`，由那边按段落哈希比对，只重嵌变了的、删掉语料里已经没有的。
 *
 * <p>⚠️ <b>为什么默认关闭（而语料投影的定时同步是默认开启）</b>：
 * 投影同步只调 content-service 的清单接口 + 写自己的表，**不花钱**；
 * 而对账一旦发现有变更就要**真调嵌入模型** —— 那是要花额度、并且失败要留痕的操作。
 * 「装好就自动开始花钱」不是一个可以替用户决定的默认值。要打开就在配置里写：
 *
 * <pre>
 * stellar:
 *   ink:
 *     index:
 *       reconcile-enabled: true
 *       reconcile-interval-ms: 600000      # 默认 10 分钟
 *       reconcile-initial-delay-ms: 120000 # 默认 2 分钟（避开启动期）
 * </pre>
 *
 * <p>⚠️ 另一件如实说明的事：这里的嵌入调用**还没有记进 `ai_call_log`**。
 * 台账由 Java 侧写（身份/配额/场景都在那边），而定时任务没有「发起人」——
 * 要让它出现在调用记录里，需要给台账一个「系统发起」的语义（下一刀）。
 * 在那之前，请在日志里看 `scene=index` 那几行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "stellar.ink.index.reconcile-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class IndexReconcileJob {

    private final PythonAiClient pythonAiClient;

    /**
     * 固定延迟（不是固定频率）：本轮没跑完就不开下一轮 —— 对账可能因为嵌入慢而拖很久，
     * 堆叠出并发对账只会让两边都更慢，还更容易撞额度。
     */
    @Scheduled(
            initialDelayString = "${stellar.ink.index.reconcile-initial-delay-ms:120000}",
            fixedDelayString = "${stellar.ink.index.reconcile-interval-ms:600000}")
    public void reconcile() {
        try {
            Map<String, Object> result = pythonAiClient.reconcileIndex();
            log.info("索引对账完成（scene=index）：{}", result);
        } catch (RuntimeException error) {
            // 对账失败不该让调度停摆：下一轮会再来一次，而对账本身是幂等的
            log.warn("索引对账失败（本轮跳过，下一轮继续）：{}", error.toString());
        }
    }
}
