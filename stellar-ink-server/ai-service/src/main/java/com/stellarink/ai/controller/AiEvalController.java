package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.EvalRunRequestDTO;
import com.stellarink.aiclient.dto.EvalRunResponseDTO;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评测台的对外出口（**全部要求 ADMIN**）。
 *
 * <p>这是「用户要的评测面板」真正走的路径：浏览器 → 网关 {@code /ai/admin/eval/**}（ADMIN 门槛）
 * → 本控制器（服务内再复核一次）→ {@link PythonAiClient}（带内部签名）→ Python 的评测接口。
 *
 * <p>Java 侧刻意**不做任何加工**：不排序、不裁剪、不算指标，只把请求转过去、把结果转回来。
 * 判断标准依旧是「换成另一个检索策略或指标要不要改这里」—— 要改就说明它属于 Python。
 * 唯一附加的是审计日志：跑评测是 ADMIN 动作，记一条「谁在什么时候用什么策略跑了哪份数据集」。
 *
 * <p>降级由 {@code PythonAiClientFallbackFactory} 统一给出 503，**不返回空对比表**：
 * 空表会被面板渲染成「0 分」，把「服务没连上」误报成「检索质量差」。
 */
@Slf4j
@RestController
@RequestMapping("/ai/admin/eval")
@RequiredArgsConstructor
@Tag(name = "AI 评测台", description = "ADMIN：跑黄金集检索评测、看策略对比与逐题明细")
public class AiEvalController {

    private final PythonAiClient pythonAiClient;

    @GetMapping("/datasets")
    @Operation(summary = "可评测的数据集清单", description = "面板下拉框用它填充")
    public Response<List<Map<String, Object>>> datasets() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(pythonAiClient.evalDatasets());
    }

    @GetMapping("/strategies")
    @Operation(
            summary = "标准策略组",
            description = "默认五组由 Python 定义（与命令行脚本同一份），面板据此渲染勾选项")
    public Response<List<Map<String, Object>>> strategies() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(pythonAiClient.evalStrategies());
    }

    @PostMapping("/run")
    @Operation(
            summary = "跑一轮检索评测",
            description = "请求里的 strategies 留空时由 Python 用标准五组；响应含对比表、逐题明细与诚实提示")
    public Response<EvalRunResponseDTO> run(@RequestBody EvalRunRequestDTO request) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        Long operator = AuthHelper.loginId();

        EvalRunResponseDTO result = pythonAiClient.evalRun(request);

        // 审计：谁跑了哪份数据集、几组策略、多少题。不记问题与正文（评测数据量大且无必要）
        log.info("AI 评测完成：operator={} dataset={} strategies={} cases={} models={} elapsedMs={}",
                operator,
                result == null ? null : result.getDataset(),
                result == null || result.getStrategies() == null ? 0 : result.getStrategies().size(),
                result == null || result.getCases() == null ? 0 : result.getCases().size(),
                result == null ? null : result.getModels(),
                result == null ? null : result.getElapsedMs());
        return Response.success(result);
    }
}
