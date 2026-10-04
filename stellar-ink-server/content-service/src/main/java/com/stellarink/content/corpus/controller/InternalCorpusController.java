package com.stellarink.content.corpus.controller;

import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.content.corpus.service.InternalCorpusService;
import com.stellarink.sharedmodel.enums.CorpusKind;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 语料的**内部只读**接口（`/internal/**`）。
 *
 * <p>⚠️ 三条必须保持的边界：
 *
 * <ol>
 *   <li><b>不得配置网关路由</b>：网关没有任何显式路由匹配 `/internal/**`，
 *       所以从外面访问是 404；一旦有人给网关加了 `/internal/**` 的通配路由，
 *       这个「无鉴权的内部口子」就直接暴露到公网。改动网关路由时请把这条注释一起读。</li>
 *   <li><b>它只读、且只返回公开内容</b>：调用方是 ai-service（内网），
 *       返回范围由 {@link InternalCorpusService} 收口（草稿/私有笔记永不出现）。</li>
 *   <li><b>不在这里做鉴权</b>：内部契约靠「网络不可达 + ai-service 自己的调用链」保证；
 *       如果将来需要更硬的保证，应当加内网签名校验（与 Python 的 `X-AI-*` 同思路），
 *       而不是把 Sa-Token 搬到这条路上。</li>
 * </ol>
 */
@RestController
@RequestMapping("/internal/corpus")
@RequiredArgsConstructor
public class InternalCorpusController {

    private final InternalCorpusService internalCorpusService;

    /**
     * 语料清单（不含正文）。
     *
     * @param since 只取该时间之后修改过的（ISO-8601，如 {@code 2026-10-04T18:00:00}）
     * @param ids   只取这些 id（如 {@code ?ids=1,2,3}）
     * @param limit 上限，默认 {@value InternalCorpusService#DEFAULT_LIMIT}
     */
    @GetMapping
    public Response<CorpusSliceVO> slice(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return Response.success(internalCorpusService.slice(since, ids, limit));
    }

    /**
     * 单篇正文（嵌入用）。草稿 / 已删除 / 私有笔记一律 404。
     *
     * <p>{@code kind} 非法值返回参数错误，而不是当成「没找到」—— 前者是调用方写错了，
     * 后者是内容不可见，混起来会让排查方向完全跑偏。
     */
    @GetMapping("/{kind}/{id}")
    public Response<CorpusContentVO> content(@PathVariable String kind, @PathVariable Long id) {
        CorpusKind parsed;
        try {
            parsed = CorpusKind.parse(kind);
        } catch (IllegalArgumentException error) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, error.getMessage());
        }
        if (parsed == null) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_MISSING, "缺少 kind（post / note）");
        }
        return Response.success(internalCorpusService.content(parsed, id));
    }
}
