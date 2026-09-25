package com.stellarink.aiclient.client;

import com.stellarink.aiclient.constant.AiContractPaths;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
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
import com.stellarink.aiclient.fallback.PythonAiClientFallbackFactory;
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
        url = "${stellar.ink.ai.python-base-url:http://127.0.0.1:8200}",
        fallbackFactory = PythonAiClientFallbackFactory.class)
public interface PythonAiClient {

    /** Python 侧探活（原始 JSON，便于 ai-service 判定降级原因）。 */
    @GetMapping(AiContractPaths.HEALTH)
    Map<String, Object> health();

    /**
     * 流式问答。
     *
     * <p>返回类型暂定 {@code Object}：打通 SSE 时再换成具体的事件流类型
     * （Spring 侧用 {@code ResponseBodyEmitter} / WebClient 流，由 ai-service 决定；
     * 客户端不提前绑定某种传输实现）。
     */
    @PostMapping(
            value = AiContractPaths.QA_STREAM,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Object qaStream(@RequestBody QaStreamRequestDTO request);

    /**
     * 非流式问答：一次请求拿完整答案（含引用与拒答标记）。
     *
     * <p>与 {@link #qaStream} 并存是有意的：SSE 那条要等 Java 协议转换与前端消费方一起接，
     * 而「检索 → 引用 → 拒答」的编排已经能用了 —— 先用它把功能交付出去，
     * 而不是让用户等一条还没人消费的流式通道。
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
}
