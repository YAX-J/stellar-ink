package com.stellarink.aiclient.client;

import com.stellarink.aiclient.constant.AiContractPaths;
import com.stellarink.aiclient.dto.AiTraceDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.aiclient.dto.AiWikiStaleRequestDTO;
import com.stellarink.aiclient.dto.AiWikiStaleResultDTO;
import com.stellarink.aiclient.dto.MemoryExtractRequestDTO;
import com.stellarink.aiclient.dto.MemoryExtractResultDTO;
import com.stellarink.aiclient.dto.MemoryPlanRequestDTO;
import com.stellarink.aiclient.dto.MemoryPlanResultDTO;
import com.stellarink.aiclient.dto.MemoryRecallRequestDTO;
import com.stellarink.aiclient.dto.MemoryRecallResultDTO;import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.aiclient.dto.EvalRunRequestDTO;
import com.stellarink.aiclient.dto.EvalRunResponseDTO;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Java → Python 的内部客户端契约（Feign）。
 *
 * <p>这是**内网协议**：路径与 8200 端口都不经网关，浏览器无法直达（红线 §7.2）。
 * 对外接口是 ai-service 的 {@code /ai/**}，两者不要混为一谈。
 *
 * <p>M0 只冻结契约：方法签名与 DTO 已定，真正的调用与签名头注入（A2 落地）
 * 由 ai-service 装配。URL 走配置项 {@code stellar.ink.ai.python-base-url}
 * （即 {@code AI_PYTHON_BASE_URL}，与探活 `AiProperties` 同一个键），不注册 Nacos：
 * Python 不参与 Java 服务发现。
 *
 * <p>⚠️ 键名踩过一次：这里曾经写的是 {@code ai.python.base-url}，而所有 yml 里配的是
 * {@code stellar.ink.ai.python-base-url} —— 占位符取不到值就退回默认的
 * {@code 127.0.0.1:8200}，于是**配置与环境变量被静默忽略**。本地碰巧一致看不出来，
 * Docker 里 Python 叫 {@code stellar-ink-ai}，就会变成「探活正常、调用全挂」。
 */
@FeignClient(
        name = "python-ai",
        url = "${stellar.ink.ai.python-base-url:http://127.0.0.1:8200}")
public interface PythonAiClient {

    /**
     * 非流式问答：一次请求拿完整答案（含引用与拒答标记）。
     *
     * <p>流式（{@code /qa/stream}）不在这里：它由 ai-service 的
     * {@code HttpQaStreamClient} 用 JDK HttpClient 单独开一条流（Feign 的解码器是
     * 「拿完整 body」语义，会把 SSE 退化成一次性响应）。
     */
    @PostMapping(
            value = AiContractPaths.QA_ASK,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    QaAnswerDTO qaAsk(@RequestBody QaStreamRequestDTO request);

    /** 写作建议（草稿只在本次请求内使用）。 */
    @PostMapping(
            value = AiContractPaths.WRITING_SUGGEST,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    WritingSuggestResultDTO writingSuggest(@RequestBody WritingSuggestRequestDTO request);

    /**
     * 写作风格画像（E1）：按作者统计已发表文章的习惯。
     *
     * <p>只读、可重算、不落库。它是 E2 只读 Agent 的前置上下文，
     * 也让 Copilot 的润色能贴合作者本来的语气。
     */
    @PostMapping(
            value = AiContractPaths.WRITING_STYLE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    WritingStyleResultDTO writingStyle(@RequestBody WritingStyleRequestDTO request);

    /**
     * 只读 Agent 问答（E2）：预算受限的多步检索，工具全部只读。
     *
     * <p>它比一次问答慢、也更贵（可能调多次模型与检索），因此预算由 ai-service 定，
     * 不由客户端传。
     */
    @PostMapping(
            value = AiContractPaths.AGENT_ASK,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AgentAskResultDTO agentAsk(@RequestBody AgentAskRequestDTO request);

    /** 触发索引重建任务（ADMIN）。 */
    @PostMapping(
            value = AiContractPaths.INDEX_REBUILD,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    IndexJobDTO rebuildIndex(@RequestBody IndexRebuildRequestDTO request);

    /** 查询索引任务状态（ADMIN）。 */
    @GetMapping(value = AiContractPaths.INDEX_JOB, produces = MediaType.APPLICATION_JSON_VALUE)
    IndexJobDTO indexJob(@PathVariable("id") String jobId);

    /**
     * 可评测的数据集清单（评测台下拉框）。
     *
     * <p>返回原始列表而不是自定义 DTO：这是**给面板读的展示数据**
     * （id / 名称 / 题目数），字段由 Python 侧 `EvalDataset.summary()` 决定，
     * 前端按字段名渲染即可；Java 不解析、不加工，避免多一层需要同步的映射。
     */
    @GetMapping(value = AiContractPaths.EVAL_DATASETS, produces = MediaType.APPLICATION_JSON_VALUE)
    List<Map<String, Object>> evalDatasets();

    /**
     * 标准策略组（评测台首次打开时的默认勾选）。
     *
     * <p>同样原样转发：默认五组由 Python 定义（与命令行脚本同一份），
     * 前端若自己写一份默认值，两边迟早会分叉。
     */
    @GetMapping(
            value = AiContractPaths.EVAL_STRATEGIES,
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<Map<String, Object>> evalStrategies();

    /** 跑一轮检索评测：返回对比表 + 逐题明细（ADMIN；只读，不改数据）。 */
    @PostMapping(
            value = AiContractPaths.EVAL_RUN,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    EvalRunResponseDTO evalRun(@RequestBody EvalRunRequestDTO request);

    /**
     * 按 traceId 取回 Python 侧的链路事件（E3-4）。
     *
     * <p>只回「这一台 Python 实例记到的事件」：缓冲有界且是进程内的，
     * 查不到时 {@code found=false} —— 调用方要把它当成「换一台再查」，而不是「链路不存在」。
     */
    @GetMapping(value = AiContractPaths.TRACE_REPLAY, produces = MediaType.APPLICATION_JSON_VALUE)
    AiTraceDTO traceReplay(@PathVariable("traceId") String traceId);

    /**
     * 抽取带证据的 Wiki 主张（E4-1）。
     *
     * <p>返回的 {@code stats.dropped} 与 {@code claims} 同等重要：它说明「模型提了多少、
     * 被证据校验挡掉多少」，是区分「模型不行」与「引用编造被挡下」的唯一线索 ——
     * Java 侧原样透出，不加工。
     */
    @PostMapping(
            value = AiContractPaths.WIKI_CLAIMS,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AiWikiClaimsResultDTO wikiClaims(@RequestBody AiWikiClaimsRequestDTO request);

    /**
     * 失效盘点（E4-11）：把库里存的主张锚点交给 Python 比对当前语料。
     *
     * <p>为什么由 Python 判：段落序号与内容哈希都是**切块的产物**，只有它知道当前是哪一版。
     * 这也顺带守住了「Python 不碰库」的边界 —— 输入由 Java 从库里读出来。
     */
    @PostMapping(
            value = AiContractPaths.WIKI_STALE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AiWikiStaleResultDTO wikiStale(@RequestBody AiWikiStaleRequestDTO request);

    /**
     * 抽取记忆候选（M9）：从一段对话里抽候选，**出处必须能在这次对话里找到**。
     *
     * <p>{@code stats.dropped} 与 {@code candidates} 同等重要：记忆抽得少时，
     * 它是区分「模型没提出」与「提出了但出处对不上」的唯一线索。
     */
    @PostMapping(
            value = AiContractPaths.MEMORY_CANDIDATES,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MemoryExtractResultDTO memoryCandidates(@RequestBody MemoryExtractRequestDTO request);

    /**
     * 算写入计划（M9）：新增 / 重复 / 冲突。
     *
     * <p>为什么由 Python 判：规则的实现（归一化、相似度、冲突阈值）只在那边有一份，
     * Java 复制一份的后果是「同一条记忆存了两行」或「冲突漏判」。
     */
    @PostMapping(
            value = AiContractPaths.MEMORY_PLAN,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MemoryPlanResultDTO memoryPlan(@RequestBody MemoryPlanRequestDTO request);

    /**
     * 算可召回的记忆（M9）：按类型 / 可信度 / 有效期过滤，排序确定。
     *
     * <p>⚠️ **用户隔离不在这里**：Java 按登录身份取出该用户的记忆再传进来 ——
     * 「用户 A 的记忆不会被 B 召回」由取数范围保证，不是靠这层的过滤条件。
     */
    @PostMapping(
            value = AiContractPaths.MEMORY_RECALL,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MemoryRecallResultDTO memoryRecall(@RequestBody MemoryRecallRequestDTO request);
}
