package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * AI 服务健康状态（{@code GET /ai/health} 的 data 部分），对**所有角色公开**。
 *
 * <p>安全边界：只暴露「能不能用」，不暴露任何配置细节 ——
 * 不含 Python 内网地址、端口、模型名、Provider、密钥是否存在等信息。
 * 排查需要的细节留在服务端日志里（运维看日志，不靠公开接口）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiHealthVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Java 侧服务名，供前端/监控确认打到了哪个服务 */
    private String service;

    /** 服务版本 */
    private String version;

    /** 运行环境名（dev / prod），便于区分探活目标 */
    private String env;

    /** AI 能力是否整体可用（要求 Java 侧与 Python 侧同时就绪） */
    private Boolean available;

    /**
     * 不可用时的可读原因，例如「下游 AI 编排服务未就绪」。
     * <p>刻意不写具体地址与错误堆栈：这是公开接口。
     */
    private String reason;

    /** 下游（Python）是否可用；与 {@code available} 分开，便于定位是 Java 还是 Python 的问题 */
    private Boolean downstreamAvailable;

    /** 检查时间 */
    private Instant checkedAt;
}
