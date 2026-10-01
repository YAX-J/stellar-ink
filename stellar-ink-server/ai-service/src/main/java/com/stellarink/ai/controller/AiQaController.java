package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.QaAnswerDTO;
import com.stellarink.aiclient.dto.QaStreamRequestDTO;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiAskDTO;
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
 * 星海问答：读者就全站已发布文章提问，回答必须带引用（或明确拒答）。
 *
 * <p>门槛是**登录**（READER 及以上）—— 这不是写作辅助，任何人都能问；
 * 每次调用会记一条账（谁、哪个模型、多少 token、多少耗时），见 {@code AiUsageService}。
 *
 * <p>Java 侧只做协议转换：请求体映射成内部契约、把当前用户 id 带进签名头（由
 * {@code InternalSignatureFeignInterceptor} 完成）、把结果原样返回。**答案与引用一律来自 Python**，
 * 这一层不拼、不改、不补 —— 一旦在这里「顺手润色」，引用就可能对不上原文。
 *
 * <p>为什么保留非流式：流式（{@code /ai/qa/stream}，见 {@link AiQaStreamController}）需要
 * 逐帧转发与「断开即取消下游」，链路更长、失败面更大；一次性回答是它的**降级路径**，
 * 也是首屏「问一句就走」的场景里更省事的选择。两条共用同一套 Python 编排，不会给出不同结论。
 */
@Slf4j
@RestController
@RequestMapping("/ai/qa")
@RequiredArgsConstructor
@Tag(name = "AI 星海问答", description = "登录用户就全站文章提问；回答带引用，证据不足时明确拒答")
public class AiQaController {

    private final PythonAiClient pythonAiClient;

    /** 调用账（E3-1）：成功与失败都记一条，见 {@link AiUsageService} */
    private final AiUsageService usageService;

    @PostMapping
    @Operation(
            summary = "就全站文章提问",
            description = "返回答案与引用；evidenceSufficient=false 表示文章里没有依据（前端要显示拒答文案）")
    public Response<QaAnswerDTO> ask(@Valid @RequestBody AiAskDTO request) {
        Long userId = AuthHelper.loginId();

        QaStreamRequestDTO internal = QaStreamRequestDTO.builder()
                .question(request.getQuestion().trim())
                .topK(request.getTopK())
                .build();

        QaAnswerDTO answer = usageService.around(
                AiCallScene.QA, () -> pythonAiClient.qaAsk(internal), QaAnswerDTO::getUsage);

        // 审计：谁问了、有几个引用、是否拒答、用的哪个模型。**不记问题原文**（可能含个人信息）
        log.info("AI 问答完成：userId={} citations={} evidenceSufficient={} model={} latencyMs={}",
                userId,
                answer == null || answer.getCitations() == null ? 0 : answer.getCitations().size(),
                answer == null ? null : answer.getEvidenceSufficient(),
                answer == null || answer.getUsage() == null ? null : answer.getUsage().getModel(),
                answer == null || answer.getUsage() == null ? null : answer.getUsage().getLatencyMs());
        return Response.success(answer);
    }
}
