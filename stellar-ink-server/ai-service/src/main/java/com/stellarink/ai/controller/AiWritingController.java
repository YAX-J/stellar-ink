package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
import com.stellarink.aiclient.dto.WritingSuggestRequestDTO;
import com.stellarink.aiclient.dto.WritingSuggestResultDTO;
import com.stellarink.aiclient.enums.WritingTask;
import com.stellarink.aiclient.enums.WritingTone;
import com.stellarink.ai.enums.AiCallScene;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiWritingStyleDTO;
import com.stellarink.sharedmodel.dto.ai.AiWritingSuggestDTO;
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
 * 星笺 Copilot：作者的写作建议（**只给候选，不写正文**）。
 *
 * <p>门槛是 AUTHOR：建议要读作者的草稿，而草稿属于创作内容；网关按
 * {@code /ai/writing/} 前缀拦一道，服务内再用 {@code requireAtLeast} 复核。
 *
 * <p>红线（`development-workflow.md` §7.4）：这一层**没有任何写入路径** ——
 * 它只把候选返回给前端；作者在执笔页看过差异预览、点「采纳」之后，
 * 正文才会通过既有的 {@code /posts/**} 落库。
 *
 * <p>日志**不记草稿内容**（那是未发表的私有内容），只记任务类型、候选数与用量。
 */
@Slf4j
@RestController
@RequestMapping("/ai/writing")
@RequiredArgsConstructor
@Tag(name = "AI 写作建议", description = "AUTHOR：润色/续写/拟标题等候选，采纳与否由作者决定")
public class AiWritingController {

    private final PythonAiClient pythonAiClient;

    /** 调用账（E3-1）：只记**真的调了模型**的路径，故只有 {@code /suggest} 记账 */
    private final AiUsageService usageService;

    @PostMapping("/suggest")
    @Operation(
            summary = "生成写作候选",
            description = "返回候选文本与理由；正文不会被改动，采纳由前端差异预览 + 作者确认完成")
    public Response<WritingSuggestResultDTO> suggest(@Valid @RequestBody AiWritingSuggestDTO request) {
        AuthHelper.requireAtLeast(com.stellarink.sharedmodel.enums.Role.AUTHOR);
        Long authorId = AuthHelper.loginId();

        WritingSuggestRequestDTO internal = WritingSuggestRequestDTO.builder()
                .task(WritingTask.valueOf(request.getTask().trim().toUpperCase()))
                .draft(request.getDraft() == null ? "" : request.getDraft())
                .instruction(trimToNull(request.getInstruction()))
                .tone(WritingTone.valueOf(request.getTone().trim().toUpperCase()))
                .candidateCount(request.getCandidateCount())
                .build();

        WritingSuggestResultDTO result = usageService.around(
                AiCallScene.WRITING_SUGGEST,
                () -> pythonAiClient.writingSuggest(internal),
                WritingSuggestResultDTO::getUsage);

        log.info("AI 写作建议完成：author={} task={} candidates={} model={} latencyMs={}",
                authorId,
                request.getTask(),
                result == null || result.getCandidates() == null ? 0 : result.getCandidates().size(),
                result == null || result.getUsage() == null ? null : result.getUsage().getModel(),
                result == null || result.getUsage() == null ? null : result.getUsage().getLatencyMs());
        return Response.success(result);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 写作风格画像（E1）：量出**当前登录作者**已发表文章的习惯。
     *
     * <p>门槛同为 AUTHOR：画像是创作辅助，读者没有草稿可辅助。
     *
     * <p>画像**只读**：Python 侧现算、不落库、不进索引，因此这里没有清理与失效的问题。
     * 它同时是 E2 只读 Agent 的前置上下文（Agent 需要「这个作者平时怎么说话」）。
     *
     * <p>⚠️ 画像**不记调用账**：它是字符级统计，一次模型都不调 —— 记进去只会让成本看板上
     * 多出一行「花了 0 元」的假调用。账只记真正调用模型的路径。
     *
     * <p>样本不够时 Python 返回 `evidenceSufficient=false` + 可读的 `notes`，
     * 这一层**原样透传**：把「还没写够」显示成「没有风格」是两回事。
     */
    @PostMapping("/style")
    @Operation(
            summary = "写作风格画像",
            description = "按当前作者已发表文章统计句长、关联词等习惯；样本不足时 evidenceSufficient=false")
    public Response<WritingStyleResultDTO> style(@Valid @RequestBody AiWritingStyleDTO request) {
        AuthHelper.requireAtLeast(com.stellarink.sharedmodel.enums.Role.AUTHOR);
        Long authorId = AuthHelper.loginId();

        WritingStyleRequestDTO internal = WritingStyleRequestDTO.builder()
                .authorId(authorId)
                .maxSamples(request.getMaxSamples())
                .build();

        WritingStyleResultDTO result = pythonAiClient.writingStyle(internal);

        // 审计：谁量的、用了几篇、量出来没有。**不记 commonPhrases 与 topTags** ——
        // 那是作者的语言指纹，日志里留一份就多一处泄露面
        log.info("AI 写作画像完成：author={} evidenceSufficient={} samples={} chars={}",
                authorId,
                result == null ? null : result.getEvidenceSufficient(),
                result == null || result.getProfile() == null ? null : result.getProfile().getSampleCount(),
                result == null || result.getProfile() == null ? null : result.getProfile().getCharCount());
        return Response.success(result);
    }
}
