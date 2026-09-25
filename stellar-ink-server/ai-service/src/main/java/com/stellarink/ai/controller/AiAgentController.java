package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiAgentAskDTO;
import com.stellarink.sharedmodel.response.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读 Agent 出口（E2）：预算受限的多步检索问答。
 *
 * <p>门槛是**登录**（与一次问答同为读者功能）：Agent 查的仍然是站内已发布文章，
 * 没有比问答多出任何权限 —— 这一点必须守住，否则「Agent」会变成一个绕过权限的借口。
 *
 * <p>Java 侧只做三件事：把预算收进服务端上限、转发、审计。
 * **不解析 `steps`、不改 `doneReason`、不因 `answer` 为空而报错** ——
 * `doneReason=length`（预算触顶）是正常结果，它会带着已查到的引用回来，
 * 前端要显示「查到这些但没收敛」。
 *
 * <p>服务端预算上限：默认 4 步 / 6 次工具调用。比 Python 侧的契约上限（8 / 12）更紧 ——
 * 默认值应当保守，前端真要跑更多必须显式传，而不是让默认值就花掉双倍的钱。
 */
@Slf4j
@RestController
@RequestMapping("/ai/agent")
@RequiredArgsConstructor
@Tag(name = "AI 只读 Agent", description = "登录用户；多步检索，工具全部只读，预算受限")
public class AiAgentController {

    /** 服务端默认预算：比契约上限紧，避免「默认就很贵」 */
    static final int DEFAULT_MAX_STEPS = 4;
    static final int DEFAULT_MAX_TOOL_CALLS = 6;

    private final PythonAiClient pythonAiClient;

    @PostMapping("/ask")
    @Operation(
            summary = "只读 Agent 问答",
            description = "多步检索后给出答案与引用；doneReason=length 表示预算触顶（不是失败）")
    public Response<AgentAskResultDTO> ask(@Valid @RequestBody AiAgentAskDTO request) {
        Long userId = AuthHelper.loginId();

        AgentAskRequestDTO internal = AgentAskRequestDTO.builder()
                .question(request.getQuestion().trim())
                .maxSteps(bounded(request.getMaxSteps(), DEFAULT_MAX_STEPS))
                .maxToolCalls(bounded(request.getMaxToolCalls(), DEFAULT_MAX_TOOL_CALLS))
                .build();

        AgentAskResultDTO result = pythonAiClient.agentAsk(internal);

        // 审计：谁问的、几步、几次工具、是否收敛、用的哪个模型。
        // **不记问题原文与 thought** —— thought 里可能带上作者草稿或隐私片段
        log.info("AI Agent 完成：userId={} doneReason={} steps={} toolCalls={} citations={} model={}",
                userId,
                result == null ? null : result.getDoneReason(),
                result == null || result.getSteps() == null ? 0 : result.getSteps().size(),
                result == null ? null : result.getToolCalls(),
                result == null || result.getCitations() == null ? 0 : result.getCitations().size(),
                result == null ? null : result.getUsageModel());
        return Response.success(result);
    }

    /**
     * 预算取「客户端想给的」与「服务端默认」的更小值。
     *
     * <p>为什么不直接用客户端传的值：`@Max` 只挡住越界，挡不住「每次都传最大值」。
     * 这里取 min 的语义是「客户端只能收紧」——想跑更多必须改服务端配置，
     * 而不是让每个请求各自决定花多少钱。
     */
    private static int bounded(Integer requested, int ceiling) {
        if (requested == null) {
            return ceiling;
        }
        return Math.min(requested, ceiling);
    }
}
