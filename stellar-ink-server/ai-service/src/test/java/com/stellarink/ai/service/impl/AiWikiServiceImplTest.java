package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.dto.AiWikiClaimDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.ai.mapper.AiWikiClaimMapper;
import com.stellarink.ai.pojo.AiWikiClaim;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 落库的账要算对（H2 真落库，**含那条幂等唯一键**）。
 *
 * <p>这一层的价值全在「重复构建」上：新增文章、换模型、手滑重跑都会触发重建，
 * 而只回「新增 N 条」的实现会让第二次构建看起来又在膨胀知识库。
 * 所以用例断言的是**三种结果分别计数**（新增 / 更新 / 未变动），以及缺证据的记录不落库。
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiWikiServiceImplTest {

    @Autowired
    private AiWikiServiceImpl service;

    @Autowired
    private AiWikiClaimMapper claimMapper;

    /** Python 客户端与调用账都换成替身：这里验的是落库逻辑，不是 HTTP 与配额 */
    @MockBean
    private com.stellarink.aiclient.client.PythonAiClient pythonAiClient;

    @MockBean
    private com.stellarink.ai.service.AiUsageService usageService;

    /**
     * 每个用例前清空这张表。
     *
     * <p>为什么必需：ai-service 的 {@code @SpringBootTest} **共用一个 H2 库**
     * （上下文按 profile 缓存，不按测试类重建），所以上一个用例留下的行会污染下一个用例的计数 ——
     * 实测表现是「expected: 2 but was: 3」这种看起来像逻辑错、实际是脏数据的断言失败。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearClaims() {
        claimMapper.delete(null);
    }

    private static AiWikiClaimDTO claim(String text, Double confidence, String heading) {
        return AiWikiClaimDTO.builder()
                .text(text)
                .postId(7L)
                .chunkIndex(0)
                .postVersion("v1")
                .contentHash("hash0")
                .quote("每天写五百字")
                .headingPath(heading)
                .confidence(confidence)
                .build();
    }

    private AiWikiClaimsResultDTO pythonResult(List<AiWikiClaimDTO> claims, int proposed, int kept) {
        return AiWikiClaimsResultDTO.builder()
                .claims(claims)
                .stats(AiWikiClaimsResultDTO.AiWikiStatsDTO.builder()
                        .proposed(proposed)
                        .kept(kept)
                        .dropped(Map.of("quoteNotFound", proposed - kept))
                        .posts(1)
                        .entityProposed(2)
                        .entityKept(1)
                        .entities(1)
                        .build())
                .notes(List.of("Python 侧的解释"))
                .usageModel("stub-chat")
                .latencyMs(12L)
                .build();
    }

    /** 让 `around` 直接执行被包的那次调用（真实实现里它还会做配额与记账） */
    private void stubUsagePassthrough() {
        when(usageService.around(any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
    }

    @Test
    @DisplayName("首次构建：全部新增，且两边的账都回来")
    void firstBuildInsertsEverything() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any())).thenReturn(pythonResult(List.of(
                claim("每天写五百字可以累积成十八万字", 0.9, "写作方法"),
                claim("把目标切到小得不可能失败是关键", 0.75, "写作方法")
        ), 3, 2));

        AiWikiBuildVO result = service.build(AiWikiClaimsRequestDTO.builder().maxPosts(1).build());

        assertEquals(2, result.getInserted());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getSkipped());
        assertEquals(3, result.getProposed(), "模型提了多少要如实带回");
        assertEquals(Map.of("quoteNotFound", 1), result.getDropped(), "被校验挡掉多少也要带回");
        assertEquals(2, claimMapper.selectCount(null).intValue());
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("新增 2 条")));
    }

    @Test
    @DisplayName("重复构建：**未变动**而不是又新增一批（幂等锚点生效）")
    void rebuildIsIdempotent() {
        stubUsagePassthrough();
        List<AiWikiClaimDTO> claims = List.of(claim("重复构建不该产生重复行", 0.8, "写作方法"));
        when(pythonAiClient.wikiClaims(any())).thenReturn(pythonResult(claims, 1, 1));

        service.build(AiWikiClaimsRequestDTO.builder().build());
        AiWikiBuildVO second = service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(0, second.getInserted(), "第二次不该再插一行");
        assertEquals(1, second.getSkipped(), "内容一致 → 未变动");
        assertEquals(1, claimMapper.selectCount(null).intValue());
    }

    @Test
    @DisplayName("同一主张、置信度变了：更新而不是新增，也不是未变动")
    void changedConfidenceUpdates() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any()))
                .thenReturn(pythonResult(List.of(claim("置信度变了要更新", 0.4, "写作方法")), 1, 1))
                .thenReturn(pythonResult(List.of(claim("置信度变了要更新", 0.9, "写作方法")), 1, 1));

        service.build(AiWikiClaimsRequestDTO.builder().build());
        AiWikiBuildVO second = service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(0, second.getInserted());
        assertEquals(1, second.getUpdated());
        assertEquals(1, claimMapper.selectCount(null).intValue(), "还是一条，不是两条");
        AiWikiClaim row = claimMapper.selectList(null).get(0);
        assertEquals(0, row.getConfidence().compareTo(new java.math.BigDecimal("0.900")));
    }

    @Test
    @DisplayName("缺证据字段的记录**不落库**，并在 notes 里说明")
    void evidenceLessClaimsAreRejected() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any())).thenReturn(pythonResult(List.of(
                AiWikiClaimDTO.builder().text("没有 contentHash 的主张").postId(7L).build()
        ), 1, 1));

        AiWikiBuildVO result = service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(0, result.getInserted());
        assertEquals(0, claimMapper.selectCount(null).intValue(), "无法核验的东西不许进库");
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("缺证据字段")));
    }

    @Test
    @DisplayName("读者侧按文章读：按段落序号排序，且带上原文片段")
    void readerSideIsOrderedByChunk() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any())).thenReturn(pythonResult(List.of(
                AiWikiClaimDTO.builder().text("第二段的说法").postId(7L).chunkIndex(1)
                        .postVersion("v1").contentHash("hash1").quote("深夜写作").build(),
                AiWikiClaimDTO.builder().text("第一段的说法").postId(7L).chunkIndex(0)
                        .postVersion("v1").contentHash("hash0").quote("每天写五百字").build()
        ), 2, 2));
        service.build(AiWikiClaimsRequestDTO.builder().build());

        var claims = service.claimsOfPost(7L);

        assertEquals(List.of(0, 1), claims.stream().map(c -> c.getChunkIndex()).toList());
        assertEquals("每天写五百字", claims.get(0).getQuote(), "证据要一起回，读者才能核对");
        assertEquals(2L, service.countOfPost(7L));
        assertEquals(0L, service.countOfPost(999L));
        assertTrue(service.claimsOfPost(null).isEmpty(), "空 postId 直接返回空，不查库");
    }
}
