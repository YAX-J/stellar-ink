package com.stellarink.ai.service.impl;

import com.stellarink.aiclient.dto.AiWikiClaimDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.aiclient.dto.AiWikiClaimsResultDTO;
import com.stellarink.aiclient.dto.AiWikiEntityDTO;
import com.stellarink.aiclient.dto.AiWikiRelationDTO;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Autowired
    private com.stellarink.ai.mapper.AiWikiEntityMapper entityMapper;

    @Autowired
    private com.stellarink.ai.mapper.AiWikiEntityMentionMapper mentionMapper;

    @Autowired
    private com.stellarink.ai.mapper.AiWikiRelationMapper relationMapper;

    @Autowired
    private com.stellarink.ai.mapper.AiWikiRelationEvidenceMapper relationEvidenceMapper;

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
        // 先删子表再删主表：证据引用关系、提及引用实体
        relationEvidenceMapper.delete(null);
        relationMapper.delete(null);
        mentionMapper.delete(null);
        entityMapper.delete(null);
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

    /** 造一个带实体与关系的 Python 结果（E4-6 的落库对象） */
    private AiWikiClaimsResultDTO graphResult(int weight, String evidenceText) {
        var claimText = "每天写五百字可以累积成十八万字";
        var mention = AiWikiEntityDTO.AiWikiEntityMentionDTO.builder()
                .name("每天写五百字").postId(7L).chunkIndex(0).claimText(claimText).build();
        var mentionOther = AiWikiEntityDTO.AiWikiEntityMentionDTO.builder()
                .name("十八万字").postId(7L).chunkIndex(0).claimText(claimText).build();
        return AiWikiClaimsResultDTO.builder()
                .claims(List.of(claim(claimText, 0.9, "写作方法")))
                .entities(List.of(
                        AiWikiEntityDTO.builder().name("每天写五百字").normalized("每天写五百字")
                                .kind("concept").count(1).postIds(List.of(7L))
                                .mentions(List.of(mention)).build(),
                        // 同一 normalized 出现两次：真实情况下 Python 每个实体簇只送一条，
                        // 这里是**压力用例**（证明「写法的差异不是不同实体」这条锚点真的在库上生效）
                        AiWikiEntityDTO.builder().name("每天写五百字").normalized("每天写五百字")
                                .kind("concept").count(1).postIds(List.of(7L))
                                .mentions(List.of(mention)).build(),
                        AiWikiEntityDTO.builder().name("十八万字").normalized("十八万字")
                                .kind("concept").count(1).postIds(List.of(7L))
                                .mentions(List.of(mentionOther)).build()))
                .relations(List.of(AiWikiRelationDTO.builder()
                        .source("十八万字").target("每天写五百字").weight(weight)
                        .evidence(List.of(AiWikiRelationDTO.EvidenceDTO.builder()
                                .postId(7L).chunkIndex(0).claimText(evidenceText).build()))
                        .build()))
                .stats(AiWikiClaimsResultDTO.AiWikiStatsDTO.builder()
                        .proposed(1).kept(1).dropped(Map.of()).posts(1)
                        .entityProposed(3).entityKept(3).entities(2).relations(1).build())
                .usageModel("stub-chat").latencyMs(5L).build();
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

    @Test
    @DisplayName("知识图落库：写法不同的同一实体只占一行，提及按唯一键去重")
    void graphPersistenceIsIdempotentByNormalized() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any())).thenReturn(graphResult(1, "每天写五百字可以累积成十八万字"));

        AiWikiBuildVO result = service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(2, result.getEntities(), "「每天写五百字」与「　每天写五百字」是同一个实体");
        assertEquals(1, result.getRelations());
        assertEquals(2, entityMapper.selectCount(null).intValue());
        // 三条提及里有两条完全相同（同一实体同一主张）→ 只写一条
        assertEquals(2, mentionMapper.selectCount(null).intValue());
    }

    @Test
    @DisplayName("重复构建：实体不重复、关系权重更新，且**证据先清后写**")
    void graphRebuildDoesNotDuplicate() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any()))
                .thenReturn(graphResult(1, "每天写五百字可以累积成十八万字"))
                .thenReturn(graphResult(2, "十八万字来自每天写五百字的复利"));

        service.build(AiWikiClaimsRequestDTO.builder().build());
        service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(2, entityMapper.selectCount(null).intValue(), "实体不重复");
        assertEquals(1, relationMapper.selectCount(null).intValue(), "无向边只有一行");
        assertEquals(2, relationMapper.selectList(null).get(0).getWeight(), "权重跟着重建更新");
        // 证据只增不删的话，旧证据会永远留着，而 weight 与证据条数一旦对不上，
        // 这条边就没法用来核对了
        var evidences = relationEvidenceMapper.selectList(null);
        assertEquals(1, evidences.size(), "证据先清后写，不是累加");
        assertEquals("十八万字来自每天写五百字的复利", evidences.get(0).getClaimText());
    }

    @Test
    @DisplayName("关系端点实体缺失：这条边不落库（不画指向不存在实体的线）")
    void relationWithoutEndpointIsSkipped() {
        stubUsagePassthrough();
        AiWikiClaimsResultDTO payload = graphResult(1, "每天写五百字可以累积成十八万字");
        // 把 target 改成一个不在这批实体里的名字
        payload.getRelations().get(0).setTarget("某个没被写进去的实体");
        when(pythonAiClient.wikiClaims(any())).thenReturn(payload);

        AiWikiBuildVO result = service.build(AiWikiClaimsRequestDTO.builder().build());

        assertEquals(0, result.getRelations());
        assertEquals(0, relationMapper.selectCount(null).intValue());
    }

    @Test
    @DisplayName("读者侧实体：只带本文的提及与共现关系，另一端的名字要一起回")
    void entitiesOfPostReturnsMentionsAndRelations() {
        stubUsagePassthrough();
        when(pythonAiClient.wikiClaims(any())).thenReturn(graphResult(1, "每天写五百字可以累积成十八万字"));
        service.build(AiWikiClaimsRequestDTO.builder().build());

        var entities = service.entitiesOfPost(7L);

        assertEquals(2, entities.size());
        // 按名字取，而不是按下标：并列时是按名字排的，写死下标会让用例对排序细节过敏
        var top = entities.stream()
                .filter(entity -> "每天写五百字".equals(entity.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, top.getMentions().size(), "本文的提及：读者在这里核对");
        assertEquals(0, top.getMentions().get(0).getChunkIndex());
        assertNotNull(top.getMentionCount(), "全站计数也带上（与本文提及数是两回事）");
        assertEquals(1, top.getRelations().size());
        assertEquals("十八万字", top.getRelations().get(0).getName(), "读者看的是名字，不是 id");
        assertEquals(1, top.getRelations().get(0).getWeight());
        assertFalse(top.getRelations().get(0).getEvidence().isEmpty(), "边也要回到原文");
    }

    @Test
    @DisplayName("读者侧实体：没有提及的文章返回空列表（不报错、也不去查实体表）")
    void entitiesOfPostIsEmptyForUnknownPost() {
        assertTrue(service.entitiesOfPost(999L).isEmpty());
        assertTrue(service.entitiesOfPost(null).isEmpty(), "空 postId 直接返回空");
    }
}
