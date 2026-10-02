package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiRetrievalAuditSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索审计的查看入口（M8，ADMIN）。
 *
 * <p>它回答的是回放回答不了的问题：「最近七天哪类问题总被拒答」。
 * 回放能看某一次请求的候选全文，但看不到**趋势** —— 而趋势才是决定「要不要补语料」的依据。
 *
 * <p>⚠️ 这里**看不到问题原文**（审计表只存哈希与长度）：那是刻意的口径。
 * 要追某一次的具体内容，按 traceId 去 `/ai/admin/trace/{id}` 看回放。
 */
@Tag(name = "检索审计", description = "M8：线上检索的规模、拒答率与失败率（不存问题原文）")
@RestController
@RequestMapping("/ai/admin/retrieval-audit")
@RequiredArgsConstructor
public class AiRetrievalAuditController {

    private final AiRetrievalAuditService auditService;

    @GetMapping("/summary")
    @Operation(summary = "最近 N 天的检索审计汇总")
    public Response<AiRetrievalAuditSummaryVO> summary(@RequestParam(defaultValue = "7") int days) {
        AuthHelper.requireAtLeast(com.stellarink.sharedmodel.enums.Role.ADMIN);
        int bounded = Math.min(Math.max(days, 1), 90);
        return Response.success(auditService.summarize(bounded));
    }
}
