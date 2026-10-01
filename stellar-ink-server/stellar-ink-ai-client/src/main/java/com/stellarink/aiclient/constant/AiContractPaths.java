package com.stellarink.aiclient.constant;

/**
 * Python AI 服务的内部接口路径。
 *
 * <p>与 {@code stellar-ink-ai} 的 FastAPI 路由一一对应：Python 仅内网可达，
 * 这些路径**不经过网关**，也不对浏览器暴露。对外路径是 {@code /ai/**}（由 ai-service 提供）。
 */
public final class AiContractPaths {

    /** Python 侧探活（ai-service 的 {@code /ai/health} 会探测它） */
    public static final String HEALTH = "/health";

    /** 流式问答（SSE）；协议转换与前端消费方就绪后再接 */
    public static final String QA_STREAM = "/qa/stream";

    /** 非流式问答：一次请求拿完整答案（当前前端走这条） */
    public static final String QA_ASK = "/qa";

    /** 写作建议 */
    public static final String WRITING_SUGGEST = "/writing/suggest";

    /** 写作风格画像（E1）：只量不写，按作者统计已发表文章的习惯 */
    public static final String WRITING_STYLE = "/writing/style";

    /** 只读 Agent（E2）：预算受限的多步检索；工具全部只读 */
    public static final String AGENT_ASK = "/agent/ask";

    /** 触发索引重建任务 */
    public static final String INDEX_REBUILD = "/admin/index/rebuild";

    /** 查询索引任务状态（{id} 由调用方拼接） */
    public static final String INDEX_JOB = "/admin/jobs/{id}";

    /** 可评测的数据集清单（评测台下拉框） */
    public static final String EVAL_DATASETS = "/eval/datasets";

    /** 标准策略组（面板首次打开时的默认勾选） */
    public static final String EVAL_STRATEGIES = "/eval/strategies";

    /** 跑一轮检索评测 */
    public static final String EVAL_RUN = "/eval/run";

    /** 按 traceId 回放 Python 侧的链路事件（E3-4）：检索 / 工具 / 模型三段 */
    public static final String TRACE_REPLAY = "/internal/trace/{traceId}";

    /** LLM Wiki 主张抽取（E4）：带证据的原子主张，引用必须能在原文里找到 */
    public static final String WIKI_CLAIMS = "/wiki/claims";

    private AiContractPaths() {
    }
}
