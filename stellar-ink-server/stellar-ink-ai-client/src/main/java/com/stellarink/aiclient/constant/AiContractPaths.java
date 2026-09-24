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

    /** 流式问答（SSE） */
    public static final String QA_STREAM = "/qa/stream";

    /** 写作建议 */
    public static final String WRITING_SUGGEST = "/writing/suggest";

    /** 触发索引重建任务 */
    public static final String INDEX_REBUILD = "/admin/index/rebuild";

    /** 查询索引任务状态（{id} 由调用方拼接） */
    public static final String INDEX_JOB = "/admin/jobs/{id}";

    private AiContractPaths() {
    }
}
