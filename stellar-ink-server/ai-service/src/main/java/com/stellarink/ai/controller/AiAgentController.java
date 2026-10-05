package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AgentAskRequestDTO;
import com.stellarink.aiclient.dto.AgentAskResultDTO;
import com.stellarink.aiclient.dto.AgentVerifyRequestDTO;
import com.stellarink.aiclient.dto.AgentVerifyResultDTO;
import com.stellarink.aiclient.dto.UsageDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.service.AiUsageService;
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
 * <p>Java 侧只做三件事：把预算收进服务端上限、把 `agent`（司职）透传、审计。
 * **不解析 `steps`、不改 `doneReason`、不因 `answer` 为空而报错** ——
 * `doneReason=length`（预算触顶）是正常结果，它会带着已查到的引用回来，
 * 前端要显示「查到这些但没收敛」。
 *
 * <p>服务端预算上限：默认 4 步 / 6 次工具调用。比 Python 侧的契约上限（8 / 12）更紧 ——
 * 默认值应当保守，前端真要跑更多必须显式传，而不是让默认值就花掉双倍的钱。
 * ⚠️ **这一层只减不增**：Python 还会按司职再取一次 min（A1 起司职自己声明预算），
 * 所以「Java 放行」不等于「真的会跑那么多步」—— 两边都是收紧，没有一处放宽。
 *
 * <p>`agent`（司职）的合法性**不在这一层判**：可选司职由 Python 的注册表定义，
 * 未知名字会被它以 422 拒掉（消息里带可选清单），`PythonErrorDecoder` 原样交给用户。
 *
 * <p>A2 起多了 `POST /ai/agent/verify`（确定性引用核验）：它**一个模型都不调**，
 * 因此既不进调用账、也不占配额 —— 这两条口径写在 {@link #verify} 的注释里。
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

    /** 调用账（E3-1）：Agent 比一次问答更贵，更该记清谁跑了多少步 */
    private final AiUsageService usageService;

    @PostMapping("/ask")
    @Operation(
            summary = "只读 Agent 问答",
            description = "多步检索后给出答案与引用；doneReason=length 表示预算触顶（不是失败）")
    public Response<AgentAskResultDTO> ask(@Valid @RequestBody AiAgentAskDTO request) {
        Long userId = AuthHelper.loginId();

        // 司职名原样透传：合法与否由 Python 的注册表判（它才知道有哪些岗位），
        // 未知名字会带 422 + 可选清单回来，由 PythonErrorDecoder 原样交给用户。
        // 这里刻意**不校验白名单** —— 在 Java 抄一份可选值，迟早与 Python 分叉
        AgentAskRequestDTO internal = AgentAskRequestDTO.builder()
                .question(request.getQuestion().trim())
                .agent(trimmedAgent(request.getAgent()))
                .maxSteps(bounded(request.getMaxSteps(), DEFAULT_MAX_STEPS))
                .maxToolCalls(bounded(request.getMaxToolCalls(), DEFAULT_MAX_TOOL_CALLS))
                .build();

        // Agent 目前只回报模型名、不回报 token（见 AgentAskResultDTO）：
        // 账里仍然记下模型，token 留空由 untokenizedCalls 暴露，绝不当 0
        AgentAskResultDTO result = usageService.around(
                AiCallScene.AGENT,
                () -> pythonAiClient.agentAsk(internal),
                answer -> UsageDTO.builder().model(answer.getUsageModel()).build());

        // 审计：谁问的、哪个司职、几步、几次工具、是否收敛、用的哪个模型。
        // **不记问题原文与 thought** —— thought 里可能带上作者草稿或隐私片段
        log.info("AI Agent 完成：userId={} agent={} doneReason={} steps={} toolCalls={} citations={} model={}",
                userId,
                result == null ? null : result.getAgent(),
                result == null ? null : result.getDoneReason(),
                result == null || result.getSteps() == null ? 0 : result.getSteps().size(),
                result == null ? null : result.getToolCalls(),
                result == null || result.getCitations() == null ? 0 : result.getCitations().size(),
                result == null ? null : result.getUsageModel());
        return Response.success(result);
    }

    /**
     * 引用核验（A2）：核对「答案 + 引用」，**一个模型都不调**。
     *
     * <p>为什么不计入调用账、不占配额：核验是**确定性**的（编号越界 / 未标编号 /
     * 片段与原文对不上），没有模型调用就没有成本。给它记一笔账会让成本看板上的
     * 「agent」那一行混进零成本的调用 —— 那行数字就不再是「模型花了多少」。
     * 为同一个理由，它也**不做配额拦截**：把一次不花钱的核验拦在配额外面，
     * 只会让用户以为「AI 额度用完了，连核验都不能用」。
     *
     * <p>⚠️ 由此它**没有 {@code AiCallScene} 值**（那里放一个永不使用的枚举值，
     * 只会让人以为「核验也记账了」）。真要看用量就看日志里的
     * {@code AI 引用核验完成} 那一行。
     *
     * <p>错误口径沿用 {@code PythonErrorDecoder}：Python 的 4xx（含契约层的 422）
     * 原样翻成 {@code PythonApiException} 交给全局处理器，不在这里自造一套。
     */
    @PostMapping("/verify")
    @Operation(
            summary = "引用核验（确定性，零模型调用）",
            description = "核对答案里的编号越界 / 未标编号 / 片段与原文对不上；verdict=ok 只表示「没查出问题」")
    public Response<AgentVerifyResultDTO> verify(@Valid @RequestBody AiAgentVerifyDTO request) {
        Long userId = AuthHelper.loginId();

        AgentVerifyRequestDTO internal = AgentVerifyRequestDTO.builder()
                .answer(request.getAnswer())
                .citations(request.getCitations())
                .build();

        AgentVerifyResultDTO result = pythonAiClient.agentVerify(internal);

        // 审计：谁核的、结论、核了几条、几类问题。
        // **不记答案原文与引用片段** —— 那些是文章正文，日志里不该有
        log.info("AI 引用核验完成：userId={} verdict={} checked={} citations={} problems={} uncited={} outOfRange={}",
                userId,
                result == null ? null : result.getVerdict(),
                result == null ? null : result.getChecked(),
                request.getCitations() == null ? 0 : request.getCitations().size(),
                result == null || result.getProblems() == null ? 0 : result.getProblems().size(),
                result == null ? null : result.getUncited(),
                result == null || result.getOutOfRange() == null ? 0 : result.getOutOfRange().size());
        return Response.success(result);
    }

    /**
     * 司职名的空值归一：空白字符串按「没给」处理。
     *
     * <p>为什么不让空串一路传到 Python：Python 侧对空串也是取缺省（口径一致），
     * 但显式归一让**契约里出现的语义只有一种**（null = 没给），
     * 而不是「null 与 "" 都对」——后者每次都要去想「这两个是不是真的等价」。
     */
    private static String trimmedAgent(String agent) {
        if (agent == null) {
            return null;
        }
        String trimmed = agent.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
