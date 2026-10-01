package com.stellarink.ai.controller;

import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM Wiki（E4-2）：构建出口（ADMIN）与读者侧读取。
 *
 * <p>两个出口的门槛**刻意不同**：
 * <ul>
 *   <li>{@code POST /ai/admin/wiki/build}：ADMIN —— 它是批量的模型调用，直接花钱，
 *       而且是「重写知识库」这种影响全站内容的动作；</li>
 *   <li>{@code GET /ai/wiki/...}：**公开可读**（与文章本身的可见性一致）。
 *       主张连同 `quote` 一起返回，读者能自己核对这是不是文章说的 ——
 *       要登录才能看证据的话，Wiki 就变成了「信我」。</li>
 * </ul>
 *
 * <p>⚠️ 读者侧只回**已发布文章**的主张：这张表按 `post_id` 存，本身没有可见性判断，
 * 而 Python 抽取的语料就是「已发布文章」。将来语料若含未发布内容，
 * 这里必须补一道可见性过滤（届时以业务库的 `post.status` 为准 —— 见 status.md 的待办）。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "LLM Wiki", description = "带证据的知识条目：ADMIN 构建，读者可按文章读取")
public class AiWikiController {

    /** 服务端默认预算：一次最多抽几篇文章（客户端只能收紧） */
    static final int DEFAULT_MAX_POSTS = 5;
    /** 每段最多接受几条主张 */
    static final int DEFAULT_MAX_CLAIMS_PER_CHUNK = 3;

    private final AiWikiService wikiService;

    @PostMapping("/admin/wiki/build")
    @Operation(
            summary = "构建带证据的 Wiki 主张（ADMIN）",
            description = "按文章逐篇抽取原子主张并落库；返回抽取统计与落库统计（新增/更新/未变动）")
    public Response<AiWikiBuildVO> build(@RequestBody(required = false) AiWikiClaimsRequestDTO request) {
        AuthHelper.requireAtLeast(Role.ADMIN);

        // 预算只能收紧：`bounded` 取 min，与 Agent 同一条口径。
        // 不这么做的话，每个请求都能自行决定「这次花多少钱」
        Integer maxPosts = bounded(
                request == null ? null : request.getMaxPosts(), DEFAULT_MAX_POSTS);
        Integer maxClaimsPerChunk = bounded(
                request == null ? null : request.getMaxClaimsPerChunk(),
                DEFAULT_MAX_CLAIMS_PER_CHUNK);

        AiWikiBuildVO result = wikiService.build(AiWikiClaimsRequestDTO.builder()
                .maxPosts(maxPosts)
                .maxClaimsPerChunk(maxClaimsPerChunk)
                .build());
        log.info("Wiki 构建：抽取 {}/{} 条，落库 新增{} 更新{} 未变动{}",
                result.getKept(), result.getProposed(),
                result.getInserted(), result.getUpdated(), result.getSkipped());
        return Response.success(result);
    }

    @GetMapping("/wiki/posts/{postId}/claims")
    @Operation(summary = "按文章读 Wiki 主张（公开）", description = "按段落序号排序，每条都带原文片段")
    public Response<List<AiWikiClaimVO>> claimsOfPost(@PathVariable("postId") Long postId) {
        return Response.success(wikiService.claimsOfPost(postId));
    }

    @GetMapping("/wiki/claims/count")
    @Operation(summary = "某篇文章有多少条 Wiki 主张（公开）", description = "读者侧据此判断要不要显示入口")
    public Response<Long> countOfPost(@RequestParam("postId") Long postId) {
        return Response.success(wikiService.countOfPost(postId));
    }

    private static int bounded(Integer requested, int ceiling) {
        if (requested == null) {
            return ceiling;
        }
        return Math.min(requested, ceiling);
    }
}
