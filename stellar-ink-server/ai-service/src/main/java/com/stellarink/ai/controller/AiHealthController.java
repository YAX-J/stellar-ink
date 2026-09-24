package com.stellarink.ai.controller;

import com.stellarink.ai.client.PythonHealthProbe;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiHealthVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * AI 服务健康检查：{@code GET /ai/health}，**公开**（不需要登录）。
 *
 * <p>为什么要公开：运维与前端需要在未登录时判断「AI 能不能用」，
 * 而排查故障时最怕的就是「探活接口本身要鉴权」。
 * 代价是必须克制输出内容 —— 只回能力状态与可读原因，不回配置细节。
 *
 * <p>与 Python 侧 {@code GET /health} 的关系：本接口是**对外聚合**结果，
 * Python 的接口只在编排网络内可见（M1 起由 ai-service 调用）。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI 健康检查", description = "AI 能力可用性探测（公开）")
public class AiHealthController {

    private final PythonHealthProbe pythonHealthProbe;
    private final Environment environment;

    @Value("${spring.application.name:ai-service}")
    private String serviceName;

    @Value("${spring.application.version:1.0.0-SNAPSHOT}")
    private String serviceVersion;

    @GetMapping("/health")
    @Operation(summary = "AI 服务健康状态", description = "公开接口，只返回能力可用性与可读原因，不泄露配置")
    public Response<AiHealthVO> health() {
        PythonHealthProbe.ProbeResult downstream = pythonHealthProbe.probe();
        String env = environment.getActiveProfiles().length == 0
                ? "default"
                : environment.getActiveProfiles()[0];

        boolean available = downstream.available();
        String reason = available ? null : "下游 AI 编排服务未就绪";

        if (!available) {
            // 原因细节只写日志（运维看日志），响应里给可读结论
            log.warn("AI 健康检查：下游不可用，原因={}", downstream.reason());
        }

        return Response.success(AiHealthVO.builder()
                .service(serviceName)
                .version(serviceVersion)
                .env(env)
                .available(available)
                .reason(reason)
                .downstreamAvailable(downstream.available())
                .checkedAt(Instant.now())
                .build());
    }
}
