package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiUsageSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 成本与用量看板（E3-1）：{@code GET /ai/admin/usage/summary}。
 *
 * <p>为什么是 ADMIN：账里含「谁在什么时候用了多少」——那是运营与个人信息，
 * 而且金额本身就是管理信息。网关按 {@code /ai/admin/} 前缀先拦一道，这里再复核一次。
 *
 * <p>返回口径只有一条要记住：{@code cost} 是**已定价部分**的合计，
 * 必须连带 {@code unpricedCalls} / {@code untokenizedCalls} 一起看 ——
 * 缺口非 0 时那个金额只是下限，不是「这个月花了多少」。
 */
@Slf4j
@RestController
@RequestMapping("/ai/admin/usage")
@RequiredArgsConstructor
@Tag(name = "AI 用量与成本", description = "ADMIN：AI 调用账汇总（tokens / 耗时 / 成功率 / 成本）")
public class AiUsageController {

    /** 默认看一周：既覆盖「最近怎么用的」，又不至于一次扫太久 */
    private static final int DEFAULT_DAYS = 7;

    private final AiUsageService usageService;

    @GetMapping("/summary")
    @Operation(
            summary = "AI 用量与成本汇总",
            description = "按场景与模型分组统计窗口内的调用数、tokens、成本；缺单价/缺用量的调用数如实返回")
    public Response<AiUsageSummaryVO> summary(
            @RequestParam(value = "days", defaultValue = "" + DEFAULT_DAYS) int days) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        AiUsageSummaryVO summary = usageService.summary(days);
        log.info("AI 用量看板：days={} calls={} cost={} unpriced={} untokenized={}",
                summary.getDays(), summary.getCalls(), summary.getCost(),
                summary.getUnpricedCalls(), summary.getUntokenizedCalls());
        return Response.success(summary);
    }
}
