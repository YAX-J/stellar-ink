package com.stellarink.contentclient.client;

import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Java → content-service 的语料读取契约（RAG 知识库的上游）。
 *
 * <p>调用方是 ai-service 的语料同步：把「已发布文章 + 已发布且公开的笔记」投影进自己的
 * `ai_content_snapshot`（见 `deploy/sql/19_ai_content_snapshot.sql`）。
 * 为什么不让 ai-service 直接读 `post`/`note`：它的 Mapper 只允许访问 `ai_*` 表（AGENTS §5），
 * 而「什么算可见内容」的规则只能有一份 —— 所以规则留在 content-service，这里只取结果。
 *
 * <p><b>为什么用 url 而不是服务名（刻意，不是偷懒）</b>：
 * 本仓库 dev/test 共用同一个 Nacos 命名空间，用 {@code name = "content-service"} 走服务发现时，
 * 本机 ai-service 可能被路由到**测试机上**的 content-service —— 那是另一个库、另一批文章，
 * 表现为「同步过来的语料跟本机库对不上」，且日志里没有任何异常。
 * 这与 AGENTS 里「Python 地址只有一个键」是同一个思路：地址显式、可配置、可预测。
 *
 * <p><b>为什么没有 FallbackFactory（刻意）</b>：本模块没有 circuit breaker 依赖，
 * 而 Spring Cloud OpenFeign 在缺少它时会**忽略** {@code fallbackFactory} —— 降级方法永远不会被调用，
 * 却让人以为「失败已经处理了」。仓库里已有一个因此被删除的 `PythonAiClientFallbackFactory`。
 * 所以失败处理交给调用方显式做：{@link com.stellarink.ai.corpus.service.CorpusSyncService}
 * 捕获异常后**保留旧快照、只记失败**，绝不清空（见该实现里的说明）。
 */
@FeignClient(
        name = "content-service-client",
        url = "${stellar.ink.content.base-url:http://127.0.0.1:8102}")
public interface ContentCorpusClient {

    /**
     * 语料清单（不含正文）。
     *
     * @param since ISO-8601，只取该时间之后修改的（严格大于）；null = 全量
     * @param ids   只取这些 id；null/空 = 不限
     * @param limit 上限（content-service 侧会夹到 [1,1000]）
     */
    @GetMapping("/internal/corpus")
    Response<CorpusSliceVO> slice(
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit);

    /** 单篇正文（嵌入用）。草稿 / 私有 / 已删除在 content-service 侧就是 404。 */
    @GetMapping("/internal/corpus/{kind}/{id}")
    Response<CorpusContentVO> content(@PathVariable("kind") String kind, @PathVariable("id") Long id);
}
